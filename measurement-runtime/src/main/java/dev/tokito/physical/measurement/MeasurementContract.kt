package dev.tokito.physical.measurement

import java.math.BigDecimal

enum class DecimalConvention { DOT, COMMA }

enum class MeasurementQuantity { VOLTAGE, CURRENT, RESISTANCE, CAPACITANCE, INDUCTANCE, FREQUENCY, POWER }

enum class MeasurementUnit(
    val quantity: MeasurementQuantity,
    val symbol: String,
    val baseSymbol: String,
    val siMultiplier: BigDecimal,
) {
    VOLT(MeasurementQuantity.VOLTAGE, "V", "V", BigDecimal.ONE),
    MILLIVOLT(MeasurementQuantity.VOLTAGE, "mV", "V", BigDecimal("0.001")),
    MICROVOLT(MeasurementQuantity.VOLTAGE, "µV", "V", BigDecimal("0.000001")),
    KILOVOLT(MeasurementQuantity.VOLTAGE, "kV", "V", BigDecimal("1000")),
    AMPERE(MeasurementQuantity.CURRENT, "A", "A", BigDecimal.ONE),
    MILLIAMPERE(MeasurementQuantity.CURRENT, "mA", "A", BigDecimal("0.001")),
    MICROAMPERE(MeasurementQuantity.CURRENT, "µA", "A", BigDecimal("0.000001")),
    OHM(MeasurementQuantity.RESISTANCE, "Ω", "Ω", BigDecimal.ONE),
    MILLIOHM(MeasurementQuantity.RESISTANCE, "mΩ", "Ω", BigDecimal("0.001")),
    KILOOHM(MeasurementQuantity.RESISTANCE, "kΩ", "Ω", BigDecimal("1000")),
    MEGAOHM(MeasurementQuantity.RESISTANCE, "MΩ", "Ω", BigDecimal("1000000")),
    PICOFARAD(MeasurementQuantity.CAPACITANCE, "pF", "F", BigDecimal("0.000000000001")),
    NANOFARAD(MeasurementQuantity.CAPACITANCE, "nF", "F", BigDecimal("0.000000001")),
    MICROFARAD(MeasurementQuantity.CAPACITANCE, "µF", "F", BigDecimal("0.000001")),
    MILLIFARAD(MeasurementQuantity.CAPACITANCE, "mF", "F", BigDecimal("0.001")),
    FARAD(MeasurementQuantity.CAPACITANCE, "F", "F", BigDecimal.ONE),
    MICROHENRY(MeasurementQuantity.INDUCTANCE, "µH", "H", BigDecimal("0.000001")),
    MILLIHENRY(MeasurementQuantity.INDUCTANCE, "mH", "H", BigDecimal("0.001")),
    HENRY(MeasurementQuantity.INDUCTANCE, "H", "H", BigDecimal.ONE),
    HERTZ(MeasurementQuantity.FREQUENCY, "Hz", "Hz", BigDecimal.ONE),
    KILOHERTZ(MeasurementQuantity.FREQUENCY, "kHz", "Hz", BigDecimal("1000")),
    MEGAHERTZ(MeasurementQuantity.FREQUENCY, "MHz", "Hz", BigDecimal("1000000")),
    MILLIWATT(MeasurementQuantity.POWER, "mW", "W", BigDecimal("0.001")),
    WATT(MeasurementQuantity.POWER, "W", "W", BigDecimal.ONE),
}

data class SourceSpan(val startInclusive: Int, val endExclusive: Int, val text: String) {
    init { require(startInclusive >= 0 && endExclusive >= startInclusive) }
}

enum class RelationshipKind { BETWEEN, ACROSS, AT }

data class MeasurementRelationship(
    val kind: RelationshipKind,
    val primary: String,
    val reference: String? = null,
) {
    init {
        require(primary.isNotBlank())
        require(kind != RelationshipKind.BETWEEN || !reference.isNullOrBlank())
    }
}

sealed interface MeasurementValue {
    data class Scalar(val value: BigDecimal) : MeasurementValue
    data class Range(val low: BigDecimal, val high: BigDecimal) : MeasurementValue {
        init { require(low <= high) }
    }
}

data class MeasurementCandidate(
    val value: MeasurementValue,
    val unit: MeasurementUnit,
    val relationship: MeasurementRelationship?,
    val sourceSpan: SourceSpan,
    val confidence: Double,
) {
    init { require(confidence.isFinite() && confidence in 0.0..1.0) }

    val normalizedSiValue: MeasurementValue = when (value) {
        is MeasurementValue.Scalar -> MeasurementValue.Scalar(value.value.multiply(unit.siMultiplier))
        is MeasurementValue.Range -> MeasurementValue.Range(
            value.low.multiply(unit.siMultiplier),
            value.high.multiply(unit.siMultiplier),
        )
    }
}

enum class AmbiguityReason {
    DECIMAL_SEPARATOR,
    RANGE_STATEMENT,
    CORRECTION,
    MULTIPLE_VALUES,
    LOW_TRANSCRIPT_CONFIDENCE,
    MISSING_RELATIONSHIP,
}

enum class IncompleteReason { EMPTY_TRANSCRIPT, MISSING_VALUE, MISSING_UNIT, UNSUPPORTED_UNIT, CONTRADICTORY_UNIT, OVERFLOW }

data class MeasurementDraft(
    val originalTranscript: String,
    val primary: MeasurementCandidate,
    val alternatives: List<MeasurementCandidate>,
    val ambiguities: Set<AmbiguityReason>,
) {
    val requiresConfirmation: Boolean get() = ambiguities.isNotEmpty()
}

sealed interface MeasurementParseResult {
    data class Parsed(val draft: MeasurementDraft) : MeasurementParseResult
    data class Incomplete(
        val originalTranscript: String,
        val reason: IncompleteReason,
        val recoverablePrompt: String,
    ) : MeasurementParseResult
}

enum class MeasurementSource { ENGINEER_VOICE }

data class ConfirmedMeasurement(
    val draft: MeasurementDraft,
    val confirmedCandidate: MeasurementCandidate,
    val source: MeasurementSource,
    val confirmedAtMonotonicMillis: Long,
) {
    init { require(confirmedAtMonotonicMillis >= 0) }
}

enum class ConfirmationPolicy { ALWAYS, WHEN_AMBIGUOUS }

sealed interface MeasurementTurn {
    data class ConfirmationRequired(val draft: MeasurementDraft, val prompt: String) : MeasurementTurn
    data class Confirmed(val measurement: ConfirmedMeasurement) : MeasurementTurn
    data class CorrectionRequired(val incomplete: MeasurementParseResult.Incomplete) : MeasurementTurn
    data class Rejected(val reason: String) : MeasurementTurn
}
