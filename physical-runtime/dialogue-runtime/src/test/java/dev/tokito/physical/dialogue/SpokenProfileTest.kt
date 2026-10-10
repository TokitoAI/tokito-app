package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test
import com.geniex.sdk.bean.ProfilingData

class SpokenProfileTest {
    private fun receipt(tokens: Long = 12, reason: String = "eos", ttft: Double = 25.0) =
        ProfilingData(ttft, 15.0, 10.0, 100, tokens, 0, 0.0, 0.0, 0.0, reason)

    @Test fun vendorTokenCountsAndTerminationMustBeValid() {
        validateGenerationReceipt(receipt(), 128)
        listOf(receipt(tokens = -1), receipt(tokens = 129), receipt(ttft = Double.NaN)).forEach {
            val failure = runCatching { validateGenerationReceipt(it, 128) }.exceptionOrNull()
            assertEquals(DialogueFailureCode.INVALID_MODEL_OUTPUT, (failure as DialogueFailure).code)
        }
        listOf("length", "max_tokens", "TOKEN_LIMIT").forEach {
            val failure = runCatching { validateGenerationReceipt(receipt(reason = it), 128) }.exceptionOrNull()
            assertEquals(DialogueFailureCode.OUTPUT_LIMIT, (failure as DialogueFailure).code)
        }
    }
    @Test fun qwenNonThinkingProfileDoesNotUseGreedyDecoding() {
        val config = conversationalSampler(DialogueRuntime.QAIRT)
        assertEquals(0.7f, config.temperature)
        assertEquals(0.8f, config.topP)
        assertEquals(20, config.topK)
        assertEquals(0f, config.minP)
    }

    @Test fun sourceSelectorAlsoPreservesQwenNonGreedyProfile() {
        val config = evidenceSelectionSampler(DialogueRuntime.QAIRT)
        assertEquals(0.7f, config.temperature)
        assertEquals(0.8f, config.topP)
        assertEquals(20, config.topK)
        assertEquals(0f, config.minP)
        assertTrue(canRepairEvidenceSelection(DialogueFailureCode.OUTPUT_LIMIT))
        assertTrue(canRepairEvidenceSelection(DialogueFailureCode.INVALID_MODEL_OUTPUT))
        assertFalse(canRepairEvidenceSelection(DialogueFailureCode.GENERATION_TIMEOUT))
        assertFalse(canRepairEvidenceSelection(DialogueFailureCode.MODEL_LOAD))
    }

    @Test fun spokenPromptHasOneConsistentConfigurableBudgetWithoutAnswerFixtures() {
        val protocol = DialogueProtocol(DialogueLimits())
        assertTrue(protocol.conversationalSystemPrompt().contains("160 words"))
        assertTrue(protocol.conversationalSystemPrompt().contains("/no_think"))
        val question = "Explain inductance in general."
        val request = protocol.requestPrompt(DialogueRequest(question))
        assertTrue(request.contains(question))
        assertTrue(request.contains("at most 160 words"))
        assertFalse(request.contains("at most two"))
        assertFalse(request.contains("resistor"))
    }
}
