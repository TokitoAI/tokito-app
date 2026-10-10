package dev.tokito.physical.dialogue

import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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
    fun isFinishing(): Boolean = synchronized(lock) { pending != null }
    fun discardCurrent() = synchronized(lock) {
        admitted?.second?.let { resource -> executor.execute { runCatching { resource.close() } } }
        admitted = null
    }

    suspend fun load(deadlineMs: Long, factory: suspend () -> T) = withTimeout(deadlineMs) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val attempt = Any()
            val previous = synchronized(lock) {
                check(!closed) { "Model loader is closed" }
                check(pending == null) { "Previous native model load is still finishing" }
                check(nativeLoadPermit.tryAcquire()) { "A native model load is already finishing" }
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
                            nativeLoadPermit.release()
                            if (closed) executor.shutdown()
                        }
                    }
                }
            } catch (failure: Throwable) {
                synchronized(lock) {
                    if (pending === attempt) {
                        pending = null
                        pendingContinuation = null
                        nativeLoadPermit.release()
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
                executor.execute { runCatching { resource.close() } }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pendingContinuation?.cancel()
            admitted?.second?.let { resource -> executor.execute { runCatching { resource.close() } } }
            admitted = null
            // Queued disposal and in-flight JNI finish; new work is rejected.
            if (pending == null) executor.shutdown()
        }
    }

    private companion object {
        // Recreating a runtime cannot accumulate blocked JNI loads after timeout.
        val nativeLoadPermit = Semaphore(1)
    }
}
