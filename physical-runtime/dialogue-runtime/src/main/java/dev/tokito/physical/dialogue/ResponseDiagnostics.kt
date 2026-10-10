package dev.tokito.physical.dialogue

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Observation only: this watchdog never cancels, closes or publishes a result.
 * It has an independent Java timer, not the caller's coroutine event loop.
 * Thread states use fixed role labels; names, stacks and request data are absent.
 */
internal class ResponseDiagnostics(
    private val deadlineMs: Long,
    private val sink: (String) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val started = System.nanoTime()
    private val caller = Thread.currentThread()
    private var native: Thread? = null
    private var cancellation: Thread? = null
    private var timer: ScheduledFuture<*>? = null
    private var closed = false

    fun phase(phase: ResponsePhase) {
        synchronized(lock) {
            when (phase) {
                ResponsePhase.WORKER_ENTER -> native = Thread.currentThread()
                ResponsePhase.CANCEL_JOB_ENTER -> cancellation = Thread.currentThread()
                ResponsePhase.RECEIPT_SETUP -> if (!closed && timer == null) {
                    timer = watchdog.schedule(::deadlineObserved, deadlineMs, TimeUnit.MILLISECONDS)
                }
                else -> Unit
            }
        }
        emit("phase=${phase.name.lowercase()}")
    }

    private fun deadlineObserved() {
        val roles = synchronized(lock) {
            if (closed) return
            Triple(caller, native, cancellation)
        }
        // Emit before querying JVM thread state: a missing following summary is
        // itself useful evidence if an ART safepoint/diagnostic query blocks.
        emit("phase=watchdog_deadline")
        emit("phase=watchdog_threads caller=${roles.first.state.name} " +
            "native=${roles.second?.state?.name ?: "NOT_STARTED"} " +
            "cancel=${roles.third?.state?.name ?: "NOT_STARTED"}")
    }

    private fun emit(fields: String) {
        runCatching { sink("Response diagnostic $fields elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}") }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            timer?.cancel(false)
            timer = null
        }
        emit("phase=receipt_end")
    }

    private companion object {
        // One process-wide daemon and remove-on-cancel queue, not a watchdog
        // thread retained per runtime. It never performs vendor/native cleanup.
        val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "TokitoReceiptWatchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
