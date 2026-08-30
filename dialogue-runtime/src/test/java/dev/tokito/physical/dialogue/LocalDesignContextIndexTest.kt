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

    @Test(expected = IllegalArgumentException::class)
    fun malformedRecordsAreRejectedAtConstruction() {
        LocalDesignContextIndex(
            contextRevision = "context-revision-a",
            records = records + records.first().copy(recordId = "record\u0000alias"),
        )
    }

    private fun index() = LocalDesignContextIndex("context-revision-a", records)
}
