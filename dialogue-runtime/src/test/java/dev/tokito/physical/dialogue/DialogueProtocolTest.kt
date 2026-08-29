package dev.tokito.physical.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueProtocolTest {
    private val protocol = DialogueProtocol(DialogueLimits())
    private val capability = Capability(
        name = "locate_component",
        version = 1,
        description = "Locate a component already present in the verified design context",
        arguments = listOf(ToolArgument("reference", ArgumentType.STRING, maxLength = 16)),
        maxDeadlineMs = 3_000,
    )

    @Test
    fun parsesResponseAndClarification() {
        assertEquals(
            DialogueOutcome.Response("Place the board flat."),
            protocol.parse("{\"kind\":\"response\",\"text\":\"Place the board flat.\"}", request()),
        )
        assertEquals(
            DialogueOutcome.Clarification("Which diode?"),
            protocol.parse("{\"kind\":\"clarification\",\"question\":\"Which diode?\"}", request()),
        )
    }

    @Test
    fun acceptsOnlyDeclaredTypedToolProposal() {
        val output = """{"kind":"tool_proposal","id":"proposal_01","tool":"locate_component","version":1,"arguments":{"reference":"D3"},"deadline_ms":2500,"rationale":"User asked where D3 belongs"}"""
        val result = protocol.parse(output, request()) as DialogueOutcome.ToolProposal
        assertEquals("D3", result.arguments["reference"])
        assertEquals(2_500, result.deadlineMs)
    }

    @Test
    fun rejectsUndeclaredToolsAndArguments() {
        assertFailure(DialogueFailureCode.POLICY_REJECTED) {
            protocol.parse("""{"kind":"tool_proposal","id":"proposal_01","tool":"move_motor","version":1,"arguments":{},"deadline_ms":1000,"rationale":"move"}""", request())
        }
        assertFailure(DialogueFailureCode.POLICY_REJECTED) {
            protocol.parse("""{"kind":"tool_proposal","id":"proposal_01","tool":"locate_component","version":1,"arguments":{"reference":"D3","measurement":5},"deadline_ms":1000,"rationale":"locate"}""", request())
        }
    }

    @Test
    fun rejectsExtraFieldsAndTextOutsideJson() {
        assertFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT) {
            protocol.parse("answer: {\"kind\":\"response\",\"text\":\"x\"}", request())
        }
        assertFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT) {
            protocol.parse("{\"kind\":\"response\",\"text\":\"x\",\"measurement\":4.2}", request())
        }
    }

    @Test
    fun enforcesRequestAndDeadlineBounds() {
        assertFailure(DialogueFailureCode.INVALID_REQUEST) {
            protocol.requestPrompt(request().copy(utterance = "x".repeat(1_001)))
        }
        assertFailure(DialogueFailureCode.POLICY_REJECTED) {
            protocol.parse("""{"kind":"tool_proposal","id":"proposal_01","tool":"locate_component","version":1,"arguments":{"reference":"D3"},"deadline_ms":3001,"rationale":"locate"}""", request())
        }
    }

    @Test
    fun serializedPromptCarriesBoundedEvidenceNotRawMedia() {
        val prompt = protocol.requestPrompt(
            request().copy(observations = listOf(ObservationSummary("board_pose", "Board stable", 0.91, 1234))),
        )
        assertTrue(prompt.contains("board_pose"))
        assertTrue(prompt.contains("0.91"))
        assertTrue(!prompt.contains("image_bytes"))
    }

    private fun request() = DialogueRequest(
        utterance = "Where does D3 go?",
        capabilities = listOf(capability),
        deadlineMs = 4_000,
    )

    private fun assertFailure(code: DialogueFailureCode, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is DialogueFailure)
        assertEquals(code, (failure as DialogueFailure).code)
    }
}
