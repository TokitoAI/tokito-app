package dev.tokito.physical.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DeterministicMeasurementParserTest {
    private val parser = DeterministicMeasurementParser()

    @Test
    fun parsesScalarRelationshipAndNormalizesToSi() {
        val draft = parser.parse("5.2 volts from TP1 to GND").parsed()

        assertDecimal("5.2", draft.primary.scalar())
        assertEquals(MeasurementUnit.VOLT, draft.primary.unit)
        assertEquals(
            MeasurementRelationship(RelationshipKind.BETWEEN, "TP1", "GND"),
            draft.primary.relationship,
        )
        assertEquals("5.2 volts", draft.primary.sourceSpan.text)
        assertDecimal("5.2", draft.primary.normalizedScalar())
        assertTrue(draft.alternatives.isEmpty())
        assertFalse(draft.requiresConfirmation)
    }

    @Test
    fun parsesNegativeSpokenDecimalAndSiPrefix() {
        val draft = parser.parse("negative five point two millivolts at TP2").parsed()

        assertDecimal("-5.2", draft.primary.scalar())
        assertDecimal("-0.0052", draft.primary.normalizedScalar())
        assertEquals(MeasurementRelationship(RelationshipKind.AT, "TP2"), draft.primary.relationship)
    }

    @Test
    fun appliesExplicitDotAndCommaConventions() {
        val dot = DeterministicMeasurementParser(
            MeasurementParserConfig(decimalConvention = DecimalConvention.DOT),
        ).parse("1,000.5 millivolts").parsed()
        val comma = DeterministicMeasurementParser(
            MeasurementParserConfig(decimalConvention = DecimalConvention.COMMA),
        ).parse("1.000,5 millivolts").parsed()

        assertDecimal("1000.5", dot.primary.scalar())
        assertDecimal("1.0005", dot.primary.normalizedScalar())
        assertDecimal("1000.5", comma.primary.scalar())
        assertDecimal("1.0005", comma.primary.normalizedScalar())
    }

    @Test
    fun flagsNonConfiguredSeparatorAsAmbiguousDecimal() {
        val draft = parser.parse("4,8 volts").parsed()

        assertDecimal("4.8", draft.primary.scalar())
        assertTrue(AmbiguityReason.DECIMAL_SEPARATOR in draft.ambiguities)
        assertTrue(draft.requiresConfirmation)
    }

    @Test
    fun preservesRangeAndRequiresConfirmation() {
        val draft = parser.parse("4.8 to 5.2 volts across R12").parsed()
        val range = draft.primary.value as MeasurementValue.Range

        assertDecimal("4.8", range.low)
        assertDecimal("5.2", range.high)
        assertEquals(MeasurementRelationship(RelationshipKind.ACROSS, "R12"), draft.primary.relationship)
        assertTrue(AmbiguityReason.RANGE_STATEMENT in draft.ambiguities)
    }

    @Test
    fun correctionUsesLastReadingAndRetainsAlternative() {
        val draft = parser.parse("5 volts, no sorry, 4.8 volts").parsed()

        assertDecimal("4.8", draft.primary.scalar())
        assertDecimal("5", draft.alternatives.single().scalar())
        assertTrue(AmbiguityReason.CORRECTION in draft.ambiguities)
    }

    @Test
    fun distinguishesCaseSensitiveMegaohmAndMilliohmSymbols() {
        val mega = parser.parse("2 MΩ").parsed().primary
        val milli = parser.parse("2 mΩ").parsed().primary

        assertEquals(MeasurementUnit.MEGAOHM, mega.unit)
        assertEquals(MeasurementUnit.MILLIOHM, milli.unit)
        assertDecimal("2000000", mega.normalizedScalar())
        assertDecimal("0.002", milli.normalizedScalar())
    }

    @Test
    fun rejectsContradictoryMeasurementQuantities() {
        val result = parser.parse("5 volts and 2 amps") as MeasurementParseResult.Incomplete
        assertEquals(IncompleteReason.CONTRADICTORY_UNIT, result.reason)
    }

    @Test
    fun rejectsMissingUnsupportedScientificAndOverflowValues() {
        assertEquals(IncompleteReason.MISSING_VALUE, parser.parse("volts").incomplete().reason)
        assertEquals(IncompleteReason.MISSING_UNIT, parser.parse("5.2").incomplete().reason)
        assertEquals(IncompleteReason.UNSUPPORTED_UNIT, parser.parse("25 celsius").incomplete().reason)
        assertEquals(IncompleteReason.OVERFLOW, parser.parse("1e6 volts").incomplete().reason)
        val bounded = DeterministicMeasurementParser(
            MeasurementParserConfig(maximumAbsoluteSiValue = BigDecimal("10")),
        )
        assertEquals(IncompleteReason.OVERFLOW, bounded.parse("11 volts").incomplete().reason)
    }

    @Test
    fun relationshipAndTranscriptConfidencePoliciesAreExplicit() {
        val strict = DeterministicMeasurementParser(
            MeasurementParserConfig(requireRelationship = true, minimumTranscriptConfidence = 0.9),
        )
        val draft = strict.parse("3.3 volts", transcriptConfidence = 0.7).parsed()

        assertTrue(AmbiguityReason.MISSING_RELATIONSHIP in draft.ambiguities)
        assertTrue(AmbiguityReason.LOW_TRANSCRIPT_CONFIDENCE in draft.ambiguities)
    }

    @Test
    fun resultIsDeterministicForSameInputAndConfiguration() {
        val first = parser.parse("negative 12.25 microamps at TP17", 0.92)
        repeat(100) { assertEquals(first, parser.parse("negative 12.25 microamps at TP17", 0.92)) }
    }

    private fun MeasurementParseResult.parsed() = (this as MeasurementParseResult.Parsed).draft
    private fun MeasurementParseResult.incomplete() = this as MeasurementParseResult.Incomplete
    private fun MeasurementCandidate.scalar() = (value as MeasurementValue.Scalar).value
    private fun MeasurementCandidate.normalizedScalar() =
        (normalizedSiValue as MeasurementValue.Scalar).value

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }
}
