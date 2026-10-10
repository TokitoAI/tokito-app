package dev.tokito.physical.dialogue

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** A visible owner authorizes one runtime lifetime, never an implicit keepalive.
 * Losing RESUMED revokes this lease permanently, before deferred native cleanup.
 * Returning to the UI requires an explicit new owner/runtime; native admission
 * still rejects it if previous JNI is finishing.
 */
internal class ForegroundDialogueLease(
    private val lifecycle: Lifecycle,
    private val revoke: () -> Unit,
) : DefaultLifecycleObserver {
    private val active = AtomicBoolean(true)

    init {
        check(lifecycle.currentState == Lifecycle.State.RESUMED) { "Dialogue requires a resumed foreground owner" }
        lifecycle.addObserver(this)
    }

    fun requireActive() {
        check(active.get()) { "Foreground dialogue ownership is revoked" }
    }

    override fun onPause(owner: LifecycleOwner) { close() }
    override fun onStop(owner: LifecycleOwner) { close() }
    override fun onDestroy(owner: LifecycleOwner) { close() }

    fun close() {
        if (active.compareAndSet(true, false)) revoke()
    }

    /** Must run on the lifecycle's main thread; does not join native cleanup. */
    fun detach() { lifecycle.removeObserver(this) }
}
