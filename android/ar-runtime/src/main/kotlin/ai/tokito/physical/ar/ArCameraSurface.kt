package ai.tokito.physical.ar

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
        overlay.render(null)
    }
}
