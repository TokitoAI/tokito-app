package dev.tokito.physical.dialogue

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class TokitoDialogueRuntime(
    context: Context,
    private val limits: DialogueLimits = DialogueLimits(),
) : AutoCloseable {
    private val engine = AiChat.getInferenceEngine(context.applicationContext)
    private val protocol = DialogueProtocol(limits)
    private val operation = Mutex()
    private val closed = AtomicBoolean(false)
    private val recentProposalIds = ArrayDeque<String>()
    private val _state = MutableStateFlow<DialogueRuntimeState>(DialogueRuntimeState.Uninitialized)
    val state: StateFlow<DialogueRuntimeState> = _state.asStateFlow()

    suspend fun initialize(model: DialogueModel) = operation.withLock {
        ensureOpen()
        require(model.sha256.matches(Regex("[a-f0-9]{64}"))) { "Expected a lowercase SHA-256" }
        if (!model.file.isFile) throw DialogueFailure(DialogueFailureCode.MODEL_MISSING, "Model file is absent")
        _state.value = DialogueRuntimeState.Loading
        try {
            val digest = withContext(Dispatchers.IO) { sha256(model.file) }
            if (digest != model.sha256) throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Model SHA-256 does not match its pinned manifest")
            when (val nativeState = engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }) {
                is InferenceEngine.State.Error -> throw nativeState.exception
                else -> Unit
            }
            engine.loadModel(model.file.canonicalPath)
            engine.setSystemPrompt(protocol.systemPrompt())
            _state.value = DialogueRuntimeState.Ready(model.id)
        } catch (failure: DialogueFailure) {
            _state.value = DialogueRuntimeState.Failed(failure)
            throw failure
        } catch (failure: Throwable) {
            val wrapped = DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Local dialogue model could not be loaded", failure)
            _state.value = DialogueRuntimeState.Failed(wrapped)
            throw wrapped
        }
    }

    suspend fun respond(request: DialogueRequest): DialogueOutcome = operation.withLock {
        ensureOpen()
        val ready = _state.value as? DialogueRuntimeState.Ready
            ?: throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Dialogue model is not ready")
        val prompt = protocol.requestPrompt(request) + "\n/no_think"
        _state.value = DialogueRuntimeState.Generating
        try {
            val raw = withTimeout(request.deadlineMs) {
                buildString {
                    engine.sendUserPrompt(prompt, limits.maxPredictTokens).collect { token ->
                        append(token)
                        if (length > limits.maxOutputChars) {
                            throw DialogueFailure(DialogueFailureCode.OUTPUT_LIMIT, "Model output exceeds limit")
                        }
                    }
                }
            }
            protocol.parse(raw, request).also { outcome ->
                if (outcome is DialogueOutcome.ToolProposal) {
                    if (outcome.id in recentProposalIds) {
                        throw DialogueFailure(DialogueFailureCode.POLICY_REJECTED, "Duplicate tool proposal id")
                    }
                    recentProposalIds.addLast(outcome.id)
                    if (recentProposalIds.size > 64) recentProposalIds.removeFirst()
                }
                _state.value = ready
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            _state.value = ready
            throw DialogueFailure(DialogueFailureCode.GENERATION_TIMEOUT, "Local generation exceeded its deadline", failure)
        } catch (failure: CancellationException) {
            _state.value = ready
            throw DialogueFailure(DialogueFailureCode.GENERATION_CANCELLED, "Local generation was cancelled", failure)
        } catch (failure: DialogueFailure) {
            _state.value = ready
            throw failure
        } catch (failure: Throwable) {
            val wrapped = DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Local generation failed", failure)
            _state.value = DialogueRuntimeState.Failed(wrapped)
            throw wrapped
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // llama.cpp owns one process-wide backend. Unload this model while keeping the
        // backend reusable after an Activity recreation in the same Android process.
        if (engine.state.value.isModelLoaded) runCatching { engine.cleanUp() }
        _state.update { DialogueRuntimeState.Closed }
    }

    private fun ensureOpen() {
        if (closed.get()) throw DialogueFailure(DialogueFailureCode.CLOSED, "Dialogue runtime is closed")
    }

    private fun sha256(file: java.io.File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
