package ai.tokito.physical.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ArCameraSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    data class CameraFrame(
        val bitmap: Bitmap,
        val timestampNanos: Long,
        val previewWidth: Int,
        val previewHeight: Int,
        val analysisToPreview: Matrix?,
        val geometryRevision: Long = 0,
    )

    val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        contentDescription = "Live camera view"
    }
    val overlay = ArOverlayView(context)
    val digitalTwin = DigitalTwinView(context)
    private var controller: LifecycleCameraController? = null
    private var cameraStateSource: LiveData<CameraState>? = null
    private var cameraStateObserver: Observer<CameraState>? = null
    private var zoomSource: LiveData<ZoomState>? = null
    private var zoomObserver: Observer<ZoomState>? = null
    var onZoomChanged: ((Float?, Float?, Float?) -> Unit)? = null
    var onCameraGeometryChanged: (() -> Unit)? = null
    @Volatile var geometryRevision: Long = 0
        private set
    private var lastZoomRatio: Float? = null
    private var pinchActive = false
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val ratio = zoomSource?.value?.zoomRatio ?: return false
            setZoomRatio(ratio * detector.scaleFactor)
            return true
        }
    })

    val currentZoomRatio: Float? get() = zoomSource?.value?.zoomRatio

    fun setZoomRatio(ratio: Float) {
        if (!ratio.isFinite()) return
        val state = zoomSource?.value ?: return
        controller?.setZoomRatio(ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (event.pointerCount > 1 && !pinchActive) {
            pinchActive = true
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (pinchActive) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinchActive = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tokito-camera-analysis")
    }
    private var frameListener: ((CameraFrame) -> Unit)? = null
    @Volatile private var sensorToPreview: Matrix? = null
    private var deliveredFrames = 0L

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyRunning : StartResult
        data class Failed(val reason: String) : StartResult
    }

    sealed interface Status {
        data object Opening : Status
        data object Open : Status
        data object Closing : Status
        data object Closed : Status
        data class Unavailable(val reason: String) : Status
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(preview, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(digitalTwin, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshSensorToPreview() }
    }

    fun start(lifecycleOwner: LifecycleOwner, onStatus: (Status) -> Unit = {}): StartResult {
        if (controller != null) return StartResult.AlreadyRunning
        return try {
            val next = LifecycleCameraController(context).apply {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                setPinchToZoomEnabled(false) // The guidance overlay owns single-tap registration.
                setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                setImageAnalysisOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            }
            configureAnalyzer(next)
            preview.controller = next
            next.bindToLifecycle(lifecycleOwner)
            val source = next.cameraInfo?.cameraState
            val observer = Observer<CameraState> { state ->
                val status = state.error?.let { Status.Unavailable("camera error ${it.code}") } ?: when (state.type) {
                    CameraState.Type.OPENING, CameraState.Type.PENDING_OPEN -> Status.Opening
                    CameraState.Type.OPEN -> Status.Open
                    CameraState.Type.CLOSING -> Status.Closing
                    CameraState.Type.CLOSED -> Status.Closed
                }
                onStatus(status)
                if (status == Status.Open) preview.post(::refreshSensorToPreview)
            }
            cameraStateSource = source
            cameraStateObserver = observer
            source?.observe(lifecycleOwner, observer)
            controller = next
            zoomSource = next.zoomState
            zoomObserver = Observer<ZoomState> { state ->
                if (state != null) {
                    if (lastZoomRatio != null && lastZoomRatio != state.zoomRatio) {
                        geometryRevision++
                        sensorToPreview = null
                        onCameraGeometryChanged?.invoke()
                        preview.post(::refreshSensorToPreview)
                    }
                    lastZoomRatio = state.zoomRatio
                    onZoomChanged?.invoke(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
                }
            }
            zoomSource?.observe(lifecycleOwner, requireNotNull(zoomObserver))
            StartResult.Started
        } catch (failure: RuntimeException) {
            cameraStateObserver?.let { cameraStateSource?.removeObserver(it) }
            preview.controller = null
            cameraStateSource = null
            cameraStateObserver = null
            controller = null
            StartResult.Failed(failure.message ?: failure::class.java.simpleName)
        }
    }

    fun stop() {
        zoomObserver?.let { zoomSource?.removeObserver(it) }
        zoomObserver = null
        zoomSource = null
        lastZoomRatio = null
        pinchActive = false
        geometryRevision++
        onZoomChanged?.invoke(null, null, null)
        cameraStateObserver?.let { cameraStateSource?.removeObserver(it) }
        cameraStateObserver = null
        cameraStateSource = null
        preview.controller = null
        controller?.unbind()
        controller = null
        digitalTwin.suspendRendering()
        overlay.render(null)
    }

    fun setFrameListener(listener: ((CameraFrame) -> Unit)?) {
        frameListener = listener
        controller?.let(::configureAnalyzer)
    }

    private fun refreshSensorToPreview() {
        sensorToPreview = preview.sensorToViewTransform?.let(::Matrix)
    }

    private fun configureAnalyzer(target: LifecycleCameraController) {
        target.clearImageAnalysisAnalyzer()
        if (frameListener != null) {
            target.setImageAnalysisAnalyzer(analysisExecutor, ::analyzeFrame)
            Log.i(TAG, "Camera analysis use case enabled")
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        val revision = geometryRevision
        val listener = frameListener
        if (listener == null) {
            image.close()
            return
        }
        val rotationDegrees = image.imageInfo.rotationDegrees
        val rawWidth = image.width
        val rawHeight = image.height
        val bitmap = try {
            image.toBitmap().upright(rotationDegrees)
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Camera analysis frame conversion failed: format=${image.format}", failure)
            image.close()
            return
        }
        val timestamp = System.nanoTime()
        val analysisToPreview = sensorToPreview?.let { sensorToView ->
            val bufferToSensor = Matrix()
            if (!image.imageInfo.sensorToBufferTransformMatrix.invert(bufferToSensor)) null
            else {
                val uprightToBuffer = Matrix()
                if (!rawToUpright(rawWidth, rawHeight, rotationDegrees).invert(uprightToBuffer)) null
                else {
                    val bufferToPreview = Matrix().apply { setConcat(sensorToView, bufferToSensor) }
                    Matrix().apply { setConcat(bufferToPreview, uprightToBuffer) }
                }
            }
        }
        image.close()
        deliveredFrames += 1
        if (deliveredFrames == 1L) Log.d(TAG, "Delivered first camera analysis frame")
        if (revision != geometryRevision) { bitmap.recycle(); return }
        listener(CameraFrame(bitmap, timestamp, preview.width, preview.height, analysisToPreview, revision))
    }

    private fun Bitmap.upright(rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return this
        val rotated = Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
        if (rotated !== this) recycle()
        return rotated
    }

    private fun rawToUpright(width: Int, height: Int, rotationDegrees: Int): Matrix = Matrix().apply {
        val values = when (((rotationDegrees % 360) + 360) % 360) {
            0 -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            90 -> floatArrayOf(0f, -1f, height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
            180 -> floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
            270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width.toFloat(), 0f, 0f, 1f)
            else -> error("CameraX returned an unsupported image rotation")
        }
        setValues(values)
    }

    @ExperimentalCamera2Interop
    fun calibratedPreviewIntrinsics(): CameraIntrinsics? {
        val info = controller?.cameraInfo ?: return null
        if (preview.width <= 0 || preview.height <= 0) return null
        val camera2 = Camera2CameraInfo.from(info)
        val calibration = camera2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            ?: return null
        if (calibration.size < 4) return null
        val transform = preview.sensorToViewTransform ?: return null
        val points = floatArrayOf(
            calibration[2], calibration[3],
            calibration[2] + calibration[0], calibration[3],
            calibration[2], calibration[3] + calibration[1],
        )
        transform.mapPoints(points)
        val fx = maxOf(kotlin.math.abs(points[2] - points[0]), kotlin.math.abs(points[4] - points[0])).toDouble()
        val fy = maxOf(kotlin.math.abs(points[3] - points[1]), kotlin.math.abs(points[5] - points[1])).toDouble()
        return runCatching {
            CameraIntrinsics(fx, fy, points[0].toDouble(), points[1].toDouble(), preview.width, preview.height)
        }.getOrNull()
    }

    fun destroy() {
        stop()
        analysisExecutor.shutdownNow()
        digitalTwin.destroy()
    }

    private companion object {
        const val TAG = "TokitoPoseVision"
    }
}
