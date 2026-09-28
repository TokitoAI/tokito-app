package dev.tokito.physical

import dev.tokito.physical.voice.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface CallState {
    data object Idle : CallState
    data object Preparing : CallState
    data object Listening : CallState
    data object Thinking : CallState
    data object Speaking : CallState
    data class Unavailable(val reason: String) : CallState
}

/** Serializes the hands-free STT -> dialogue -> TTS loop without exposing its text. */
internal class VoiceCallController(
    private val scope: CoroutineScope,
    private val prepare: suspend () -> Unit,
    private val startListening: suspend () -> Unit,
    private val stopListening: suspend () -> Unit,
    private val interruptSpeech: suspend () -> Unit,
    private val respond: suspend (String) -> String,
    private val speak: suspend (String, () -> Unit) -> Unit,
    private val onState: (CallState) -> Unit,
) : AutoCloseable {
    private var active = false
    private var closed = false
    private var state: CallState = CallState.Idle
    private var turn: Job? = null
    private var lastFinalTimestamp = Long.MIN_VALUE
    private val preparationMutex = Mutex()
    @Volatile private var prepared = false

    fun currentState(): CallState = state

    suspend fun toggle() {
        if (active) stop() else start()
    }

    suspend fun warmUp() {
        check(!closed) { "voice call controller is closed" }
        if (prepared) return
        if (!active) transition(CallState.Preparing)
        try {
            ensurePrepared()
            if (!active) transition(CallState.Idle)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (!active) transition(CallState.Unavailable(failure.message ?: "Local voice call is unavailable"))
        }
    }

    suspend fun start() {
        check(!closed) { "voice call controller is closed" }
        if (active) return
        active = true
        if (!prepared) transition(CallState.Preparing)
        try {
            ensurePrepared()
            if (!active) return
            startListening()
            transition(CallState.Listening)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            active = false
            transition(CallState.Unavailable(failure.message ?: "Local voice call is unavailable"))
        }
    }

    suspend fun stop() {
        if (!active && state == CallState.Idle) return
        active = false
        turn?.cancel()
        turn = null
        runCatching { interruptSpeech() }
        runCatching { stopListening() }
        transition(CallState.Idle)
    }

    fun accept(segment: TranscriptSegment) {
        val utterance = segment.text.trim()
        if (!active || !segment.isFinal || state != CallState.Listening || utterance.isEmpty()) return
        if (segment.capturedAtMonotonicMillis <= lastFinalTimestamp) return
        lastFinalTimestamp = segment.capturedAtMonotonicMillis
        turn = scope.launch {
            transition(CallState.Thinking)
            try {
                val response = respond(utterance).trim()
                require(response.isNotEmpty()) { "Local dialogue returned an empty response" }
                if (!active) return@launch
                val playbackStarted = CompletableDeferred<Unit>()
                val playbackState = scope.launch {
                    playbackStarted.await()
                    if (active && state == CallState.Thinking) transition(CallState.Speaking)
                }
                try {
                    speak(response) { playbackStarted.complete(Unit) }
                } finally {
                    if (playbackStarted.isCompleted) playbackState.join() else playbackState.cancel()
                }
                if (active) transition(CallState.Listening)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                active = false
                runCatching { stopListening() }
                transition(CallState.Unavailable(failure.message ?: "Local conversation failed"))
            } finally {
                if (!active) runCatching { stopListening() }
                turn = null
            }
        }
    }

    fun fail(reason: String) {
        if (!active) return
        active = false
        turn?.cancel()
        turn = null
        scope.launch { runCatching { stopListening() } }
        transition(CallState.Unavailable(reason))
    }

    override fun close() {
        if (closed) return
        closed = true
        active = false
        turn?.cancel()
        turn = null
    }

    private suspend fun ensurePrepared() = preparationMutex.withLock {
        if (prepared) return@withLock
        prepare()
        prepared = true
    }

    private fun transition(next: CallState) {
        if (state == next) return
        state = next
        onState(next)
    }
}
