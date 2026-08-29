package dev.tokito.physical.voice.internal

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.content.Context
import android.os.SystemClock
import dev.tokito.physical.voice.TranscriptSegment
import dev.tokito.physical.voice.VoiceRuntimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class MoonshineBackendFactory(
    context: Context,
    private val config: VoiceRuntimeConfig,
) : VoiceBackendFactory {
    private val appContext = context.applicationContext
    private val modelRoot = File(appContext.filesDir, "voice-models/moonshine-0.1.5")
    private val sttRoot = File(modelRoot, "stt-tiny-streaming-en")
    private val ttsRoot = File(modelRoot, "tts-${config.ttsVoice}")
    private val inventory = ModelInventory(modelRoot, MOONSHINE_VERSION)

    override suspend fun prepare(
    ): PreparedBackend = withContext(Dispatchers.IO) {
        val verification = inventory.verify()
        check(verification.valid) { "verified voice model bundle is unavailable: ${verification.reason}" }

        val recognizer = MoonshineRecognizer(sttRoot, config.partialUpdateIntervalSeconds)
        val synthesizer = MoonshineSynthesizer(appContext, ttsRoot, config)
        try {
            recognizer.load()
            synthesizer.load()
        } catch (error: Throwable) {
            recognizer.close()
            synthesizer.close()
            throw error
        }
        PreparedBackend(recognizer, synthesizer, verification.digest)
    }

    companion object {
        const val MOONSHINE_VERSION = "0.1.5"
    }
}

private class MoonshineRecognizer(
    private val modelRoot: File,
    private val updateInterval: Double,
) : StreamingRecognizer {
    private var transcriber: Transcriber? = null
    private var listener: StreamingRecognizer.Listener? = null

    fun load() {
        val engine = Transcriber(
            listOf(
                TranscriberOption("keyterms", "diode,resistor,capacitor,inductor,ground,voltage,ohm,ampere,pin"),
                TranscriberOption("keyterm_boost", "1.35"),
            ),
        )
        engine.setUpdateInterval(updateInterval)
        engine.setTranscribeFlags(JNI.MOONSHINE_FLAG_SPELLING_MODE)
        engine.addListener { event -> dispatch(event) }
        engine.loadFromFiles(modelRoot.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING)
        transcriber = engine
    }

    override fun start() = requireEngine().start()

    override fun accept(samples: FloatArray, sampleRate: Int) =
        requireEngine().addAudio(samples, sampleRate)

    override fun stop() = requireEngine().stop()

    override fun setListener(listener: StreamingRecognizer.Listener?) {
        this.listener = listener
    }

    override fun close() {
        transcriber?.close()
        transcriber = null
        listener = null
    }

    private fun dispatch(event: TranscriptEvent) {
        when (event) {
            is TranscriptEvent.LineTextChanged -> listener?.onTranscript(
                TranscriptSegment(
                    text = event.line.text.orEmpty(),
                    isFinal = false,
                    capturedAtMonotonicMillis = SystemClock.elapsedRealtime(),
                ),
            )
            is TranscriptEvent.LineCompleted -> listener?.onTranscript(
                TranscriptSegment(
                    text = event.line.text.orEmpty(),
                    isFinal = true,
                    capturedAtMonotonicMillis = SystemClock.elapsedRealtime(),
                ),
            )
            is TranscriptEvent.Error -> listener?.onFailure(event.cause)
        }
    }

    private fun requireEngine(): Transcriber = checkNotNull(transcriber) { "recognizer is not loaded" }
}

private class MoonshineSynthesizer(
    private val context: Context,
    private val modelRoot: File,
    private val config: VoiceRuntimeConfig,
) : SpeechSynthesizer {
    private var engine: TextToSpeech? = null
    private val speaking = AtomicBoolean(false)

    override val isSpeaking: Boolean
        get() = speaking.get()

    fun load() {
        engine = TextToSpeech(context)
            .language(config.ttsLanguage)
            .voice(config.ttsVoice)
            .modelsFrom(modelRoot)
            .also { it.load() }
    }

    override fun speak(text: String) {
        speaking.set(true)
        try {
            requireEngine().say(text)
        } finally {
            speaking.set(false)
        }
    }

    override fun stop() {
        engine?.stop()
        speaking.set(false)
    }

    override fun close() {
        engine?.close()
        engine = null
        speaking.set(false)
    }

    private fun requireEngine(): TextToSpeech = checkNotNull(engine) { "synthesizer is not loaded" }
}
