package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class GroundedConversationProtocolTest {
    private val protocol = GroundedConversationProtocol(DialogueLimits())
    private val request = DialogueRequest("Current value?", designContext = LocalDesignContextIndex("current", listOf(
        DesignContextRecord("verified-a", DesignFactAuthority.AUTHORED_PLACEMENT, setOf("R8"),
            "R8 is 2.2 kiloohms.", setOf("authored-source")))).retrieve("R8"))

    @Test fun answerIsConstructedFromSourceNotModelProse() {
        assertEquals(DialogueOutcome.Response("R8 is 2.2 kiloohms."),
            protocol.parse("</think>{\"facts\":[\"verified-a\"]}", request))
        assertTrue(protocol.parse("{\"facts\":[]}", request) is DialogueOutcome.Clarification)
    }
    @Test fun inventedIdsValuesAndControlPayloadsFailClosed() {
        listOf("{\"facts\":[\"invented\"]}", "{\"facts\":[\"verified-a\",\"verified-a\"]}",
            "{\"facts\":[\"verified-a\"],\"text\":\"R8 is 100 ohms\"}",
            "{\"facts\":[0]}", "{\"tool\":\"energize\"}", "Free prose", "<think>unfinished").forEach {
            assertEquals(DialogueFailureCode.INVALID_MODEL_OUTPUT,
                (runCatching { protocol.parse(it, request) }.exceptionOrNull() as DialogueFailure).code)
        }
    }
}
