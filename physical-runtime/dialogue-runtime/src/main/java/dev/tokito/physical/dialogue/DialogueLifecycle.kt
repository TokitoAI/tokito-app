package dev.tokito.physical.dialogue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
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

/** Only our own deadline becomes a domain failure. An enclosing caller deadline
 * is still coroutine cancellation and must retain its structured ownership.
 */
internal suspend fun <T : Any> generationDeadline(deadlineMs: Long, block: suspend () -> T): T =
    withTimeoutOrNull(deadlineMs) { block() }
        ?: throw DialogueFailure(DialogueFailureCode.GENERATION_TIMEOUT, "Local generation exceeded its deadline")
