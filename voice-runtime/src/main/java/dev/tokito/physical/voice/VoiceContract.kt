package dev.tokito.physical.voice

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

data class VoiceRuntimeConfig(
    val language: String = "en",
    val ttsSynthesisTimeoutMillis: Long = 60_000,
    val partialUpdateIntervalSeconds: Double = 0.25,
    val bargeIn: BargeInConfig = BargeInConfig(),
)

data class BargeInConfig(
    val calibrationMillis: Long = 350,
    val minimumRms: Float = 0.018f,
    val noiseMultiplier: Float = 3.2f,
    val requiredActiveFrames: Int = 4,
    val rollingWindowFrames: Int = 6,
)

enum class VoiceCapability {
    STREAMING_STT,
    LOCAL_TTS,
    TOUCH_BARGE_IN,
    VOICE_BARGE_IN,
}

sealed interface VoiceRuntimeState {
    data object Uninitialized : VoiceRuntimeState
    data class Ready(val capabilities: Set<VoiceCapability>) : VoiceRuntimeState
    data object Listening : VoiceRuntimeState
    data class Synthesizing(val utteranceId: String) : VoiceRuntimeState
    data class Speaking(val utteranceId: String) : VoiceRuntimeState
    data class Failed(val error: VoiceFailure) : VoiceRuntimeState
    data object Closed : VoiceRuntimeState
}

data class TranscriptSegment(
    val text: String,
    val isFinal: Boolean,
    val capturedAtMonotonicMillis: Long,
)

enum class VoiceErrorCode {
    PERMISSION_DENIED,
    MODEL_UNAVAILABLE,
    MODEL_INTEGRITY_FAILURE,
    AUDIO_FOCUS_DENIED,
    CAPTURE_FAILURE,
    RECOGNITION_FAILURE,
    SYNTHESIS_FAILURE,
    INVALID_STATE,
    CANCELLED,
    INTERNAL_INVARIANT,
}

data class VoiceFailure(
    val code: VoiceErrorCode,
    val message: String,
    val retryable: Boolean,
    val cause: Throwable? = null,
)

sealed interface VoiceEvent {
    data class Transcript(val segment: TranscriptSegment) : VoiceEvent
    data class SpeechStarted(val utteranceId: String, val text: String) : VoiceEvent
    data class SpeechFinished(val utteranceId: String, val interrupted: Boolean) : VoiceEvent
    data class BargeInDetected(val rms: Float, val threshold: Float) : VoiceEvent
    data class Error(val failure: VoiceFailure) : VoiceEvent
}

enum class SpeechQueuePolicy {
    QUEUE,
    REPLACE,
    REJECT_IF_BUSY,
}

interface VoiceRuntime : Closeable {
    val state: StateFlow<VoiceRuntimeState>
    val events: SharedFlow<VoiceEvent>

    suspend fun initialize()
    suspend fun startListening()
    suspend fun stopListening()
    suspend fun speak(
        text: String,
        policy: SpeechQueuePolicy = SpeechQueuePolicy.REPLACE,
        onPlaybackStarted: () -> Unit = {},
    ): String
    suspend fun interruptSpeech()
}
