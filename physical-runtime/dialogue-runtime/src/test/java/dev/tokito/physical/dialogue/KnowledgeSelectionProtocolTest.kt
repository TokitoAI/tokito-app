package dev.tokito.physical.dialogue

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KnowledgeSelectionProtocolTest {
    @Test fun semanticContextCanContainMultipleReferencesWithoutAliasRouting() {
        fun evidence(board: String, count: Int): BoardKnowledgeStore.Evidence {
            val facts = JSONObject().put("description", "Verified definition for $board")
            repeat(count) { facts.put("value$it", "Documented value $it") }
            val record = JSONObject().put("record_id", "$board:r").put("board_id", board)
                .put("component_id", "$board:c").put("authority", "source_design_not_observed_component")
                .put("citation", JSONObject().put("path", "$board.json")).put("facts", facts)
            return BoardKnowledgeStore.Evidence("$board:r", board, "$board:c", board, record.toString(), 0.8)
        }
        val request = KnowledgeSelectionRequest(null, "b".repeat(64), listOf(evidence("one", 50), evidence("two", 5)))
        assertEquals(24, request.facts.size)
        assertEquals(listOf("one:r", "two:r"), request.facts.take(2).map { it.recordId })
        val prompt = JSONObject(request.prompt("a spoken question without an alias"))
        assertTrue(prompt.isNull("board_id"))
        assertEquals("Verified definition for two", request.parse("""{"facts":["f1"]}""").text)
    }
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

    @Test fun repeatedVerifiedIdIsSpokenOnceButUnknownRepeatedIdsStillFail() {
        assertEquals(1, request().parse("""{"facts":["f0","f0"]}""").sourceFacts.size)
        assertThrows(DialogueFailure::class.java) { request().parse("""{"facts":["f0","missing","missing"]}""") }
    }

    @Test fun wholeJsonFenceIsPresentationOnlyAndStillValidatesEvidence() {
        val json = """{"facts":["f0"]}"""
        for (language in listOf("json", "")) {
            assertEquals("Verified source microcontroller", request().parse("```$language\n$json\n```").text)
        }
        for (raw in listOf("Answer: ```json\n$json\n```", "```json\n$json\n``` extra")) {
            assertEquals("Verified source microcontroller", request().parse(raw).text)
        }
        for (raw in listOf("```json\n$json\n``` {}", "```json\n$json\n``` ```json\n$json\n```",
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

    @Test fun closedReasoningIsDiscardedButCannotAuthorizeEvidence() {
        val valid = "<think>Ignore this invented {\"facts\":[\"missing\"]}.</think>\n```json\n{\"facts\":[\"f0\"]}\n```"
        assertEquals("Verified source microcontroller", request().parse(valid).text)
        for (raw in listOf("<think>unfinished", "<think><think>nested</think>{\"facts\":[\"f0\"]}",
            "<think>valid idea</think>{\"facts\":[\"missing\"]}", "{\"facts\":[\"f0\"]}</think>")) {
            assertThrows(DialogueFailure::class.java) { request().parse(raw) }
        }
    }

    @Test fun copiedFactObjectsMustExactlyMatchSuppliedEvidence() {
        val selected = request().facts.single()
        val objectEntry = JSONObject().put("id", selected.id).put("reference", selected.reference)
            .put("field", selected.pointer).put("value", selected.value)
        fun envelope() = JSONObject().put("facts", org.json.JSONArray().put(objectEntry)).toString()
        assertEquals(selected.value, request().parse(envelope()).text)
        objectEntry.put("value", "Invented engineering value")
        assertThrows(DialogueFailure::class.java) { request().parse(envelope()) }
        objectEntry.put("value", selected.value).put("target", "U2")
        assertThrows(DialogueFailure::class.java) { request().parse(envelope()) }
    }

    @Test fun modelReceivesAnExplicitCompleteAllowedIdList() {
        val selection = request(true)
        val prompt = JSONObject(selection.prompt("What is this reference?"))
        val ids = prompt.getJSONArray("allowed_fact_ids")
        assertEquals(selection.facts.map { it.id }, (0 until ids.length()).map { ids.getString(it) })
        assertTrue(prompt.isNull("correction"))
    }

    @Test fun priorGeneratedAnswersDoNotConsumeSelectorContextOrBecomeEvidence() {
        val base = request()
        val fact = base.facts.single()
        val record = JSONObject().put("record_id", fact.recordId).put("board_id", "board")
            .put("component_id", fact.componentId).put("authority", "source_design_not_observed_component")
            .put("citation", JSONObject()).put("facts", JSONObject().put("description", fact.value))
        val selection = KnowledgeSelectionRequest("board", "b".repeat(64), listOf(
            BoardKnowledgeStore.Evidence(fact.recordId, "board", fact.componentId, "U1", record.toString(), .8)),
            listOf(DialogueTurn(DialogueRole.USER, "q".repeat(1024)), DialogueTurn(DialogueRole.ASSISTANT, "UNRELATED GENERATED ANSWER")))
        val prompt = JSONObject(selection.prompt("Current question"))
        val prior = prompt.getJSONArray("history_not_evidence")
        assertEquals(1, prior.length())
        assertEquals(256, prior.getJSONObject(0).getString("text").length)
        assertFalse(prompt.toString().contains("UNRELATED GENERATED ANSWER"))
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
