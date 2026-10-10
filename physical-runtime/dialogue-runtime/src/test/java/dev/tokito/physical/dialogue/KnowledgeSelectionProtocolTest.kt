package dev.tokito.physical.dialogue

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KnowledgeSelectionProtocolTest {
    private fun request(extraFacts: Boolean = false): KnowledgeSelectionRequest {
        val record = JSONObject().put("record_id", "board:U1:source-0").put("board_id", "board")
            .put("component_id", "board:U1").put("authority", "source_design_not_observed_component")
            .put("citation", JSONObject().put("path", "source.json").put("sha256", "a".repeat(64)).put("json_pointer", "/0"))
            .put("facts", JSONObject().put("description", "Verified source microcontroller").also {
                if (extraFacts) for (i in 1..5) it.put("value$i", "Source value $i")
            })
        return KnowledgeSelectionRequest("board", "b".repeat(64), listOf(
            BoardKnowledgeStore.Evidence("board:U1:source-0", "board", "board:U1", "U1", record.toString(), 0.8)))
    }

    @Test fun qwenCanSelectOnlyExistingCitedFactsNotInventTechnicalProse() {
        val answer = request().parse("""{"facts":["f0"]}""")
        assertEquals("Verified source microcontroller", answer.text)
        assertNull(answer.componentTargetProposal)
        assertEquals("/facts/description", answer.sourceFacts.single().pointer)
        assertTrue(answer.sourceFacts.single().sourceCitation.contains("source.json"))
    }

    @Test fun invalidOrCrossBoardGeneratedSelectionFailsClosed() {
        val invalid = listOf(
            """{"facts":["missing"]}""",
            """{"facts":["f0"],"target":"other:U1","abstain":false}""",
            """{"facts":["f0","f0"]}""",
            """{"facts":["f0"],"target":null,"abstain":false,"text":"Invented 5V"}""",
            """{"facts":[],"target":null,"abstain":false}""",
            """{"facts":["f0"],"target":null,"abstain":true}""",
            """{"facts":[]}{"extra":true}""",
        )
        invalid.forEach { raw -> assertThrows(DialogueFailure::class.java) { request().parse(raw) } }
    }

    @Test fun abstentionHasNeitherSourceClaimsNorTarget() {
        val answer = request().parse("""{"facts":[]}""")
        assertTrue(answer.sourceFacts.isEmpty())
        assertNull(answer.componentTargetProposal)
    }

    @Test fun wholeJsonFenceIsPresentationOnlyAndStillValidatesEvidence() {
        val json = """{"facts":["f0"]}"""
        for (language in listOf("json", "")) {
            assertEquals("Verified source microcontroller", request().parse("```$language\n$json\n```").text)
        }
        for (raw in listOf("Answer: ```json\n$json\n```", "```json\n$json\n``` extra",
            "```json\n{\"facts\":[\"invented\"]}\n```")) {
            assertThrows(DialogueFailure::class.java) { request().parse(raw) }
        }
    }

    @Test fun excessValidSelectionsAreBudgetedOnlyAfterAllIdsAreValidated() {
        val answer = request(true).parse("""{"facts":["f0","f1","f2","f3","f4"]}""")
        assertEquals(4, answer.sourceFacts.size)
        assertThrows(DialogueFailure::class.java) {
            request(true).parse("""{"facts":["f0","f1","f2","f3","invented"]}""")
        }
    }

    @Test fun modelReceivesAnExplicitCompleteAllowedIdList() {
        val selection = request(true)
        val prompt = JSONObject(selection.prompt("What is this reference?"))
        val ids = prompt.getJSONArray("allowed_fact_ids")
        assertEquals(selection.facts.map { it.id }, (0 until ids.length()).map { ids.getString(it) })
        assertTrue(prompt.isNull("correction"))
    }

    @Test fun noMixedToolAuthorityOrHistoryForRag() {
        val protocol = DialogueProtocol(DialogueLimits())
        val valid = DialogueRequest("Where is the MCU?", knowledgeSelection = request())
        protocol.validateRequest(valid)
        assertThrows(DialogueFailure::class.java) { protocol.validateRequest(valid.copy(designContext = "{}")) }
        assertThrows(DialogueFailure::class.java) {
            protocol.validateRequest(valid.copy(history = listOf(DialogueTurn(DialogueRole.ASSISTANT, "It was 5V"))))
        }
    }
}
