package dev.tokito.physical.dialogue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDesignContextIndexTest {
    private val records = listOf(
        DesignContextRecord(
            recordId = "placement-r14",
            authority = DesignFactAuthority.AUTHORED_PLACEMENT,
            exactKeys = setOf("R14"),
            text = "R14 uses footprint 0402 at centroid 12.5 mm, 8.0 mm on the top side.",
            sourceIds = setOf("pcb-placement"),
        ),
        DesignContextRecord(
            recordId = "reset-evidence",
            authority = DesignFactAuthority.DATASHEET,
            exactKeys = setOf("SW1"),
            text = "The reset switch is active low and is grounded when pressed.",
            sourceIds = setOf("datasheet-switch"),
        ),
    )

    @Test
    fun exactReferenceLookupWinsAndRetainsEvidence() {
        val result = JSONObject(index().retrieve("Where is R14?")!!)
        assertEquals("context-revision-a", result.getString("context_revision"))
        val record = result.getJSONArray("records").getJSONObject(0)
        assertEquals("placement-r14", record.getString("record_id"))
        assertEquals("authored_placement", record.getString("authority"))
        assertEquals("pcb-placement", record.getJSONArray("source_ids").getString(0))
    }

    @Test fun explicitReferenceCannotRedirectToAnUnrelatedHigherAuthorityTarget() {
        val index = LocalDesignContextIndex("test-revision", listOf(
            DesignContextRecord("r14", DesignFactAuthority.DATASHEET, setOf("R14"),
                "R14 is a reset resistor.", setOf("datasheet")),
            DesignContextRecord("switch", DesignFactAuthority.AUTHORED_PLACEMENT, setOf("SW1"),
                "SW1 is the reset switch.", setOf("board"),
                spokenAliases = setOf("reset switch"),
                boardTarget = DesignBoardTarget("SW1", 0.5, 0.5)),
        ))
        val answer = index.groundedAnswer("Where is R14 near the reset switch?")!!
        assertEquals("R14 is a reset resistor.", answer.fallbackText)
        assertNull(answer.boardTarget)
        assertEquals("r14", JSONObject(answer.designContext).getJSONArray("records").getJSONObject(0).getString("record_id"))
    }

    @Test
    fun lexicalEvidenceLookupDoesNotRequireAnEmbeddingModel() {
        val result = JSONObject(index().retrieve("Is the reset switch active low?")!!)
        assertEquals(
            "reset-evidence",
            result.getJSONArray("records").getJSONObject(0).getString("record_id"),
        )
    }

    @Test
    fun unrelatedQueriesReturnNoContextInsteadOfWholeCorpus() {
        assertNull(index().retrieve("Tell me about a motor controller"))
        assertNull(index().answer("Tell me about a motor controller"))
    }

    @Test
    fun deterministicAnswersDoNotReuseAPreviousRecord() {
        assertTrue(index().answer("Where is R14?")!!.contains("R14"))
        assertTrue(index().answer("Is the reset switch active low?")!!.contains("reset switch"))
    }

    @Test
    fun retrievalIsDeterministicAndBounded() {
        val constrained = LocalDesignContextIndex(
            contextRevision = "context-revision-a",
            records = records,
            limits = DesignContextIndexLimits(maxRecords = 1, maxContextChars = 500, maxRecordChars = 200),
        )
        val first = constrained.retrieve("reset R14")!!
        assertEquals(first, constrained.retrieve("reset R14"))
        assertTrue(first.length <= 500)
        assertEquals(1, JSONObject(first).getJSONArray("records").length())
    }

    @Test
    fun groundedAnswerIncludesAllBoundedMatchesForAggregateQuestions() {
        val result = index().groundedAnswer("Which reset R14 records are available?")!!
        val records = JSONObject(result.designContext).getJSONArray("records")
        assertEquals(2, records.length())
        assertTrue(result.requiredAnchors.containsAll(setOf("R14", "SW1")))
    }

    @Test
    fun aggregateIoQuestionExcludesRecordsMatchedOnlyByFillerWords() {
        val ioRecords = listOf(
            DesignContextRecord(
                recordId = "mcu-usb-port",
                authority = DesignFactAuthority.DATASHEET,
                exactKeys = setOf("J10"),
                text = "J10 is the MCU USB I/O port.",
                sourceIds = setOf("board-guide"),
            ),
            DesignContextRecord(
                recordId = "debug-usb-port",
                authority = DesignFactAuthority.DATASHEET,
                exactKeys = setOf("J11"),
                text = "J11 is the MCU-Link USB I/O port.",
                sourceIds = setOf("board-guide"),
            ),
            DesignContextRecord(
                recordId = "accelerometer",
                authority = DesignFactAuthority.DATASHEET,
                exactKeys = setOf("U5"),
                text = "This board has an accelerometer landmark.",
                sourceIds = setOf("board-guide"),
            ),
        )
        val result = LocalDesignContextIndex("context-revision-a", ioRecords)
            .groundedAnswer("How many I/O ports does this have?")!!
        val selected = JSONObject(result.designContext).getJSONArray("records")
        assertEquals(2, selected.length())
        assertEquals("debug-usb-port", selected.getJSONObject(0).getString("record_id"))
        assertEquals("mcu-usb-port", selected.getJSONObject(1).getString("record_id"))
    }

    @Test
    fun unqualifiedPinNumberResolvesOnlyWhenUnique() {
        val connectorJ9 = DesignContextRecord(
            recordId = "connector-j9",
            authority = DesignFactAuthority.SUPPORTING,
            exactKeys = setOf("J9", "P0_19"),
            text = "Camera header J9 pin map: 32=P0_19.",
            sourceIds = setOf("manual"),
            spokenAliases = setOf("J9", "camera header"),
            detailAnswers = listOf(DesignContextAnswer(setOf("32"), "J9 pin 32 is P0_19.")),
        )
        val connectorJ1 = connectorJ9.copy(
            recordId = "connector-j1",
            exactKeys = setOf("J1", "P4_3"),
            text = "Header J1 pin map: 2=P4_3.",
            spokenAliases = setOf("J1", "header one"),
            rankingAliases = setOf("J1", "header one"),
            detailAnswers = listOf(DesignContextAnswer(setOf("2"), "J1 pin 2 is P4_3.")),
        )
        val connectorJ2 = connectorJ9.copy(
            recordId = "connector-j2",
            exactKeys = setOf("J2", "P0_23"),
            text = "Header J2 pin map: 2=P0_23.",
            spokenAliases = setOf("J2", "header two"),
            rankingAliases = setOf("J2", "header two"),
            detailAnswers = listOf(DesignContextAnswer(setOf("2"), "J2 pin 2 is P0_23.")),
        )
        val competingJ1Fact = DesignContextRecord(
            recordId = "knowledge-j1-capability",
            authority = DesignFactAuthority.DATASHEET,
            exactKeys = setOf("J1"),
            text = "J1 exposes expansion functions.",
            sourceIds = setOf("manual"),
            spokenAliases = setOf("J1 capability"),
        )
        val pinIndex = LocalDesignContextIndex(
            "context-revision-a",
            listOf(connectorJ9, connectorJ1, connectorJ2, competingJ1Fact),
        )

        assertEquals("J9 pin 32 is P0_19.", pinIndex.answer("Where is pin32?"))
        assertNull(pinIndex.answer("Where is pin 2?"))
        assertEquals("J1 pin 2 is P4_3.", pinIndex.answer("What is J1 pin 2?"))
    }

    @Test
    fun asrTypoCanRecoverAUniqueKnowledgeAlias() {
        val timer = DesignContextRecord(
            recordId = "knowledge-timers",
            authority = DesignFactAuthority.DATASHEET,
            exactKeys = setOf("CTIMER0"),
            text = "The MCU has five 32-bit timer and counter instances.",
            sourceIds = setOf("sdk"),
            spokenAliases = setOf("timer", "counter"),
            spokenSummary = "Yes. It has five 32-bit CTIMER instances, CTIMER0 through CTIMER4.",
        )
        val timerIndex = LocalDesignContextIndex("context-revision-a", listOf(timer))

        assertEquals(timer.spokenSummary, timerIndex.answer("Does it have a cunter?"))
        assertEquals(timer.spokenSummary, timerIndex.answer("Does this have a timer?"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedRecordsAreRejectedAtConstruction() {
        LocalDesignContextIndex(
            contextRevision = "context-revision-a",
            records = records + records.first().copy(recordId = "record\u0000alias"),
        )
    }

    private fun index() = LocalDesignContextIndex("context-revision-a", records)
}
