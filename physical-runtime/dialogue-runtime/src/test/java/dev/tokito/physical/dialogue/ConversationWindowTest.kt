package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class ConversationWindowTest {
    @Test fun evictsOnlyOldestHistoryAndPreservesEvidenceAndQuestion() {
        val request = DialogueRequest("Current question", history = listOf(
            DialogueTurn(DialogueRole.USER, "old"), DialogueTurn(DialogueRole.ASSISTANT, "new")),
            designContext = "unchanged evidence")
        val attempted = mutableListOf<List<String>>()
        val admitted = admitConversationWindow(request) {
            attempted += it.history.map { turn -> turn.text }
            assertEquals(request.utterance, it.utterance)
            assertEquals(request.designContext, it.designContext)
            if (it.history.size > 1) throw DialogueFailure(DialogueFailureCode.INVALID_REQUEST, "Overflow")
            it
        }
        assertEquals(listOf(listOf("old", "new"), listOf("new")), attempted)
        assertEquals("new", admitted.history.single().text)
    }
    @Test fun neverDropsEvidenceOrRetriesBackendFailures() {
        val request = DialogueRequest("Question", history = listOf(DialogueTurn(DialogueRole.USER, "old")))
        var attempts = 0
        val failure = runCatching { admitConversationWindow(request) {
            attempts++; throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Bad tokenizer")
        } }.exceptionOrNull() as DialogueFailure
        assertEquals(DialogueFailureCode.MODEL_INTEGRITY, failure.code)
        assertEquals(1, attempts)
        assertEquals(DialogueFailureCode.INVALID_REQUEST, (runCatching {
            admitConversationWindow(request) { throw DialogueFailure(DialogueFailureCode.INVALID_REQUEST, "Evidence too large") }
        }.exceptionOrNull() as DialogueFailure).code)
    }
}
