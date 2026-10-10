package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class SpokenProfileTest {
    @Test fun qwenNonThinkingProfileDoesNotUseGreedyDecoding() {
        val config = conversationalSampler(DialogueRuntime.QAIRT)
        assertEquals(0.7f, config.temperature)
        assertEquals(0.8f, config.topP)
        assertEquals(20, config.topK)
        assertEquals(0f, config.minP)
    }

    @Test fun spokenPromptHasOneConsistentSentenceBudgetWithoutAnswerFixtures() {
        val protocol = DialogueProtocol(DialogueLimits())
        assertTrue(protocol.conversationalSystemPrompt().contains("exactly one short sentence"))
        assertTrue(protocol.conversationalSystemPrompt().contains("35 words"))
        val question = "Explain inductance in general."
        val request = protocol.requestPrompt(DialogueRequest(question))
        assertTrue(request.contains(question))
        assertTrue(request.contains("one sentence of at most 35 words"))
        assertFalse(request.contains("at most two"))
        assertFalse(request.contains("resistor"))
    }
}
