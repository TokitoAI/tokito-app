package dev.tokito.physical.voice.internal

import dev.tokito.physical.voice.TranscriptSegment
import java.io.Closeable

internal interface StreamingRecognizer : Closeable {
    fun start()
    fun accept(samples: FloatArray, sampleRate: Int)
    fun stop()
    fun setListener(listener: Listener?)

    interface Listener {
        fun onTranscript(segment: TranscriptSegment)
        fun onFailure(error: Throwable)
    }
}

internal interface SpeechSynthesizer : Closeable {
    val isSpeaking: Boolean
    fun speak(text: String)
    fun stop()
}

internal interface AudioInput : Closeable {
    val sampleRate: Int
    val supportsEchoCancellation: Boolean
    fun start(consumer: (FloatArray) -> Unit, onFailure: (Throwable) -> Unit)
    fun stop()
}

internal interface AudioFocusGate {
    fun acquireCapture(): Boolean
    fun acquireSpeech(onLoss: () -> Unit): Boolean
    fun abandon()
}

internal interface VoiceBackendFactory {
    suspend fun prepare(
        allowDownload: Boolean,
        onProgress: (Float, String) -> Unit,
    ): PreparedBackend
}

internal data class PreparedBackend(
    val recognizer: StreamingRecognizer,
    val synthesizer: SpeechSynthesizer,
    val manifestDigest: String,
)
