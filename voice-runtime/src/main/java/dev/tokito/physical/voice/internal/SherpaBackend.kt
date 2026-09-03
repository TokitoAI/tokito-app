package dev.tokito.physical.voice.internal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.tokito.physical.voice.TranscriptSegment
import dev.tokito.physical.voice.VoiceRuntimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SherpaBackendFactory(
    context: Context,
    private val config: VoiceRuntimeConfig,
) : VoiceBackendFactory {
    private val appContext = context.applicationContext
    private val modelRoot = File(appContext.filesDir, "voice-models/sherpa-onnx-$SHERPA_VERSION")
    private val inventory = ModelInventory(modelRoot, SHERPA_VERSION)

    override suspend fun prepare(): PreparedBackend = withContext(Dispatchers.IO) {
        val verification = inventory.verify()
        check(verification.valid) { "verified voice model bundle is unavailable: ${verification.reason}" }
        val bundle = SherpaBundleConfig.load(modelRoot)
        require(config.language in bundle.languages) { "voice bundle does not support ${config.language}" }
        val recognizer = SherpaStreamingRecognizer(bundle, config.partialUpdateIntervalSeconds)
        val synthesizer = SherpaSpeechSynthesizer(bundle, config.ttsSynthesisTimeoutMillis)
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
        const val SHERPA_VERSION = "1.13.6"
    }
}

internal data class SherpaBundleConfig(
    val languages: Set<String>,
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val sttTokens: File,
    val sttModelType: String,
    val ttsModel: File,
    val ttsTokens: File,
    val ttsDataDir: File,
    val ttsSpeakerId: Int,
    val ttsSpeed: Float,
) {
    companion object {
        private const val CONFIG_NAME = "voice-runtime.json"

        fun load(root: File): SherpaBundleConfig {
            val document = JSONObject(File(root, CONFIG_NAME).readText(Charsets.UTF_8))
            require(document.getInt("schema_version") == 1) { "unsupported voice runtime schema" }
            val languages = document.getJSONArray("languages")
            val admittedLanguages = buildSet {
                repeat(languages.length()) { add(languages.getString(it)) }
            }
            require(admittedLanguages.isNotEmpty() && admittedLanguages.none(String::isBlank)) {
                "voice runtime must declare at least one language"
            }
            val stt = document.getJSONObject("stt")
            require(stt.getString("engine") == "sherpa-onnx-online-transducer") {
                "unsupported streaming recognizer engine"
            }
            val modelType = stt.getString("model_type")
            require(modelType in setOf("zipformer", "zipformer2", "lstm")) {
                "unsupported streaming recognizer model type"
            }
            val tts = document.getJSONObject("tts")
            require(tts.getString("engine") == "sherpa-onnx-vits") { "unsupported speech synthesizer engine" }
            val speakerId = tts.getInt("speaker_id")
            require(speakerId >= 0) { "TTS speaker ID must be non-negative" }
            val speed = tts.getDouble("speed").toFloat()
            require(speed in 0.5f..2.0f) { "TTS speed is outside the supported range" }
            return SherpaBundleConfig(
                languages = admittedLanguages,
                encoder = resolveFile(root, stt.getString("encoder")),
                decoder = resolveFile(root, stt.getString("decoder")),
                joiner = resolveFile(root, stt.getString("joiner")),
                sttTokens = resolveFile(root, stt.getString("tokens")),
                sttModelType = modelType,
                ttsModel = resolveFile(root, tts.getString("model")),
                ttsTokens = resolveFile(root, tts.getString("tokens")),
                ttsDataDir = resolveDirectory(root, tts.getString("data_dir")),
                ttsSpeakerId = speakerId,
                ttsSpeed = speed,
            )
        }

        private fun resolveFile(root: File, relative: String): File = resolve(root, relative).also {
            require(it.isFile) { "voice model file is absent: $relative" }
        }

        private fun resolveDirectory(root: File, relative: String): File = resolve(root, relative).also {
            require(it.isDirectory) { "voice model directory is absent: $relative" }
        }

        private fun resolve(root: File, relative: String): File {
            require(relative.isNotBlank() && !File(relative).isAbsolute) { "voice model path must be relative" }
            require(relative.split('/', '\\').none { it.isBlank() || it == ".." }) { "unsafe voice model path" }
            val resolved = File(root, relative).canonicalFile
            require(resolved.path.startsWith(root.canonicalFile.path + File.separator)) {
                "voice model path escaped the bundle"
            }
            return resolved
        }
    }
}

private class SherpaStreamingRecognizer(
    private val bundle: SherpaBundleConfig,
    partialUpdateIntervalSeconds: Double,
) : StreamingRecognizer {
    private val partialIntervalMillis = (partialUpdateIntervalSeconds * 1_000).toLong().coerceAtLeast(0)
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var listener: StreamingRecognizer.Listener? = null
    private var lastText = ""
    private var lastPartialAt = 0L

    fun load() {
        check(recognizer == null) { "recognizer is already loaded" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        recognizer = OnlineRecognizer(
            assetManager = null,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = bundle.encoder.path,
                        decoder = bundle.decoder.path,
                        joiner = bundle.joiner.path,
                    ),
                    tokens = bundle.sttTokens.path,
                    numThreads = threads,
                    provider = "cpu",
                    modelType = bundle.sttModelType,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 1.2f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            ),
        )
    }

    @Synchronized
    override fun start() {
        check(stream == null) { "recognizer is already listening" }
        stream = requireRecognizer().createStream()
        lastText = ""
        lastPartialAt = 0L
    }

    @Synchronized
    override fun accept(samples: FloatArray, sampleRate: Int) {
        require(sampleRate == SAMPLE_RATE) { "sherpa recognizer requires $SAMPLE_RATE Hz audio" }
        val active = checkNotNull(stream) { "recognizer is not listening" }
        val engine = requireRecognizer()
        active.acceptWaveform(samples, sampleRate)
        while (engine.isReady(active)) engine.decode(active)
        dispatchResult(engine, active, endpoint = engine.isEndpoint(active))
    }

    @Synchronized
    override fun stop() {
        val active = stream ?: return
        val engine = requireRecognizer()
        active.inputFinished()
        while (engine.isReady(active)) engine.decode(active)
        dispatchResult(engine, active, endpoint = true)
        active.release()
        stream = null
        lastText = ""
    }

    override fun setListener(listener: StreamingRecognizer.Listener?) {
        this.listener = listener
    }

    @Synchronized
    override fun close() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
        listener = null
    }

    private fun dispatchResult(engine: OnlineRecognizer, active: OnlineStream, endpoint: Boolean) {
        val text = engine.getResult(active).text.trim()
        val now = SystemClock.elapsedRealtime()
        if (endpoint) {
            if (text.isNotBlank()) emit(text, true, now)
            engine.reset(active)
            lastText = ""
            lastPartialAt = now
        } else if (text.isNotBlank() && text != lastText && now - lastPartialAt >= partialIntervalMillis) {
            emit(text, false, now)
            lastText = text
            lastPartialAt = now
        }
    }

    private fun emit(text: String, final: Boolean, capturedAt: Long) {
        listener?.onTranscript(TranscriptSegment(text, final, capturedAt))
    }

    private fun requireRecognizer(): OnlineRecognizer = checkNotNull(recognizer) { "recognizer is not loaded" }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}

private class SherpaSpeechSynthesizer(
    private val bundle: SherpaBundleConfig,
    private val synthesisTimeoutMillis: Long,
) : SpeechSynthesizer {
    private val speaking = AtomicBoolean(false)
    private val continueSynthesis = AtomicBoolean(false)
    private val track = AtomicReference<AudioTrack?>()
    private var engine: OfflineTts? = null

    override val isSpeaking: Boolean
        get() = speaking.get()

    fun load() {
        check(engine == null) { "synthesizer is already loaded" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        engine = OfflineTts(
            assetManager = null,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = bundle.ttsModel.path,
                        tokens = bundle.ttsTokens.path,
                        dataDir = bundle.ttsDataDir.path,
                    ),
                    numThreads = threads,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            ),
        )
        require(engine?.numSpeakers()?.let { bundle.ttsSpeakerId < it } == true) {
            "configured TTS speaker is unavailable"
        }
        Log.i(TAG, "Local sherpa VITS initialized at ${engine?.sampleRate()} Hz")
    }

    override fun speak(text: String, onPlaybackStarted: () -> Unit) {
        check(speaking.compareAndSet(false, true)) { "synthesizer is already speaking" }
        continueSynthesis.set(true)
        val tts = requireEngine()
        val deadline = SystemClock.elapsedRealtime() + synthesisTimeoutMillis
        val startedAt = SystemClock.elapsedRealtime()
        var output: AudioTrack? = null
        try {
            Log.i(TAG, "Starting bounded local sherpa synthesis")
            // Sherpa's Android callback JNI can abort the VM before Kotlin can catch it.
            // Generate one bounded utterance, then retain cancellation during playback.
            val generated = tts.generate(text, bundle.ttsSpeakerId, bundle.ttsSpeed)
            check(generated.samples.isNotEmpty()) { "local TTS produced no audio" }
            check(continueSynthesis.get()) { "local TTS was cancelled" }
            check(SystemClock.elapsedRealtime() < deadline) {
                "local TTS exceeded its bounded deadline"
            }
            output = createTrack(tts.sampleRate())
            track.set(output)
            output.play()
            var offset = 0
            var playbackStarted = false
            while (offset < generated.samples.size && continueSynthesis.get()) {
                check(SystemClock.elapsedRealtime() < deadline) {
                    "local TTS exceeded its bounded deadline"
                }
                val sampleCount = minOf(PLAYBACK_CHUNK_SAMPLES, generated.samples.size - offset)
                val written = output.write(
                    generated.samples,
                    offset,
                    sampleCount,
                    AudioTrack.WRITE_BLOCKING,
                )
                check(written > 0) { "AudioTrack write failed: $written" }
                if (!playbackStarted) {
                    playbackStarted = true
                    onPlaybackStarted()
                }
                offset += written
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Local sherpa synthesis failed", failure)
            throw failure
        } finally {
            continueSynthesis.set(false)
            output?.let { active ->
                track.compareAndSet(active, null)
                runCatching { active.stop() }
                active.flush()
                active.release()
            }
            speaking.set(false)
            Log.i(TAG, "Local sherpa synthesis finished in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    override fun stop() {
        continueSynthesis.set(false)
        track.get()?.let { output ->
            runCatching { output.pause() }
            runCatching { output.flush() }
        }
    }

    override fun close() {
        stop()
        engine?.release()
        engine = null
        speaking.set(false)
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minimum > 0) { "device does not support float PCM speech playback" }
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minimum,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" } }
    }

    private fun requireEngine(): OfflineTts = checkNotNull(engine) { "synthesizer is not loaded" }

    companion object {
        private const val TAG = "TokitoLocalVoice"
        private const val PLAYBACK_CHUNK_SAMPLES = 4_096
    }
}
