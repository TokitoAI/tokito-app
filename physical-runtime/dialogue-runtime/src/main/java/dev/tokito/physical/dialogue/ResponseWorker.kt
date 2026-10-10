package dev.tokito.physical.dialogue

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException

/** The caller owns only a receipt, never a child that joins vendor JNI.
 * Revoking a receipt quarantines this worker permanently. The worker retains
 * exclusive native admission until generation, cleanup and disposal finish.
 */
internal class ResponseWorker(
    private val retireEmpty: () -> Boolean = { false },
    private val dispose: () -> Unit,
) : AutoCloseable {
    private class Attempt<T>(val receipt: CancellableContinuation<T>) {
        var job: Job? = null
        var revoked = false
    }
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokitoResponse").apply { isDaemon = true }
    }
    private val cancellation = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokitoResponseCancel").apply { isDaemon = true }
    }
    private var pending: Attempt<*>? = null
    private var closed = false
    private var quarantined = false
    private var disposed = false

    fun isQuarantined(): Boolean = synchronized(lock) { quarantined }

    suspend fun <T : Any> execute(deadlineMs: Long, block: suspend () -> T): T =
        withTimeoutOrNull(deadlineMs) {
            suspendCancellableCoroutine { receipt ->
                val attempt = Attempt(receipt)
                synchronized(lock) {
                    check(!closed && !quarantined) { "Native response worker is unavailable" }
                    check(pending == null) { "A native response is already active" }
                    check(NativeDialogueAdmission.permit.tryAcquire()) { "Native dialogue work is still finishing" }
                    pending = attempt
                }
                receipt.invokeOnCancellation { revoke(attempt) }
                worker.execute {
                    var outcome: Result<T>? = null
                    try {
                        val result = runBlocking {
                            val revoked = synchronized(lock) {
                                attempt.job = coroutineContext.job
                                attempt.revoked || closed
                            }
                            if (revoked) throw CancellationException("Native response revoked")
                            block()
                        }
                        outcome = Result.success(result)
                    } catch (failure: Throwable) {
                        outcome = Result.failure(failure)
                    } finally {
                        synchronized(lock) { if (pending === attempt) pending = null }
                        // No caller can close the engine while this lease is active.
                        try { disposeIfSafe() } finally { NativeDialogueAdmission.permit.release() }
                    }
                    val accepted = synchronized(lock) {
                        !attempt.revoked && !closed && receipt.isActive
                    }
                    if (accepted) checkNotNull(outcome).fold(
                        onSuccess = { receipt.resume(it, onCancellation = { _, _, _ -> revoke(attempt) }) },
                        onFailure = { receipt.resumeWithException(it) },
                    )
                }
            }
        } ?: throw DialogueFailure(DialogueFailureCode.GENERATION_TIMEOUT, "Local generation exceeded its deadline")

    private fun revoke(attempt: Attempt<*>) {
        synchronized(lock) {
            if (disposed) return
            attempt.revoked = true
            quarantined = true
            val job = attempt.job
            // Even cancellation handlers must not hold the UI/receipt timer.
            cancellation.execute {
                job?.cancel(CancellationException("Native response receipt revoked"))
                scheduleIdleDisposal()
            }
        }
    }

    private fun scheduleIdleDisposal() {
        val empty = synchronized(lock) {
            if (pending != null || disposed) return
            // This callback must atomically seal a native-resource-free owner,
            // not merely observe an empty handle that a late load could replace.
            if (retireEmpty()) { disposed = true; true }
            else {
                NativeDialogueAdmission.disposeWhenIdle { disposeIfSafe() }
                false
            }
        }
        if (empty) disposeAndShutdown()
    }

    private fun disposeIfSafe() {
        val eligible = synchronized(lock) {
            if (pending != null || disposed || !(closed || quarantined)) false
            else { disposed = true; true }
        }
        if (eligible) disposeAndShutdown()
    }

    private fun disposeAndShutdown() {
        try { dispose() } finally {
            worker.shutdown()
            cancellation.shutdown()
        }
    }

    override fun close() {
        val attempt = synchronized(lock) {
            if (closed) return
            closed = true
            pending
        }
        if (attempt != null) {
            attempt.receipt.cancel(CancellationException("Native response worker closed"))
        } else scheduleIdleDisposal()
    }
}
