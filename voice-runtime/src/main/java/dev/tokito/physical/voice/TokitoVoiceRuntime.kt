package dev.tokito.physical.voice

import android.content.Context
import dev.tokito.physical.voice.internal.AdaptiveBargeInDetector
import dev.tokito.physical.voice.internal.AndroidAudioFocusGate
import dev.tokito.physical.voice.internal.AndroidAudioInput
import dev.tokito.physical.voice.internal.AudioFocusGate
import dev.tokito.physical.voice.internal.AudioInput
import dev.tokito.physical.voice.internal.MoonshineBackendFactory
import dev.tokito.physical.voice.internal.PreparedBackend
import dev.tokito.physical.voice.internal.StreamingRecognizer
import dev.tokito.physical.voice.internal.VoiceBackendFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TokitoVoiceRuntime internal constructor(
    private val config: VoiceRuntimeConfig,
    private val backendFactory: VoiceBackendFactory,
    private val audioInput: AudioInput,
    private val focusGate: AudioFocusGate,
) : VoiceRuntime {
    constructor(
        context: Context,
        config: VoiceRuntimeConfig = VoiceRuntimeConfig(),
    ) : this(
        config = config,
        backendFactory = MoonshineBackendFactory(context, config),
        audioInput = AndroidAudioInput(context),
        focusGate = AndroidAudioFocusGate(context),
    )

    private enum class Mode { UNINITIALIZED, READY, LISTENING, SPEAKING, CLOSED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val speechMutex = Mutex()
    private val _state = MutableStateFlow<VoiceRuntimeState>(VoiceRuntimeState.Uninitialized)
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    private val speechInterrupted = AtomicBoolean(false)

    @Volatile private var mode = Mode.UNINITIALIZED
    @Volatile private var backend: PreparedBackend? = null
    @Volatile private var bargeInDetector: AdaptiveBargeInDetector? = null

    override val state: StateFlow<VoiceRuntimeState> = _state.asStateFlow()
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    override suspend fun initialize() = lifecycleMutex.withLock {
        if (mode == Mode.READY && backend != null) {
            _state.value = VoiceRuntimeState.Ready(capabilities())
            return@withLock
        }
        check(mode == Mode.UNINITIALIZED) { "voice runtime is already initialized" }
        try {
            val prepared = backendFactory.prepare()
            prepared.recognizer.setListener(recognizerListener)
            backend = prepared
            mode = Mode.READY
            _state.value = VoiceRuntimeState.Ready(capabilities())
        } catch (error: Throwable) {
            val code = if (error.message.orEmpty().contains("digest", ignoreCase = true) ||
                error.message.orEmpty().contains("integrity", ignoreCase = true)
            ) {
                VoiceErrorCode.MODEL_INTEGRITY_FAILURE
            } else {
                VoiceErrorCode.MODEL_UNAVAILABLE
            }
            fail(VoiceFailure(code, error.message ?: "voice initialization failed", true, error))
            throw error
        }
    }

    override suspend fun startListening() = lifecycleMutex.withLock {
        check(mode == Mode.READY) { "voice runtime must be ready before listening" }
        if (!focusGate.acquireCapture()) {
            val failure = VoiceFailure(VoiceErrorCode.AUDIO_FOCUS_DENIED, "audio focus denied", true)
            fail(failure)
            throw IllegalStateException(failure.message)
        }
        try {
            requireBackend().recognizer.start()
            mode = Mode.LISTENING
            audioInput.start(::handleAudio, ::handleCaptureFailure)
            _state.value = VoiceRuntimeState.Listening
        } catch (error: Throwable) {
            runCatching { requireBackend().recognizer.stop() }
            focusGate.abandon()
            mode = Mode.READY
            val code = if (error.message.orEmpty().contains("permission", true)) {
                VoiceErrorCode.PERMISSION_DENIED
            } else {
                VoiceErrorCode.CAPTURE_FAILURE
            }
            fail(VoiceFailure(code, error.message ?: "audio capture failed", true, error))
            throw error
        }
    }

    override suspend fun stopListening() = lifecycleMutex.withLock {
        when (mode) {
            Mode.LISTENING -> {
                mode = Mode.READY
                audioInput.stop()
                requireBackend().recognizer.stop()
                focusGate.abandon()
                _state.value = VoiceRuntimeState.Ready(capabilities())
            }
            Mode.READY -> Unit
            Mode.SPEAKING -> interruptSpeechLocked()
            else -> throw IllegalStateException("voice runtime is not active")
        }
    }

    override suspend fun speak(text: String, policy: SpeechQueuePolicy): String {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "speech text cannot be blank" }
        require(normalized.length <= MAX_SPEECH_CHARACTERS) { "speech text exceeds the bounded limit" }
        if (policy == SpeechQueuePolicy.REJECT_IF_BUSY && speechMutex.isLocked) {
            throw IllegalStateException("speech is already in progress")
        }
        if (policy == SpeechQueuePolicy.REPLACE && speechMutex.isLocked) {
            interruptSpeech()
        }
        return speechMutex.withLock { speakLocked(normalized) }
    }

    override suspend fun interruptSpeech() = lifecycleMutex.withLock {
        interruptSpeechLocked()
    }

    override fun close() {
        if (mode == Mode.CLOSED) return
        mode = Mode.CLOSED
        speechInterrupted.set(true)
        runCatching { backend?.synthesizer?.stop() }
        runCatching { audioInput.close() }
        runCatching { backend?.recognizer?.close() }
        runCatching { backend?.synthesizer?.close() }
        backend = null
        focusGate.abandon()
        scope.cancel()
        _state.value = VoiceRuntimeState.Closed
    }

    private suspend fun speakLocked(text: String): String {
        val utteranceId = UUID.randomUUID().toString()
        var resumeListening = false
        lifecycleMutex.withLock {
            check(mode == Mode.READY || mode == Mode.LISTENING) { "voice runtime is not ready to speak" }
            resumeListening = mode == Mode.LISTENING
            if (resumeListening) requireBackend().recognizer.stop()
            if (!focusGate.acquireSpeech { scope.launch { interruptSpeech() } }) {
                if (resumeListening) requireBackend().recognizer.start()
                throw IllegalStateException("audio focus denied")
            }
            speechInterrupted.set(false)
            mode = Mode.SPEAKING
            bargeInDetector = if (audioInput.supportsEchoCancellation) {
                AdaptiveBargeInDetector(audioInput.sampleRate, config.bargeIn) { rms, threshold ->
                    _events.tryEmit(VoiceEvent.BargeInDetected(rms, threshold))
                    scope.launch { interruptSpeech() }
                }
            } else {
                null
            }
            _state.value = VoiceRuntimeState.Speaking(utteranceId)
            _events.tryEmit(VoiceEvent.SpeechStarted(utteranceId, text))
        }

        var synthesisFailure: VoiceFailure? = null
        try {
            withContext(Dispatchers.IO) { requireBackend().synthesizer.speak(text) }
        } catch (error: Throwable) {
            synthesisFailure = VoiceFailure(
                VoiceErrorCode.SYNTHESIS_FAILURE,
                error.message ?: "speech synthesis failed",
                true,
                error,
            )
            throw error
        } finally {
            lifecycleMutex.withLock {
                bargeInDetector = null
                focusGate.abandon()
                val interrupted = speechInterrupted.getAndSet(false)
                _events.tryEmit(VoiceEvent.SpeechFinished(utteranceId, interrupted))
                if (mode != Mode.CLOSED && synthesisFailure == null && resumeListening) {
                    if (focusGate.acquireCapture()) {
                        requireBackend().recognizer.start()
                        mode = Mode.LISTENING
                        _state.value = VoiceRuntimeState.Listening
                    } else {
                        mode = Mode.READY
                        _state.value = VoiceRuntimeState.Ready(capabilities())
                        fail(VoiceFailure(VoiceErrorCode.AUDIO_FOCUS_DENIED, "capture focus was not restored", true))
                    }
                } else if (mode != Mode.CLOSED && synthesisFailure == null) {
                    mode = Mode.READY
                    _state.value = VoiceRuntimeState.Ready(capabilities())
                } else if (mode != Mode.CLOSED) {
                    mode = Mode.READY
                    fail(checkNotNull(synthesisFailure))
                }
            }
        }
        return utteranceId
    }

    private fun interruptSpeechLocked() {
        if (mode != Mode.SPEAKING) return
        speechInterrupted.set(true)
        requireBackend().synthesizer.stop()
    }

    private fun handleAudio(samples: FloatArray) {
        when (mode) {
            Mode.LISTENING -> runCatching {
                requireBackend().recognizer.accept(samples, audioInput.sampleRate)
            }.onFailure(::handleRecognitionFailure)
            Mode.SPEAKING -> bargeInDetector?.accept(samples)
            else -> Unit
        }
    }

    private fun handleCaptureFailure(error: Throwable) {
        scope.launch {
            lifecycleMutex.withLock {
                if (mode == Mode.CLOSED) return@withLock
                audioInput.stop()
                focusGate.abandon()
                mode = Mode.READY
                fail(VoiceFailure(VoiceErrorCode.CAPTURE_FAILURE, error.message ?: "capture failed", true, error))
            }
        }
    }

    private fun handleRecognitionFailure(error: Throwable) {
        scope.launch {
            lifecycleMutex.withLock {
                if (mode == Mode.CLOSED) return@withLock
                if (mode == Mode.LISTENING) {
                    audioInput.stop()
                    runCatching { requireBackend().recognizer.stop() }
                    focusGate.abandon()
                    mode = Mode.READY
                }
                fail(VoiceFailure(VoiceErrorCode.RECOGNITION_FAILURE, error.message ?: "recognition failed", true, error))
            }
        }
    }

    private val recognizerListener = object : StreamingRecognizer.Listener {
        override fun onTranscript(segment: TranscriptSegment) {
            if (segment.text.isNotBlank()) _events.tryEmit(VoiceEvent.Transcript(segment))
        }

        override fun onFailure(error: Throwable) = handleRecognitionFailure(error)
    }

    private fun capabilities(): Set<VoiceCapability> = buildSet {
        add(VoiceCapability.STREAMING_STT)
        add(VoiceCapability.LOCAL_TTS)
        add(VoiceCapability.TOUCH_BARGE_IN)
        if (audioInput.supportsEchoCancellation) add(VoiceCapability.VOICE_BARGE_IN)
    }

    private fun requireBackend(): PreparedBackend = checkNotNull(backend) { "voice backend is unavailable" }

    private fun fail(failure: VoiceFailure) {
        _events.tryEmit(VoiceEvent.Error(failure))
        _state.value = VoiceRuntimeState.Failed(failure)
    }

    companion object {
        private const val MAX_SPEECH_CHARACTERS = 2_000
    }
}
