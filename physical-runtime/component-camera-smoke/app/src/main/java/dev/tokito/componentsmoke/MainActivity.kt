package dev.tokito.componentsmoke

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.tokito.physical.ComponentDetectionResult
import dev.tokito.physical.LocalComponentVisionRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var overlay: DetectionOverlay
    private lateinit var status: TextView
    private val workerThread = HandlerThread("tokito-camera").apply { start() }
    private val cameraHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inference = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var runtime: LocalComponentVisionRuntime? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preview = TextureView(this).also { it.surfaceTextureListener = this }
        overlay = DetectionOverlay(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); textSize = 16f
            setPadding(24, 18, 24, 18); text = "Loading local vision model…"
        }
        setContentView(FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(this@MainActivity.overlay, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        })
        inference.execute {
            runCatching {
                val bundle = File(filesDir, "component-smoke").apply { mkdirs() }
                listOf("model.tflite", "component-manifest.json").forEach { name ->
                    assets.open(name).use { input -> File(bundle, name).outputStream().use(input::copyTo) }
                }
                LocalComponentVisionRuntime.load(bundle)
            }.onSuccess {
                runtime = it
                mainHandler.post { status.text = "Camera ready — point at a circuit board"; startIfReady() }
            }.onFailure { error -> mainHandler.post { status.text = "Model error: ${error.message}" } }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 7 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startIfReady()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = startIfReady()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    @SuppressLint("MissingPermission")
    private fun startIfReady() {
        if (!preview.isAvailable || runtime == null || camera != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
        val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val selected = sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
        selected?.let { preview.surfaceTexture?.setDefaultBufferSize(it.width, it.height) }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) { camera = device; beginPreview(device) }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; mainHandler.post { status.text = "Camera error $error" } }
        }, cameraHandler)
    }

    private fun beginPreview(device: CameraDevice) {
        val surface = Surface(preview.surfaceTexture)
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                session = value
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                value.setRepeatingRequest(request, null, cameraHandler)
                scheduleAnalysis()
            }
            override fun onConfigureFailed(value: CameraCaptureSession) { mainHandler.post { status.text = "Camera configuration failed" } }
        }, cameraHandler)
    }

    private fun scheduleAnalysis() {
        mainHandler.postDelayed({
            val bitmap = preview.bitmap
            val detector = runtime
            if (bitmap != null && detector != null && analyzing.compareAndSet(false, true)) {
                inference.execute {
                    runCatching { detector.analyze(bitmap) }.onSuccess { result ->
                        writeReport(result)
                        mainHandler.post {
                            overlay.result = result
                            status.text = "${result.detections.size} detections • ${"%.0f".format(result.inferenceNanos / 1e6)} ms • local test model"
                        }
                    }.onFailure { error -> mainHandler.post { status.text = "Inference error: ${error.message}" } }
                    analyzing.set(false); bitmap.recycle()
                }
            }
            if (!isFinishing) scheduleAnalysis()
        }, 900)
    }

    private fun writeReport(result: ComponentDetectionResult) {
        val rows = JSONArray()
        result.detections.forEach { d -> rows.put(JSONObject().put("label", d.className).put("confidence", d.confidence).put("box", JSONArray(listOf(d.left, d.top, d.right, d.bottom)))) }
        File(filesDir, "component-camera-smoke.json").writeText(JSONObject()
            .put("device", android.os.Build.MODEL).put("inference_ms", result.inferenceNanos / 1e6)
            .put("detection_count", result.detections.size).put("detections", rows)
            .put("release_eligible", false).put("evaluation_valid", false).toString(2))
    }

    override fun onDestroy() {
        session?.close(); camera?.close(); runtime?.close(); inference.shutdownNow(); workerThread.quitSafely()
        super.onDestroy()
    }
}

private class DetectionOverlay(context: Context) : View(context) {
    private val box = Paint().apply { color = 0xff32e6a1.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val text = Paint().apply { color = Color.WHITE; textSize = 34f; style = Paint.Style.FILL; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
    var result: ComponentDetectionResult? = null
        set(value) { field = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val value = result ?: return
        val sx = width.toFloat() / value.inputWidth
        val sy = height.toFloat() / value.inputHeight
        value.detections.forEach { d ->
            canvas.drawRect(d.left * sx, d.top * sy, d.right * sx, d.bottom * sy, box)
            canvas.drawText("${d.className} ${"%.2f".format(d.confidence)}", d.left * sx + 4f, (d.top * sy - 8f).coerceAtLeast(36f), text)
        }
    }
}
