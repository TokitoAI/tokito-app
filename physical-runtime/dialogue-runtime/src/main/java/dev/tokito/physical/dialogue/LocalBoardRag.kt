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
    init {
        require(encoder.dimensions == 384 && encoder.modelIdentity.split(':').contains(store.expectedEncoderSha256)) {
            "Query encoder differs from the corpus encoder"
        }
    }
    data class Binding(
        val boardId: String?,
        val corpusRevision: String,
        /** Revision of the selected reference scope, not an AR registration. */
        val scopeRevision: String,
        val expiresAtElapsedMs: Long,
        val physicalIdentityVerified: Boolean = false,
    ) {
        init {
            require(boardId == null || (boardId.isNotBlank() && boardId.length <= 128 && boardId.none(Char::isISOControl)))
            require(corpusRevision.matches(Regex("[a-f0-9]{64}")))
            require(scopeRevision.isNotBlank() && scopeRevision.length <= 256 && scopeRevision.none(Char::isISOControl))
            require(expiresAtElapsedMs > 0)
            require(!physicalIdentityVerified || boardId != null)
        }
    }

    suspend fun answer(utterance: String): DialogueOutcome.Response = operation.withLock {
        DialogueProtocol(DialogueLimits()).validateRequest(DialogueRequest(utterance))
        SocialConversationPolicy.reply(utterance)?.let { return@withLock it }
        val binding = currentBinding() ?: return@withLock DialogueOutcome.Response("Name the board reference you want to ask about.")
        if (!binding.physicalIdentityVerified && Regex("(?i)\\b(?:this|my|our)\\s+(?:board|pcb|circuit|device|component)|\\bis\\s+this\\b").containsMatchIn(utterance)) {
            return@withLock DialogueOutcome.Response("The reference documentation cannot identify the physical board. I need verified board identification before answering about this board.")
        }
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
            // Bench queries are independent. Prior questions and answers must
            // not affect retrieval or consume the next query's context.
            store.retrieve(binding.boardId, encoder.embed(utterance)).also {
                coroutineContext.ensureActive()
                requireCurrent()
            }
        }
        if (evidence.isEmpty()) return@withLock DialogueOutcome.Response("No reference records are available for the selected board.")
        val selection = KnowledgeSelectionRequest(binding.boardId, binding.corpusRevision, evidence)
        android.util.Log.i("TokitoDialogue", "Reference retrieval records=${evidence.size} facts=${selection.facts.size} descriptionPresent=${selection.facts.any { it.pointer == "/facts/description" }}")
        // One bounded regeneration of a malformed selection uses the same
        // admitted evidence, never unchecked prose or a different model.
        val answer = kotlinx.coroutines.withTimeout(60_000) {
            try {
                generator.respond(DialogueRequest(utterance, deadlineMs = 60_000, knowledgeSelection = selection))
            } catch (failure: DialogueFailure) {
                if (!canRepairEvidenceSelection(failure.code)) throw failure
                coroutineContext.ensureActive()
                requireCurrent()
                val corrected = KnowledgeSelectionRequest(binding.boardId, binding.corpusRevision,
                    evidence, repairAttempt = true)
                generator.respond(DialogueRequest(utterance, deadlineMs = 60_000, knowledgeSelection = corrected))
            }
        }
        coroutineContext.ensureActive()
        requireCurrent()
        val response = answer as? DialogueOutcome.Response
            ?: throw DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Expected a source-grounded response")
        response
    }
}

/** One same-source repair only; never retry deadlines, backend faults or context admission. */
internal fun canRepairEvidenceSelection(code: DialogueFailureCode): Boolean =
    code == DialogueFailureCode.INVALID_MODEL_OUTPUT || code == DialogueFailureCode.OUTPUT_LIMIT
