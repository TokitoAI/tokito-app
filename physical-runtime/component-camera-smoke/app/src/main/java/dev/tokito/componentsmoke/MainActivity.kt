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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inference = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var runtime: LocalComponentVisionRuntime? = null
    private val modelLock = Any()
    @Volatile private var active = false
    @Volatile private var destroyed = false
    private var opening = false
    @Volatile private var epoch = 0L
    private var previewSurface: Surface? = null

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
                val accepted = synchronized(modelLock) { if (destroyed) false else { runtime = it; true } }
                if (!accepted) it.close()
                else mainHandler.post { if (!destroyed) { status.text = "Camera ready — point at a circuit board"; startIfReady() } }
            }.onFailure { error -> mainHandler.post { if (!destroyed) status.text = "Model error: ${error.message}" } }
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
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }

    override fun onStart() { super.onStart(); active = true; startIfReady() }
    override fun onStop() { active = false; closeCamera(); super.onStop() }

    private fun closeCamera() {
        epoch += 1
        opening = false
        mainHandler.removeCallbacksAndMessages(null)
        session?.close(); session = null
        camera?.close(); camera = null
        previewSurface?.release(); previewSurface = null
        overlay.result = null
    }

    @SuppressLint("MissingPermission")
    private fun startIfReady() {
        if (!active || destroyed || opening || !preview.isAvailable || runtime == null || camera != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val owner = epoch
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
        val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val selected = sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
        selected?.let { preview.surfaceTexture?.setDefaultBufferSize(it.width, it.height) }
        opening = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!active || destroyed || owner != epoch) { device.close(); return }
                    opening = false; camera = device
                    try { beginPreview(device, owner) }
                    catch (error: Exception) { closeCamera(); status.text = "Camera unavailable: ${error.message}" }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); if (owner == epoch) closeCamera()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (owner == epoch) { closeCamera(); status.text = "Camera error $error" }
                }
            }, mainHandler)
        } catch (error: Exception) { closeCamera(); status.text = "Camera unavailable: ${error.message}" }
    }

    private fun beginPreview(device: CameraDevice, owner: Long) {
        val surface = Surface(preview.surfaceTexture)
        previewSurface = surface
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                if (!active || destroyed || owner != epoch || camera !== device) { value.close(); return }
                session = value
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    value.setRepeatingRequest(request, null, mainHandler)
                    scheduleAnalysis(owner)
                } catch (error: Exception) { closeCamera(); status.text = "Camera unavailable: ${error.message}" }
            }
            override fun onConfigureFailed(value: CameraCaptureSession) {
                value.close(); if (owner == epoch) { closeCamera(); status.text = "Camera configuration failed" }
            }
        }, mainHandler)
    }

    private fun scheduleAnalysis(owner: Long) {
        mainHandler.postDelayed({
            if (!active || destroyed || owner != epoch) return@postDelayed
            val detector = runtime
            val claimed = detector != null && analyzing.compareAndSet(false, true)
            val bitmap = if (claimed) runCatching { preview.bitmap }.getOrNull() else null
            if (bitmap != null && detector != null) {
                inference.execute {
                    try { runCatching { detector.analyze(bitmap) }.onSuccess { result ->
                        if (active && !destroyed && owner == epoch) writeReport(result)
                        mainHandler.post {
                            if (!active || destroyed || owner != epoch) return@post
                            overlay.result = result
                            status.text = "${result.detections.size} detections • ${"%.0f".format(result.inferenceNanos / 1e6)} ms • local test model"
                        }
                    }.onFailure { error -> mainHandler.post {
                        if (active && !destroyed && owner == epoch) status.text = "Inference error: ${error.message}"
                    } } } finally { analyzing.set(false); bitmap.recycle() }
                }
            } else if (claimed) analyzing.set(false)
            if (active && owner == epoch) scheduleAnalysis(owner)
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
        val retired = synchronized(modelLock) { destroyed = true; runtime.also { runtime = null } }
        active = false; closeCamera()
        // Serialize destruction behind any inference that already owns the model.
        inference.execute { retired?.close(); inference.shutdown() }
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
