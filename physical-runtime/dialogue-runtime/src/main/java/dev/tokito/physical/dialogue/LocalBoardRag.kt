package dev.tokito.physical.dialogue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Native retrieval -> Qwen evidence selection. Never sends knowledge/media off device. */
class LocalBoardRag(
    private val store: BoardKnowledgeStore,
    private val encoder: TextEmbeddingEncoder,
    private val generator: TokitoDialogueRuntime,
    private val currentBinding: () -> Binding?,
    private val elapsedRealtimeMs: () -> Long,
) {
    init {
        require(encoder.dimensions == 384 && encoder.modelIdentity.split(':').contains(store.expectedEncoderSha256)) {
            "Query encoder differs from the corpus encoder"
        }
    }
    data class Binding(
        val boardId: String,
        val corpusRevision: String,
        val registrationRevision: String,
        val expiresAtElapsedMs: Long,
    ) {
        init {
            require(boardId.isNotBlank() && boardId.length <= 128 && boardId.none(Char::isISOControl))
            require(corpusRevision.matches(Regex("[a-f0-9]{64}")))
            require(registrationRevision.isNotBlank() && registrationRevision.length <= 256)
            require(expiresAtElapsedMs > 0)
        }
    }

    suspend fun answer(utterance: String): DialogueOutcome.Response {
        DialogueProtocol(DialogueLimits()).validateRequest(DialogueRequest(utterance))
        val binding = currentBinding() ?: return DialogueOutcome.Response("I need a verified board identity before searching its reference data.")
        fun requireCurrent() {
            require(binding.corpusRevision == store.corpusRevision && currentBinding() == binding &&
                elapsedRealtimeMs() < binding.expiresAtElapsedMs) {
                "Board identity or registration changed during local reasoning"
            }
        }
        requireCurrent()
        val evidence = withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            requireCurrent()
            store.retrieve(binding.boardId, encoder.embed(utterance)).also {
                coroutineContext.ensureActive()
                requireCurrent()
            }
        }
        if (evidence.isEmpty()) return DialogueOutcome.Response("No reference records are available for the identified board.")
        val selection = KnowledgeSelectionRequest(binding.boardId, binding.corpusRevision, evidence)
        val answer = generator.respond(DialogueRequest(utterance, deadlineMs = 60_000, knowledgeSelection = selection))
        coroutineContext.ensureActive()
        requireCurrent()
        return answer as? DialogueOutcome.Response
            ?: throw DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Expected a source-grounded response")
    }
}
