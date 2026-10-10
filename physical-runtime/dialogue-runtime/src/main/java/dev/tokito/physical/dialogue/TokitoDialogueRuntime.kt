package dev.tokito.physical.dialogue

import android.content.Context
import android.os.Build
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TokitoDialogueRuntime(
    context: Context,
    private val limits: DialogueLimits = DialogueLimits(),
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val protocol = DialogueProtocol(limits)
    private val operation = Mutex()
    private val initialization = InitializationWorker<LlmWrapper>()
    private val responses = ResponseWorker(retireEmpty = initialization::closeIfEmpty) {
        // Runs under native admission only after vendor work and cleanup unwind.
        runCatching { initialization.takeCurrent()?.close() }
        initialization.close()
        activeRuntime = null
        clearSession()
    }
    private val lifecycle = DialogueLifecycle { responses.close() }
    private var activeRuntime: DialogueRuntime? = null
    private val sessionMessages = ArrayList<ChatMessage>()
    private var sessionChars = 0
    private var sessionTurns = 0
    private var sessionSystemPrompt: String? = null
    private var sessionNeedsReset = false
    private val recentProposalIds = ArrayDeque<String>()
    private val _state = MutableStateFlow<DialogueRuntimeState>(DialogueRuntimeState.Uninitialized)
    val state: StateFlow<DialogueRuntimeState> = _state.asStateFlow()

    suspend fun initialize(model: DialogueModel) = withTimeout(INITIALIZATION_DEADLINE_MS) {
        operation.withLock { initializeOwned(model) }
    }

    private suspend fun initializeOwned(model: DialogueModel) {
        ensureOpen()
        if (responses.isQuarantined()) {
            throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Native dialogue runtime is quarantined")
        }
        ensureSupportedDevice()
        require(model.artifacts.isNotEmpty()) { "Expected a non-empty model inventory" }
        if (!model.path.exists()) throw DialogueFailure(DialogueFailureCode.MODEL_MISSING, "Model bundle is absent")
        if (!lifecycle.publish { _state.value = DialogueRuntimeState.Loading }) ensureOpen()
        val started = android.os.SystemClock.elapsedRealtime()
        fun phase(name: String) { Log.i(TAG, "Initialization phase=$name elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}") }
        try {
            initialization.load(INITIALIZATION_DEADLINE_MS) {
                phase("inventory")
                val inventoryMatches = model.artifacts.all { artifact ->
                    artifact.sha256.matches(Regex("[a-f0-9]{64}")) &&
                        artifact.file.isFile &&
                        artifact.file.length() == artifact.sizeBytes &&
                        sha256(artifact.file) == artifact.sha256
                }
                if (!inventoryMatches) {
                    throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Model bundle does not match its pinned inventory")
                }
                phase("jni_abi")
                requireGenieXJniAbi()
                phase("sdk_init")
                initializeGenieX()
                phase("model_create")
                val modelConfig = when (model.runtime) {
                    DialogueRuntime.QAIRT -> ModelConfig(
                        nCtx = 0,
                        nGpuLayers = 0,
                        max_tokens = limits.maxPredictTokens,
                        enable_thinking = false,
                    )
                    DialogueRuntime.LLAMA_CPP -> ModelConfig(
                        nCtx = BuildConfig.DIALOGUE_CONTEXT_SIZE,
                        max_tokens = limits.maxPredictTokens,
                        enable_thinking = false,
                    )
                }
                LlmWrapper.builder()
                    .llmCreateInput(
                        LlmCreateInput(
                            model_name = model.id,
                            model_path = model.path.canonicalPath,
                            config = modelConfig,
                            runtime_id = model.runtime.wireValue,
                            compute_unit = BuildConfig.DIALOGUE_COMPUTE_UNIT,
                        ),
                    )
                    .build()
                    .getOrThrow()
            }
            coroutineContext.ensureActive()
            if (!lifecycle.publish {
                activeRuntime = model.runtime
                clearSession()
                _state.value = DialogueRuntimeState.Ready(model.id)
            }) ensureOpen()
            phase("ready")
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            initialization.discardCurrent()
            initializationFailure(DialogueFailure(DialogueFailureCode.MODEL_LOAD_TIMEOUT, "Local model initialization exceeded its deadline"))
            phase("timeout")
            throw failure
        } catch (failure: CancellationException) {
            initialization.discardCurrent()
            initializationFailure(DialogueFailure(DialogueFailureCode.MODEL_LOAD_CANCELLED, "Local model initialization was cancelled"))
            phase("cancelled")
            throw failure
        } catch (failure: DialogueFailure) {
            initializationFailure(failure)
            throw failure
        } catch (failure: Throwable) {
            val wrapped = DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Local dialogue model could not be loaded", failure)
            initializationFailure(wrapped)
            phase("failed")
            throw wrapped
        }
    }

    suspend fun respond(request: DialogueRequest): DialogueOutcome = operation.withLock {
        ensureOpen()
        protocol.validateRequest(request)
        lifecycle.responding {
            val ready = _state.value as? DialogueRuntimeState.Ready
                ?: throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Dialogue model is not ready")
            val started = android.os.SystemClock.elapsedRealtime()
            if (!lifecycle.publish { _state.value = DialogueRuntimeState.Generating }) ensureOpen()
            try {
                val outcome = responses.execute(request.deadlineMs) { respondOwned(request) }
                lifecycle.publish { _state.value = ready }
                outcome
            } catch (failure: CancellationException) {
                Log.i(TAG, "Generation receipt=cancelled elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                lifecycle.publish {
                    _state.value = DialogueRuntimeState.Failed(DialogueFailure(
                        DialogueFailureCode.GENERATION_CANCELLED, "Local generation was cancelled", failure))
                }
                throw failure
            } catch (failure: DialogueFailure) {
                if (failure.code == DialogueFailureCode.GENERATION_TIMEOUT) {
                    Log.i(TAG, "Generation receipt=timeout elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                }
                lifecycle.publish {
                    _state.value = if (responses.isQuarantined()) DialogueRuntimeState.Failed(failure) else ready
                }
                throw failure
            } catch (failure: Throwable) {
                val wrapped = DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Local generation failed", failure)
                lifecycle.publish { _state.value = DialogueRuntimeState.Failed(wrapped) }
                throw wrapped
            }
        }
    }

    private suspend fun respondOwned(request: DialogueRequest): DialogueOutcome {
        protocol.ungroundedIdentityClarification(request)?.let { return it }
        val prompt = protocol.requestPrompt(request)
        val toolMode = request.capabilities.isNotEmpty()
        requireDialogueMode(activeRuntime, toolMode)
        val systemPrompt = if (toolMode) protocol.systemPrompt() else protocol.conversationalSystemPrompt()
        val activeEngine = initialization.current()
            ?: throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Dialogue model is not ready")
        val started = android.os.SystemClock.elapsedRealtime()
        fun phase(name: String) { Log.i(TAG, "Generation phase=$name elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}") }
        return try {
            val raw = run {
                phase("prepare")
                prepareSession(activeEngine, systemPrompt, prompt)
                phase("template")
                val templated = activeEngine.applyChatTemplate(
                    sessionMessages.toTypedArray(),
                    null,
                    false,
                ).getOrThrow()
                Log.i(TAG, "Local template: chatmlSystem=${templated.formattedText.contains("<|im_start|>system")}, " +
                    "chatmlUser=${templated.formattedText.contains("<|im_start|>user")}, " +
                    "chatmlAssistant=${templated.formattedText.contains("<|im_start|>assistant")}, " +
                    "nonThinkingPrefix=${templated.formattedText.endsWith("<think>\n\n</think>\n\n")}")
                val generation = GenerationConfig(
                    maxTokens = limits.maxPredictTokens,
                    samplerConfig = if (toolMode && activeRuntime == DialogueRuntime.LLAMA_CPP) {
                        SamplerConfig(grammarString = TOKITO_DIALOGUE_GRAMMAR)
                    } else {
                        conversationalSampler(activeRuntime)
                    },
                )
                phase("native_generate")
                buildString {
                    activeEngine.generateStreamFlow(templated.formattedText, generation).collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> {
                                append(result.text)
                                if (length > limits.maxOutputChars) {
                                    throw DialogueFailure(DialogueFailureCode.OUTPUT_LIMIT, "Model output exceeds limit")
                                }
                            }
                            is LlmStreamResult.Completed -> Unit
                            is LlmStreamResult.Error -> throw result.throwable
                        }
                    }
                }
            }
            phase("native_returned")
            sessionMessages.add(ChatMessage("assistant", raw))
            sessionChars += raw.length
            sessionTurns += 1
            val outcome = try {
                if (toolMode) protocol.parse(raw, request) else protocol.parseConversation(raw)
            } catch (failure: DialogueFailure) {
                if (failure.code == DialogueFailureCode.INVALID_MODEL_OUTPUT) {
                    Log.w(
                        TAG,
                        "Rejected local model envelope: length=${raw.length}, " +
                            "startsObject=${raw.trimStart().startsWith('{')}, " +
                            "endsObject=${raw.trimEnd().endsWith('}')}, " +
                            "containsThinkTag=${raw.contains("<think>") || raw.contains("</think>")}, " +
                            "objectStart=${raw.indexOf('{')}, objectEnd=${raw.lastIndexOf('}')}, " +
                            "containsFence=${raw.contains("```")}",
                    )
                }
                throw failure
            }
            outcome.also {
                if (outcome is DialogueOutcome.ToolProposal) {
                    if (outcome.id in recentProposalIds) {
                        throw DialogueFailure(DialogueFailureCode.POLICY_REJECTED, "Duplicate tool proposal id")
                    }
                    recentProposalIds.addLast(outcome.id)
                    if (recentProposalIds.size > 64) recentProposalIds.removeFirst()
                }
            }
        } catch (failure: CancellationException) {
            phase("cleanup")
            stopInterruptedStream(activeEngine)
            phase("cleanup_returned")
            sessionNeedsReset = true
            throw failure
        } catch (failure: DialogueFailure) {
            if (failure.code == DialogueFailureCode.GENERATION_TIMEOUT ||
                failure.code == DialogueFailureCode.OUTPUT_LIMIT) {
                phase("cleanup")
                stopInterruptedStream(activeEngine)
                phase("cleanup_returned")
            }
            sessionNeedsReset = true
            throw failure
        } catch (failure: Throwable) {
            sessionNeedsReset = true
            val wrapped = DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Local generation failed", failure)
            throw wrapped
        }
    }

    private suspend fun stopInterruptedStream(engine: LlmWrapper) =
        withContext(NonCancellable + Dispatchers.IO) {
            // A cancelled caller must still stop the native producer before its
            // lease releases the engine. Never run cleanup JNI on the UI thread.
            runCatching { engine.stopStream() }
            Unit
        }

    override fun close() {
        lifecycle.close { _state.value = DialogueRuntimeState.Closed }
        initialization.cancelPending()
    }

    private fun initializationFailure(failure: DialogueFailure) {
        lifecycle.publish { _state.value = DialogueRuntimeState.Failed(failure) }
    }

    private fun ensureOpen() {
        if (lifecycle.isClosed()) throw DialogueFailure(DialogueFailureCode.CLOSED, "Dialogue runtime is closed")
    }

    private fun ensureSupportedDevice() {
        if (!supportsDialogueRuntime(Build.SUPPORTED_ABIS)) {
            throw DialogueFailure(
                DialogueFailureCode.UNSUPPORTED_DEVICE,
                "Local dialogue requires a $SUPPORTED_ABI Android device",
            )
        }
    }

    private suspend fun prepareSession(
        activeEngine: LlmWrapper,
        systemPrompt: String,
        userPrompt: String,
    ) {
        val mustReset = sessionTurns > 0 ||
            sessionNeedsReset ||
            sessionSystemPrompt != null && sessionSystemPrompt != systemPrompt ||
            sessionTurns >= limits.maxHistoryTurns ||
            sessionChars + userPrompt.length > limits.maxPromptChars
        if (mustReset) {
            val resetCode = activeEngine.reset()
            if (resetCode != 0) {
                throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Local dialogue session could not be reset")
            }
            clearSession()
        }
        if (sessionMessages.isEmpty()) {
            sessionMessages.add(ChatMessage("system", systemPrompt))
            sessionSystemPrompt = systemPrompt
            sessionChars = systemPrompt.length
        }
        sessionMessages.add(ChatMessage("user", userPrompt))
        sessionChars += userPrompt.length
    }

    private fun clearSession() {
        sessionMessages.clear()
        sessionChars = 0
        sessionTurns = 0
        sessionSystemPrompt = null
        sessionNeedsReset = false
    }

    private suspend fun initializeGenieX() = suspendCancellableCoroutine { continuation ->
        GenieXSdk.getInstance().init(applicationContext, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(reason: String) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Hexagon runtime initialization failed: $reason"))
                }
            }
        })
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

    private companion object {
        const val INITIALIZATION_DEADLINE_MS = 120_000L
        const val TAG = "TokitoDialogue"
        const val SUPPORTED_ABI = "arm64-v8a"
    }
}

internal fun supportsDialogueRuntime(abis: Array<String>): Boolean =
    abis.any { it == "arm64-v8a" }

/** The admitted QAIRT profile is Qwen3 non-thinking; greedy decoding is not its recommended profile. */
internal fun conversationalSampler(runtime: DialogueRuntime?): SamplerConfig =
    if (runtime == DialogueRuntime.QAIRT) {
        SamplerConfig(temperature = 0.7f, topP = 0.8f, topK = 20, minP = 0f)
    } else {
        SamplerConfig(temperature = 0f, topP = 1f, topK = 1)
    }

internal fun requireDialogueMode(runtime: DialogueRuntime?, toolMode: Boolean) {
    if (toolMode && runtime != DialogueRuntime.LLAMA_CPP) {
        throw DialogueFailure(
            DialogueFailureCode.POLICY_REJECTED,
            "The active local runtime has not been validated for constrained tool proposals",
        )
    }
}
