package ai.tokito.physical.ar

import android.os.Looper
import android.view.Choreographer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Lifecycle-bound presentation loop; expiry and suppression clear the view on the next frame. */
class ArPresenter(
    private val runtime: ArRuntime,
    private val overlay: ArOverlayView,
    private val onSuppressed: (String) -> Unit = {},
) : DefaultLifecycleObserver, Choreographer.FrameCallback {
    private var running = false
    private var lastDecision: RenderDecision? = null

    fun bind(owner: LifecycleOwner) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "presenter must bind on the main thread" }
        owner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        lastDecision = null
        overlay.render(null)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val decision = runtime.render()
        if (decision != lastDecision) {
            when (decision) {
                is RenderDecision.Ready -> overlay.render(decision.scene)
                is RenderDecision.Suppressed -> {
                    overlay.render(null)
                    onSuppressed(decision.reason)
                }
            }
            lastDecision = decision
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
