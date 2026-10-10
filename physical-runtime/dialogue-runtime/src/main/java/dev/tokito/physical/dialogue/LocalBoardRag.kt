package dev.tokito.physical.dialogue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native retrieval -> Qwen evidence selection. Never sends knowledge/media off device. */
class LocalBoardRag(
    private val store: BoardKnowledgeStore,
    private val encoder: TextEmbeddingEncoder,
    private val generator: TokitoDialogueRuntime,
    private val currentBinding: () -> Binding?,
    private val elapsedRealtimeMs: () -> Long,
) {
    private val operation = Mutex()
    private var historyBinding: Binding? = null
    private val history = ArrayDeque<DialogueTurn>()
    init {
        require(encoder.dimensions == 384 && encoder.modelIdentity.split(':').contains(store.expectedEncoderSha256)) {
            "Query encoder differs from the corpus encoder"
        }
    }
    data class Binding(
        val boardId: String,
        val corpusRevision: String,
        /** Revision of the selected reference scope, not an AR registration. */
        val scopeRevision: String,
        val expiresAtElapsedMs: Long,
    ) {
        init {
            require(boardId.isNotBlank() && boardId.length <= 128 && boardId.none(Char::isISOControl))
            require(corpusRevision.matches(Regex("[a-f0-9]{64}")))
            require(scopeRevision.isNotBlank() && scopeRevision.length <= 256 && scopeRevision.none(Char::isISOControl))
            require(expiresAtElapsedMs > 0)
        }
    }

    suspend fun answer(utterance: String): DialogueOutcome.Response = operation.withLock {
        DialogueProtocol(DialogueLimits()).validateRequest(DialogueRequest(utterance))
        if (Regex("(?i)\\b(?:this|my|our)\\s+(?:board|pcb|circuit|device|component)|\\bis\\s+this\\b").containsMatchIn(utterance)) {
            return@withLock DialogueOutcome.Response("The reference documentation cannot identify the physical board. I need verified board identification before answering about this board.")
        }
        val binding = currentBinding() ?: return@withLock DialogueOutcome.Response("Name the board reference you want to ask about.")
        if (historyBinding != binding) { history.clear(); historyBinding = binding }
        fun requireCurrent() {
            require(binding.corpusRevision == store.corpusRevision && currentBinding() == binding &&
                elapsedRealtimeMs() < binding.expiresAtElapsedMs) {
                "Board reference scope changed during local reasoning"
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
        if (evidence.isEmpty()) return@withLock DialogueOutcome.Response("No reference records are available for the selected board.")
        val selection = KnowledgeSelectionRequest(binding.boardId, binding.corpusRevision, evidence, history.toList())
        val answer = generator.respond(DialogueRequest(utterance, deadlineMs = 60_000, knowledgeSelection = selection))
        coroutineContext.ensureActive()
        requireCurrent()
        val response = answer as? DialogueOutcome.Response
            ?: throw DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Expected a source-grounded response")
        // Keep only published, source-validated answers. Context switches clear memory.
        if (response.sourceFacts.isNotEmpty()) {
            history.addLast(DialogueTurn(DialogueRole.USER, utterance.take(1024)))
            history.addLast(DialogueTurn(DialogueRole.ASSISTANT, response.text.take(1024)))
            while (history.size > 4) history.removeFirst()
        }
        response
    }
}
