package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class SemanticDesignSearchTest {
    private val records = listOf(
        DesignContextRecord("supply", DesignFactAuthority.DATASHEET, setOf("U1"),
            "The regulator supplies the MCU.", setOf("datasheet")),
        DesignContextRecord("connector", DesignFactAuthority.AUTHORED_PLACEMENT, setOf("J1"),
            "The connector receives external power.", setOf("placement")),
    )

    private class Encoder : TextEmbeddingEncoder {
        override var modelIdentity = "model-a"
        override val dimensions = 2
        var calls = 0
        var query = floatArrayOf(1f, 0f)
        override fun embed(text: String): FloatArray {
            calls++
            return when {
                text.startsWith("The regulator") -> floatArrayOf(1f, 0f)
                text.startsWith("The connector") -> floatArrayOf(0f, 1f)
                else -> query
            }
        }
        override fun close() = Unit
    }

    private class Cache : DesignVectorCache {
        var identity: String? = null
        var vectors: List<FloatArray>? = null
        override fun load(identity: String, dimensions: Int, recordIds: List<String>) =
            vectors?.takeIf { this.identity == identity }?.map { it.copyOf() }
        override fun replace(identity: String, dimensions: Int, recordIds: List<String>, vectors: List<FloatArray>) {
            this.identity = identity
            this.vectors = vectors.map { it.copyOf() }
        }
    }

    @Test fun semanticParaphraseRetrievesOnlyAdmittedRecord() {
        val index = LocalDesignContextIndex("rev-a", records).withEmbeddings(Encoder(), Cache())
        val context = requireNotNull(index.retrieve("Which device keeps the controller energized?"))
        assertTrue(context.contains("regulator"))
        assertTrue(context.contains("datasheet"))
        assertTrue(context.contains("rev-a"))
    }

    @Test fun missingReferenceNeverBecomesSemanticIdentity() {
        val index = LocalDesignContextIndex("rev-a", records).withEmbeddings(Encoder(), Cache())
        assertNull(index.retrieve("Where is U99?"))
        assertNull(index.retrieve("U99 external power"))
        assertNull(index.retrieve("pin 99"))
        encoderCallsForIdentityChecks()
        assertTrue(requireNotNull(index.answer("J1")).contains("connector"))
    }

    private fun encoderCallsForIdentityChecks(): Int {
        val encoder = Encoder()
        val index = LocalDesignContextIndex("rev-a", records).withEmbeddings(encoder, Cache())
        val calls = encoder.calls
        listOf("Where is U 99?", "J 99 external power", "pin A99", "pin SH", "pad XYZ", "which pins supply power?").forEach {
            assertNull("must not guess $it", index.retrieve(it))
        }
        assertEquals(calls, encoder.calls)
        assertTrue(requireNotNull(index.answer("J 1")).contains("connector"))
        return calls
    }

    @Test fun semanticSimilarityCannotAuthorizeBoardHighlight() {
        val target = DesignBoardTarget("U1", 0.3, 0.4)
        val index = LocalDesignContextIndex("rev-a", listOf(records.first().copy(boardTarget = target), records.last()))
            .withEmbeddings(Encoder(), Cache())
        assertNull(requireNotNull(index.groundedAnswer("Where is the device that keeps the controller energized?")).boardTarget)
        assertEquals(target, requireNotNull(index.groundedAnswer("Where is U 1?")).boardTarget)
    }

    @Test fun namedPinsRequireExactAdmittedDetailsAndComponentIdentity() {
        val encoder = Encoder()
        val pinRecords = records.map { it.copy(detailAnswers = listOf(DesignContextAnswer(setOf("a4"), "${it.exactKeys.single()} pin A4 is verified."))) }
        val index = LocalDesignContextIndex("rev-a", pinRecords).withEmbeddings(encoder, Cache())
        val calls = encoder.calls
        assertNull(index.answer("pin A4"))
        assertEquals("J1 pin A4 is verified.", index.answer("J 1 pin A4"))
        assertNull(index.answer("J 1 pin A99"))
        assertEquals(calls, encoder.calls)
    }

    @Test fun ambiguousAndUnrelatedVectorsAbstain() {
        val encoder = Encoder()
        val search = SemanticDesignSearch("rev-a", records, encoder, Cache())
        encoder.query = floatArrayOf(1f, 1f)
        assertTrue(search.search("ambiguous").isEmpty())
        encoder.query = floatArrayOf(-1f, -1f)
        assertTrue(search.search("unrelated").isEmpty())
    }

    @Test fun cacheReusedOnlyForSameModelCorpusAndRevision() {
        val encoder = Encoder()
        val cache = Cache()
        SemanticDesignSearch("rev-a", records, encoder, cache)
        assertEquals(2, encoder.calls)
        SemanticDesignSearch("rev-a", records, encoder, cache)
        assertEquals(2, encoder.calls)
        SemanticDesignSearch("rev-b", records, encoder, cache)
        assertEquals(4, encoder.calls)
        encoder.modelIdentity = "model-b"
        SemanticDesignSearch("rev-b", records, encoder, cache)
        assertEquals(6, encoder.calls)
        SemanticDesignSearch("rev-b", records.map { it.copy(text = it.text + " New fact.") }, encoder, cache)
        assertEquals(8, encoder.calls)
    }

    @Test fun sameQueryAvoidsRepeatedInference() {
        val encoder = Encoder()
        val search = SemanticDesignSearch("rev-a", records, encoder, Cache())
        search.search("energized")
        search.search("energized")
        assertEquals(3, encoder.calls)
    }

    @Test fun invalidVectorsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { SemanticDesignSearch.normalized(floatArrayOf(Float.NaN), 1) }
        assertThrows(IllegalArgumentException::class.java) { SemanticDesignSearch.normalized(floatArrayOf(0f), 1) }
        assertThrows(IllegalArgumentException::class.java) { SemanticDesignSearch.normalized(floatArrayOf(1f), 2) }
    }

    @Test fun admittedIdentityCannotMutateThroughCallerCollections() {
        val keys = mutableSetOf("U1")
        val sources = mutableSetOf("reviewed-source")
        val index = LocalDesignContextIndex("rev-a", listOf(records.first().copy(exactKeys = keys, sourceIds = sources)))
        keys.clear()
        keys.add("U99")
        sources.clear()
        sources.add("unreviewed-source")
        assertNull(index.retrieve("U99"))
        val context = requireNotNull(index.retrieve("U1"))
        assertTrue(context.contains("reviewed-source"))
        assertFalse(context.contains("unreviewed-source"))
    }
}
