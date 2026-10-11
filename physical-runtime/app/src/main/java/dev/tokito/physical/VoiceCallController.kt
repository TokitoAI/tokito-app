package dev.tokito.physical

import dev.tokito.physical.voice.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

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
    private var ownership = 0L
    private var lastFinalTimestamp = Long.MIN_VALUE
    private val preparationMutex = Mutex()
    private val audioOwnershipMutex = Mutex()
    @Volatile private var prepared = false
    private var preparationRevision = 0L

    fun currentState(): CallState = state

    /** Host foreground retirement invalidates prepared native resources as well as turns. */
    fun invalidatePreparation() { preparationRevision++; prepared = false }

    suspend fun toggle() {
        if (active) stop() else start()
    }

    suspend fun warmUp() {
        check(!closed) { "voice call controller is closed" }
        val owner = ownership
        if (prepared) return
        if (!active) transition(CallState.Preparing)
        try {
            ensurePrepared()
            if (!active && owner == ownership) transition(CallState.Idle)
        } catch (failure: CancellationException) {
            if (!active && owner == ownership) transition(CallState.Idle)
            throw failure
        } catch (failure: Throwable) {
            if (!active && owner == ownership) transition(CallState.Unavailable(failure.message ?: "Local voice call is unavailable"))
        }
    }

    suspend fun start() {
        check(!closed) { "voice call controller is closed" }
        if (active) return
        active = true
        val owner = ++ownership
        if (!prepared) transition(CallState.Preparing)
        try {
            ensurePrepared()
            if (!active || owner != ownership || closed) return
            audioOwnershipMutex.withLock {
                if (active && owner == ownership && !closed) startListening()
            }
            if (!active || owner != ownership || closed) return
            transition(CallState.Listening)
        } catch (failure: CancellationException) {
            if (owner == ownership) { active = false; transition(CallState.Idle) }
            throw failure
        } catch (failure: Throwable) {
            if (owner == ownership) {
                active = false
                transition(CallState.Unavailable(failure.message ?: "Local voice call is unavailable"))
            }
        }
    }

    suspend fun stop() {
        cleanupRevokedTurn(revokeActiveTurn())
    }

    /** Called synchronously by host onStop, before deferred audio cleanup. */
    fun revokeActiveTurn(): Long {
        ownership++
        active = false
        turn?.cancel()
        turn = null
        transition(CallState.Idle)
        return ownership
    }

    suspend fun cleanupRevokedTurn(owner: Long) = audioOwnershipMutex.withLock {
        if (owner != ownership || active || closed) return@withLock
        runCatching { interruptSpeech() }
        runCatching { stopListening() }
    }

    fun accept(segment: TranscriptSegment) {
        val utterance = segment.text.trim()
        if (!active || !segment.isFinal || state != CallState.Listening || utterance.isEmpty()) return
        if (segment.capturedAtMonotonicMillis <= lastFinalTimestamp) return
        lastFinalTimestamp = segment.capturedAtMonotonicMillis
        val owner = ownership
        turn = scope.launch {
            val ownedTurn = coroutineContext.job
            transition(CallState.Thinking)
            try {
                val response = respond(utterance).trim()
                require(response.isNotEmpty()) { "Local dialogue returned an empty response" }
                if (!active || owner != ownership) return@launch
                val playbackStarted = CompletableDeferred<Unit>()
                val playbackState = scope.launch {
                    playbackStarted.await()
                    if (active && owner == ownership && state == CallState.Thinking) transition(CallState.Speaking)
                }
                try {
                    speak(response) { playbackStarted.complete(Unit) }
                } finally {
                    if (playbackStarted.isCompleted) playbackState.join() else playbackState.cancel()
                }
                if (active && owner == ownership) transition(CallState.Listening)
            } catch (failure: CancellationException) {
                invalidatePreparation()
                if (owner == ownership && active) {
                    active = false
                    transition(CallState.Idle)
                }
                throw failure
            } catch (failure: Throwable) {
                invalidatePreparation()
                if (owner == ownership) {
                    active = false
                    runCatching { stopListening() }
                    transition(CallState.Unavailable(failure.message ?: "Local conversation failed"))
                }
            } finally {
                if (!active && owner == ownership) runCatching { stopListening() }
                if (turn === ownedTurn) turn = null
            }
        }
    }

    fun fail(reason: String) {
        if (!active) return
        active = false
        ownership++
        turn?.cancel()
        turn = null
        val retired = ownership
        scope.launch { cleanupRevokedTurn(retired) }
        transition(CallState.Unavailable(reason))
    }

    override fun close() {
        if (closed) return
        closed = true
        ownership++
        active = false
        turn?.cancel()
        turn = null
    }

    private suspend fun ensurePrepared() = preparationMutex.withLock {
        if (prepared) return@withLock
        val revision = preparationRevision
        prepare()
        if (closed || revision != preparationRevision) throw CancellationException("Preparation ownership retired")
        prepared = true
    }

    private fun transition(next: CallState) {
        if (closed) return
        if (state == next) return
        state = next
        onState(next)
    }
}
