package dev.tokito.physical.dialogue

import java.io.File

enum class DialogueRuntime(val wireValue: String) {
    LLAMA_CPP("llama_cpp"),
    QAIRT("qairt"),
}

data class DialogueModelArtifact(
    val file: File,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class DialogueModel(
    val path: File,
    val artifacts: List<DialogueModelArtifact>,
    val id: String,
    val runtime: DialogueRuntime,
)

enum class DialogueRole { USER, ASSISTANT }

data class DialogueTurn(val role: DialogueRole, val text: String)

data class ObservationSummary(
    val kind: String,
    val summary: String,
    val confidence: Double,
    val observedAtElapsedMs: Long,
)

enum class ArgumentType { STRING, NUMBER, BOOLEAN }

data class ToolArgument(
    val name: String,
    val type: ArgumentType,
    val required: Boolean = true,
    val maxLength: Int = 256,
)

data class Capability(
    val name: String,
    val version: Int,
    val description: String,
    val arguments: List<ToolArgument>,
    val maxDeadlineMs: Long = 5_000,
)

data class DialogueRequest(
    val utterance: String,
    val history: List<DialogueTurn> = emptyList(),
    val observations: List<ObservationSummary> = emptyList(),
    val capabilities: List<Capability> = emptyList(),
    val designContext: String? = null,
    val deadlineMs: Long = 4_000,
    val knowledgeSelection: KnowledgeSelectionRequest? = null,
)

sealed interface DialogueOutcome {
    data class Response(
        val text: String,
        val sourceFacts: List<KnowledgeSelectionRequest.Fact> = emptyList(),
        val componentTargetProposal: String? = null,
    ) : DialogueOutcome
    data class Clarification(val question: String) : DialogueOutcome
    data class ToolProposal(
        val id: String,
        val tool: String,
        val version: Int,
        val arguments: Map<String, Any>,
        val deadlineMs: Long,
        val rationale: String,
    ) : DialogueOutcome
}

enum class DialogueFailureCode {
    UNSUPPORTED_DEVICE,
    MODEL_MISSING,
    MODEL_INTEGRITY,
    INVALID_REQUEST,
    MODEL_LOAD,
    MODEL_LOAD_TIMEOUT,
    MODEL_LOAD_CANCELLED,
    GENERATION_TIMEOUT,
    GENERATION_CANCELLED,
    OUTPUT_LIMIT,
    INVALID_MODEL_OUTPUT,
    POLICY_REJECTED,
    CLOSED,
}

class DialogueFailure(
    val code: DialogueFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class DialogueLimits(
    val maxHistoryTurns: Int = 8,
    val maxTurnChars: Int = 1_000,
    val maxObservationCount: Int = 12,
    val maxObservationChars: Int = 500,
    val maxDesignContextChars: Int = 12_000,
    val maxCapabilities: Int = 24,
    val maxPromptChars: Int = 32_000,
    val maxOutputChars: Int = 4_096,
    val maxSpokenWords: Int = 160,
    val maxPredictTokens: Int = 512,
    val maxDeadlineMs: Long = 60_000,
)

sealed interface DialogueRuntimeState {
    data object Uninitialized : DialogueRuntimeState
    data object Loading : DialogueRuntimeState
    data class Ready(val modelId: String) : DialogueRuntimeState
    data object Generating : DialogueRuntimeState
    data class Failed(val failure: DialogueFailure) : DialogueRuntimeState
    data object Closed : DialogueRuntimeState
}
