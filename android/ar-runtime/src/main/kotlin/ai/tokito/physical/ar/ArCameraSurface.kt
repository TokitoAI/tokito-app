package ai.tokito.physical.ar

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

class ArCameraSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
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
    }

    fun start(lifecycleOwner: LifecycleOwner, onStatus: (Status) -> Unit = {}): StartResult {
        if (controller != null) return StartResult.AlreadyRunning
        return try {
            val next = LifecycleCameraController(context).apply {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            }
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
            }
            cameraStateSource = source
            cameraStateObserver = observer
            source?.observe(lifecycleOwner, observer)
            controller = next
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
        cameraStateObserver?.let { cameraStateSource?.removeObserver(it) }
        cameraStateObserver = null
        cameraStateSource = null
        preview.controller = null
        controller?.unbind()
        controller = null
        digitalTwin.suspendRendering()
        overlay.render(null)
    }

    @ExperimentalCamera2Interop
    fun calibratedPreviewIntrinsics(): CameraIntrinsics? {
        val info = controller?.cameraInfo ?: return null
        if (preview.width <= 0 || preview.height <= 0) return null
        val camera2 = Camera2CameraInfo.from(info)
        val calibration = camera2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            ?: return null
        if (calibration.size < 4) return null
        val active = camera2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: camera2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val sensorOrientation = camera2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
            ?: return null
        val displayDegrees = when (preview.display?.rotation ?: android.view.Surface.ROTATION_0) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> return null
        }
        val relativeRotation = (sensorOrientation - displayDegrees + 360) % 360
        return CameraCalibrationMapper.toPreview(
            sensorFx = calibration[0].toDouble(),
            sensorFy = calibration[1].toDouble(),
            sensorCx = calibration[2].toDouble() - active.left,
            sensorCy = calibration[3].toDouble() - active.top,
            sensorWidth = active.width(),
            sensorHeight = active.height(),
            clockwiseRotationDegrees = relativeRotation,
            previewWidth = preview.width,
            previewHeight = preview.height,
        )
    }

    fun destroy() {
        stop()
        digitalTwin.destroy()
    }
}
