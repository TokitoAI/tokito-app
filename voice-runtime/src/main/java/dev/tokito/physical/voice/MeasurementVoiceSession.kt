package dev.tokito.physical.voice

import android.os.SystemClock
import dev.tokito.physical.measurement.ConfirmationPolicy
import dev.tokito.physical.measurement.DeterministicMeasurementParser
import dev.tokito.physical.measurement.MeasurementConversation
import dev.tokito.physical.measurement.MeasurementDraft
import dev.tokito.physical.measurement.MeasurementParserConfig
import dev.tokito.physical.measurement.MeasurementTurn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Couples final local-STT transcripts to deterministic measurement parsing and
 * reads confirmation/correction prompts through local TTS. No raw audio or
 * transcript is sent off-device, and only an explicitly confirmed turn becomes
 * a [MeasurementTurn.Confirmed].
 */
class MeasurementVoiceSession(
    private val voice: VoiceRuntime,
    parserConfig: MeasurementParserConfig = MeasurementParserConfig(requireRelationship = true),
    confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALWAYS,
    clockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val conversation = MeasurementConversation(
        DeterministicMeasurementParser(parserConfig),
        confirmationPolicy,
        clockMillis,
    )
    private val mutex = Mutex()
    private val _turns = MutableSharedFlow<MeasurementTurn>(extraBufferCapacity = 8)
    @Volatile private var active = false

    val turns: SharedFlow<MeasurementTurn> = _turns.asSharedFlow()
    val pendingDraft: MeasurementDraft? get() = conversation.pendingDraft()
    val isActive: Boolean get() = active

    /** Arms one bounded request_measurement interaction. */
    suspend fun begin() = mutex.withLock {
        conversation.clear()
        active = true
    }

    /** Returns null for partial hypotheses; only final STT segments mutate state. */
    suspend fun accept(segment: TranscriptSegment): MeasurementTurn? {
        if (!segment.isFinal || segment.text.isBlank()) return null
        return mutex.withLock {
            if (!active) return@withLock null
            publishAndPrompt(conversation.submitFinalTranscript(segment.text))
        }
    }

    suspend fun confirmPrimary(): MeasurementTurn = mutex.withLock {
        publishAndPrompt(conversation.confirmPrimary())
    }

    suspend fun confirmAlternative(index: Int): MeasurementTurn = mutex.withLock {
        publishAndPrompt(conversation.confirmAlternative(index))
    }

    suspend fun reject(): MeasurementTurn = mutex.withLock {
        publishAndPrompt(conversation.reject())
    }

    suspend fun cancel() = mutex.withLock {
        conversation.clear()
        active = false
    }

    private suspend fun publishAndPrompt(turn: MeasurementTurn): MeasurementTurn {
        _turns.emit(turn)
        when (turn) {
            is MeasurementTurn.ConfirmationRequired -> voice.speak(turn.prompt, SpeechQueuePolicy.REPLACE)
            is MeasurementTurn.CorrectionRequired -> voice.speak(
                turn.incomplete.recoverablePrompt,
                SpeechQueuePolicy.REPLACE,
            )
            is MeasurementTurn.Confirmed,
            is MeasurementTurn.Rejected,
            -> active = false
        }
        return turn
    }
}
