package dev.tokito.physical.voice

import android.content.Context
import dev.tokito.physical.voice.internal.AdaptiveBargeInDetector
import dev.tokito.physical.voice.internal.ActivationAudioGate
import dev.tokito.physical.voice.internal.AndroidAudioFocusGate
import dev.tokito.physical.voice.internal.AndroidAudioInput
import dev.tokito.physical.voice.internal.AudioFocusGate
import dev.tokito.physical.voice.internal.AudioInput
import dev.tokito.physical.voice.internal.SherpaBackendFactory
import dev.tokito.physical.voice.internal.PreparedBackend
import dev.tokito.physical.voice.internal.StreamingRecognizer
import dev.tokito.physical.voice.internal.VoiceBackendFactory
import dev.tokito.physical.voice.internal.WakeMatch
import dev.tokito.physical.voice.internal.WakePhraseMatcher
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
        backendFactory = SherpaBackendFactory(context, config),
        audioInput = AndroidAudioInput(context),
        focusGate = AndroidAudioFocusGate(context),
    )

    private enum class Mode { UNINITIALIZED, READY, AWAITING_WAKE, ACTIVATING, LISTENING, SPEAKING, CLOSED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val speechMutex = Mutex()
    private val _state = MutableStateFlow<VoiceRuntimeState>(VoiceRuntimeState.Uninitialized)
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    private val speechInterrupted = AtomicBoolean(false)

    @Volatile private var mode = Mode.UNINITIALIZED
    @Volatile private var backend: PreparedBackend? = null
    @Volatile private var bargeInDetector: AdaptiveBargeInDetector? = null
    @Volatile private var activationGate: ActivationAudioGate? = null
    @Volatile private var lastVadConfidence = 0f
    @Volatile private var lastVadEventAt = 0L
    private val wakeMatcher = WakePhraseMatcher(config.activation)

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

    override suspend fun startActivation() = lifecycleMutex.withLock {
        check(mode == Mode.READY) { "voice runtime must be ready before activation" }
        if (!focusGate.acquireCapture()) {
            val failure = VoiceFailure(VoiceErrorCode.AUDIO_FOCUS_DENIED, "audio focus denied", true)
            fail(failure)
            throw IllegalStateException(failure.message)
        }
        try {
            activationGate = ActivationAudioGate(audioInput.sampleRate, config.activation.vad)
            lastVadConfidence = 0f
            requireBackend().recognizer.start()
            mode = Mode.AWAITING_WAKE
            audioInput.start(::handleAudio, ::handleCaptureFailure)
            _state.value = VoiceRuntimeState.AwaitingWake
        } catch (error: Throwable) {
            activationGate = null
            runCatching { requireBackend().recognizer.stop() }
            focusGate.abandon()
            mode = Mode.READY
            fail(VoiceFailure(VoiceErrorCode.CAPTURE_FAILURE, error.message ?: "activation capture failed", true, error))
            throw error
        }
    }

    override suspend fun stopListening() = lifecycleMutex.withLock {
        when (mode) {
            Mode.LISTENING, Mode.AWAITING_WAKE, Mode.ACTIVATING -> {
                mode = Mode.READY
                activationGate = null
                withContext(Dispatchers.IO) {
                    audioInput.stop()
                    requireBackend().recognizer.stop()
                }
                focusGate.abandon()
                _state.value = VoiceRuntimeState.Ready(capabilities())
            }
            Mode.READY -> Unit
            Mode.SPEAKING -> interruptSpeechLocked()
            else -> throw IllegalStateException("voice runtime is not active")
        }
    }

    override suspend fun speak(
        text: String,
        policy: SpeechQueuePolicy,
        onPlaybackStarted: () -> Unit,
    ): String {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "speech text cannot be blank" }
        require(normalized.length <= MAX_SPEECH_CHARACTERS) { "speech text exceeds the bounded limit" }
        if (policy == SpeechQueuePolicy.REJECT_IF_BUSY && speechMutex.isLocked) {
            throw IllegalStateException("speech is already in progress")
        }
        if (policy == SpeechQueuePolicy.REPLACE && speechMutex.isLocked) {
            interruptSpeech()
        }
        return speechMutex.withLock { speakLocked(normalized, onPlaybackStarted) }
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

    private suspend fun speakLocked(text: String, onPlaybackStarted: () -> Unit): String {
        val utteranceId = UUID.randomUUID().toString()
        var resumeMode: Mode? = null
        lifecycleMutex.withLock {
            check(mode == Mode.READY || mode == Mode.LISTENING || mode == Mode.AWAITING_WAKE) { "voice runtime is not ready to speak" }
            resumeMode = mode.takeIf { it == Mode.LISTENING || it == Mode.AWAITING_WAKE }
            if (resumeMode != null) withContext(Dispatchers.Default) { requireBackend().recognizer.stop() }
            if (!focusGate.acquireSpeech { scope.launch { interruptSpeech() } }) {
                if (resumeMode != null) requireBackend().recognizer.start()
                throw IllegalStateException("audio focus denied")
            }
            speechInterrupted.set(false)
            mode = Mode.SPEAKING
            bargeInDetector = if (audioInput.supportsEchoCancellation) {
                AdaptiveBargeInDetector(audioInput.sampleRate, config.bargeIn) { rms, threshold ->
                    _events.tryEmit(VoiceEvent.BargeInDetected(rms, threshold))
                    interruptSpeechLocked()
                }
            } else {
                null
            }
            _state.value = VoiceRuntimeState.Synthesizing(utteranceId)
        }

        var synthesisFailure: VoiceFailure? = null
        try {
            withContext(Dispatchers.IO) {
                requireBackend().synthesizer.speak(text) {
                    _state.value = VoiceRuntimeState.Speaking(utteranceId)
                    _events.tryEmit(VoiceEvent.SpeechStarted(utteranceId, text))
                    onPlaybackStarted()
                }
            }
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
                if (mode != Mode.CLOSED && synthesisFailure == null && resumeMode != null) {
                    if (focusGate.acquireCapture()) {
                        requireBackend().recognizer.start()
                        mode = checkNotNull(resumeMode)
                        _state.value = if (mode == Mode.AWAITING_WAKE) VoiceRuntimeState.AwaitingWake else VoiceRuntimeState.Listening
                    } else {
                        mode = Mode.READY
                        _state.value = VoiceRuntimeState.Ready(capabilities())
                        fail(VoiceFailure(VoiceErrorCode.AUDIO_FOCUS_DENIED, "capture focus was not restored", true))
                    }
                } else if (mode != Mode.CLOSED && synthesisFailure == null) {
                    mode = Mode.READY
                    _state.value = VoiceRuntimeState.Ready(capabilities())
                } else if (mode != Mode.CLOSED) {
                    if (resumeMode != null) audioInput.stop()
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
            Mode.AWAITING_WAKE -> runCatching {
                val frame = requireNotNull(activationGate).accept(samples) {
                    requireBackend().recognizer.accept(it, audioInput.sampleRate)
                }
                lastVadConfidence = frame.confidence
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastVadEventAt >= config.activation.vadEventIntervalMillis) {
                    lastVadEventAt = now
                    _events.tryEmit(VoiceEvent.VoiceActivity(frame.confidence, frame.rms, frame.noiseFloor, frame.isSpeech))
                }
            }.onFailure(::handleRecognitionFailure)
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
                activationGate = null
                runCatching { requireBackend().recognizer.stop() }
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
                if (mode == Mode.LISTENING || mode == Mode.AWAITING_WAKE || mode == Mode.ACTIVATING) {
                    audioInput.stop()
                    activationGate = null
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
            if (segment.text.isBlank()) return
            if (mode == Mode.AWAITING_WAKE) {
                wakeMatcher.match(segment.text, lastVadConfidence)?.let(::activateFromWake)
            } else if (mode == Mode.LISTENING) {
                _events.tryEmit(VoiceEvent.Transcript(segment))
            }
        }

        override fun onFailure(error: Throwable) = handleRecognitionFailure(error)
    }

    private fun activateFromWake(match: WakeMatch) {
        if (mode != Mode.AWAITING_WAKE) return
        mode = Mode.ACTIVATING
        _events.tryEmit(VoiceEvent.WakeWordDetected(match.phrase, match.confidence))
        scope.launch {
            lifecycleMutex.withLock {
                if (mode != Mode.ACTIVATING) return@withLock
                activationGate = null
                requireBackend().recognizer.stop()
                requireBackend().recognizer.start()
                mode = Mode.LISTENING
                _state.value = VoiceRuntimeState.Listening
            }
        }
    }

    private fun capabilities(): Set<VoiceCapability> = buildSet {
        add(VoiceCapability.LOCAL_WAKE_WORD)
        add(VoiceCapability.LOCAL_VAD)
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
