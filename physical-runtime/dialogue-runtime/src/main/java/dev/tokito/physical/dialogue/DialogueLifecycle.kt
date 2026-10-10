package dev.tokito.physical.dialogue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext

/** Revokes publication immediately, but never destroys an engine still used by JNI.
 * Cancellation is cooperative: a blocked vendor call can delay disposal, not close().
 */
internal class DialogueLifecycle(private val dispose: () -> Unit) {
    private val lock = Any()
    private var closed = false
    private var response: Job? = null

    fun isClosed(): Boolean = synchronized(lock) { closed }

    fun publish(block: () -> Unit): Boolean = synchronized(lock) {
        if (closed) false else { block(); true }
    }

    suspend fun <T> responding(block: suspend () -> T): T = coroutineScope {
        val owner = coroutineContext.job
        synchronized(lock) {
            if (closed) throw CancellationException("Dialogue runtime is closed")
            check(response == null) { "A dialogue response is already active" }
            response = owner
        }
        try {
            block()
        } finally {
            val mustDispose = synchronized(lock) {
                check(response === owner)
                response = null
                closed
            }
            if (mustDispose) dispose()
        }
    }

    fun close(publishClosed: () -> Unit) {
        val owner = synchronized(lock) {
            if (closed) return
            closed = true
            publishClosed()
            response
        }
        if (owner == null) dispose()
        else owner.cancel(CancellationException("Dialogue runtime closed"))
    }
}
