package dev.tokito.physical.dialogue

import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException

/** A blocked vendor JNI call cannot hold the caller's coroutine deadline open.
 * There is at most one native load in flight. Cancellation revokes installation,
 * not an unsafe attempt to interrupt JNI; the worker disposes its late result.
 */
internal class InitializationWorker<T : AutoCloseable> : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokitoModelLoad").apply { isDaemon = true }
    }
    private var closed = false
    private var pending: Any? = null
    private var pendingContinuation: CancellableContinuation<Unit>? = null
    private var admitted: Pair<Any, T>? = null

    fun current(): T? = synchronized(lock) { admitted?.second }
    /** Revoke a loading receipt without destroying an engine leased to generation. */
    fun cancelPending() = synchronized(lock) { pendingContinuation?.cancel() }
    /** Atomically seals an empty loader so caller-side teardown cannot race a new load. */
    fun closeIfEmpty(): Boolean = synchronized(lock) {
        if (pending != null || admitted != null) false
        else { closed = true; executor.shutdown(); true }
    }
    /** Only the response worker holding native admission may synchronously dispose this. */
    fun takeCurrent(): T? = synchronized(lock) { admitted?.second.also { admitted = null } }
    fun isFinishing(): Boolean = synchronized(lock) { pending != null }
    fun discardCurrent() = synchronized(lock) {
        admitted?.second?.let(::scheduleDisposal)
        admitted = null
    }

    suspend fun load(deadlineMs: Long, factory: suspend () -> T) = withTimeout(deadlineMs) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val attempt = Any()
            val previous = synchronized(lock) {
                check(!closed) { "Model loader is closed" }
                check(pending == null) { "Previous native model load is still finishing" }
                check(NativeDialogueAdmission.permit.tryAcquire()) { "Native dialogue work is already finishing" }
                pending = attempt
                pendingContinuation = continuation
                admitted?.second.also { admitted = null }
            }
            continuation.invokeOnCancellation { revoke(attempt) }
            try {
                executor.execute {
                    var candidate: T? = null
                    try {
                        previous?.close()
                        if (!continuation.isActive || synchronized(lock) { closed }) return@execute
                        // No caller Job is inherited by blocking vendor creation.
                        // It finishes here even after the caller stops waiting.
                        candidate = runBlocking { factory() }
                        val accepted = synchronized(lock) {
                            if (!closed && pending === attempt && continuation.isActive) {
                                admitted = attempt to checkNotNull(candidate)
                                candidate = null
                                true
                            } else false
                        }
                        if (accepted) {
                            continuation.resume(Unit, onCancellation = { _, _, _ -> revoke(attempt) })
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } finally {
                        runCatching { candidate?.close() }
                        synchronized(lock) {
                            if (pending === attempt) {
                                pending = null
                            }
                            if (pendingContinuation === continuation) {
                                pendingContinuation = null
                            }
                            NativeDialogueAdmission.permit.release()
                            if (closed) executor.shutdown()
                        }
                    }
                }
            } catch (failure: Throwable) {
                synchronized(lock) {
                    if (pending === attempt) {
                        pending = null
                        pendingContinuation = null
                        NativeDialogueAdmission.permit.release()
                    }
                }
                continuation.resumeWithException(failure)
            }
        }
    }

    private fun revoke(attempt: Any) {
        synchronized(lock) {
            admitted?.takeIf { it.first === attempt }?.second?.let { resource ->
                admitted = null
                // Disposal never blocks the UI/cancellation callback.
                scheduleDisposal(resource)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pendingContinuation?.cancel()
            admitted?.second?.let(::scheduleDisposal)
            admitted = null
            // Queued disposal and in-flight JNI finish; new work is rejected.
            if (pending == null) executor.shutdown()
        }
    }

    private fun scheduleDisposal(resource: T) {
        executor.execute {
            NativeDialogueAdmission.permit.acquire()
            try { runCatching { resource.close() } }
            finally { NativeDialogueAdmission.permit.release() }
        }
    }
}
