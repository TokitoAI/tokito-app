package dev.tokito.physical.dialogue

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingPreprocessingTest {
    private val vocabulary = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "cafe", "resistor", "r", "##1", ",", "中", "文")

    @Test fun uncasedAccentPunctuationChineseAndWordPieces() {
        assertArrayEquals(longArrayOf(2, 5, 9, 6, 7, 8, 10, 11, 3),
            BertWordPieceTokenizer(vocabulary).encode("CAFÉ, resistor R1 中文"))
    }

    @Test fun unknownWordDoesNotEmitPartialPrefix() {
        assertArrayEquals(longArrayOf(2, 1, 3), BertWordPieceTokenizer(vocabulary).encode("R199"))
    }

    @Test fun truncationAlwaysReservesSeparator() {
        val ids = BertWordPieceTokenizer(vocabulary, 8).encode("resistor ".repeat(20))
        assertEquals(8, ids.size)
        assertEquals(2L, ids.first())
        assertEquals(3L, ids.last())
    }

    @Test fun rejectsUnboundedOrEmptyInput() {
        for (input in listOf("", " ", "a".repeat(8193), "\u0000\u0001")) {
            assertTrue(runCatching { BertWordPieceTokenizer(vocabulary).encode(input) }.isFailure)
        }
    }

    @Test fun longWordIsOneUnknownAndSpecialTokensPreserved() {
        assertArrayEquals(longArrayOf(2, 1, 4, 3), BertWordPieceTokenizer(vocabulary).encode("x".repeat(101) + " [MASK]"))
    }

    @Test fun specialTokensArePreservedWithoutSurroundingSpaces() {
        assertArrayEquals(longArrayOf(2, 6, 4, 6, 3), BertWordPieceTokenizer(vocabulary).encode("resistor[MASK]resistor"))
    }

    @Test fun rejectsDuplicateOrIncompleteVocabulary() {
        assertTrue(runCatching { BertWordPieceTokenizer(vocabulary + "resistor") }.isFailure)
        assertTrue(runCatching { BertWordPieceTokenizer(vocabulary.filterNot { it == "[SEP]" }) }.isFailure)
    }

    @Test fun poolingIgnoresPaddingAndNormalizes() {
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), EmbeddingMath.meanPool(
            arrayOf(floatArrayOf(3f, 4f), floatArrayOf(100f, 100f)), longArrayOf(1, 0), 2,
        ), 0.00001f)
    }

    @Test fun pooledOutputUsesMeanNotFirstToken() {
        val output = EmbeddingMath.meanPool(arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), longArrayOf(1, 1), 2)
        assertArrayEquals(floatArrayOf(0.70710677f, 0.70710677f), output, 0.00001f)
    }

    @Test fun rejectsInvalidVectorsMasksAndDimensions() {
        assertTrue(runCatching { EmbeddingMath.normalize(floatArrayOf(0f, 0f)) }.isFailure)
        assertTrue(runCatching { EmbeddingMath.normalize(floatArrayOf(Float.NaN)) }.isFailure)
        assertTrue(runCatching { EmbeddingMath.normalize(floatArrayOf(Float.POSITIVE_INFINITY)) }.isFailure)
        assertTrue(runCatching { EmbeddingMath.meanPool(arrayOf(floatArrayOf(1f)), longArrayOf(0), 1) }.isFailure)
        assertTrue(runCatching { EmbeddingMath.meanPool(arrayOf(floatArrayOf(1f)), longArrayOf(2), 1) }.isFailure)
        assertTrue(runCatching { EmbeddingMath.meanPool(arrayOf(floatArrayOf(1f)), longArrayOf(1), 2) }.isFailure)
    }
}
