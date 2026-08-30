package dev.tokito.physical.measurement

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class MeasurementParserConfig(
    val decimalConvention: DecimalConvention = DecimalConvention.DOT,
    val minimumTranscriptConfidence: Double = 0.85,
    val requireRelationship: Boolean = false,
    val maximumAbsoluteSiValue: BigDecimal = BigDecimal("1e15"),
) {
    init {
        require(minimumTranscriptConfidence.isFinite() && minimumTranscriptConfidence in 0.0..1.0)
        require(maximumAbsoluteSiValue > BigDecimal.ZERO)
    }
}

class DeterministicMeasurementParser(private val config: MeasurementParserConfig = MeasurementParserConfig()) {
    fun parse(transcript: String, transcriptConfidence: Double = 1.0): MeasurementParseResult {
        val original = transcript.trim()
        if (original.isEmpty()) return incomplete(original, IncompleteReason.EMPTY_TRANSCRIPT, "Please repeat the measurement.")
        if (original.length > MAX_TRANSCRIPT_CHARACTERS) {
            return incomplete(original, IncompleteReason.OVERFLOW, "The transcript exceeded the bounded parser input.")
        }
        if (!transcriptConfidence.isFinite() || transcriptConfidence !in 0.0..1.0) {
            return incomplete(original, IncompleteReason.MISSING_VALUE, "The transcript confidence was invalid. Please repeat the measurement.")
        }
        if (SCIENTIFIC_NOTATION.containsMatchIn(original)) {
            return incomplete(original, IncompleteReason.OVERFLOW, "Please state the value without scientific notation.")
        }

        val units = findUnits(original)
        if (units.isEmpty()) {
            val hasValue = NUMERIC_LITERAL.containsMatchIn(original) || findWordNumber(original, original.length) != null
            if (hasValue && UNSUPPORTED_UNIT_WORDS.containsMatchIn(original)) {
                return incomplete(
                    original,
                    IncompleteReason.UNSUPPORTED_UNIT,
                    "That unit is not supported for an electrical bench measurement.",
                )
            }
            return if (hasValue) incomplete(
                original,
                IncompleteReason.MISSING_UNIT,
                "Please repeat the value with its electrical unit.",
            ) else incomplete(original, IncompleteReason.MISSING_VALUE, "Please repeat the numeric value and unit.")
        }

        val candidates = mutableListOf<ParsedCandidate>()
        for (unit in units) {
            parseCandidate(original, unit, transcriptConfidence)?.let(candidates::add)
        }
        if (candidates.isEmpty()) {
            return incomplete(original, IncompleteReason.MISSING_VALUE, "A unit was heard, but no numeric value was found. Please repeat both.")
        }
        if (candidates.map { it.candidate.unit.quantity }.distinct().size > 1) {
            return incomplete(
                original,
                IncompleteReason.CONTRADICTORY_UNIT,
                "More than one measurement quantity was heard. Please state one reading at a time.",
            )
        }
        if (candidates.any { exceedsBound(it.candidate) }) {
            return incomplete(original, IncompleteReason.OVERFLOW, "The normalized value is outside the supported numeric range.")
        }

        val primaryParsed = candidates.last()
        val ambiguities = linkedSetOf<AmbiguityReason>()
        ambiguities += primaryParsed.ambiguities
        if (transcriptConfidence < config.minimumTranscriptConfidence) ambiguities += AmbiguityReason.LOW_TRANSCRIPT_CONFIDENCE
        if (config.requireRelationship && primaryParsed.candidate.relationship == null) ambiguities += AmbiguityReason.MISSING_RELATIONSHIP
        if (candidates.size > 1) {
            ambiguities += if (CORRECTION_MARKER.containsMatchIn(original)) {
                AmbiguityReason.CORRECTION
            } else {
                AmbiguityReason.MULTIPLE_VALUES
            }
        }
        return MeasurementParseResult.Parsed(
            MeasurementDraft(
                originalTranscript = original,
                primary = primaryParsed.candidate,
                alternatives = candidates.dropLast(1).map { it.candidate }.asReversed(),
                ambiguities = ambiguities,
            ),
        )
    }

    private fun parseCandidate(text: String, unitMatch: UnitMatch, transcriptConfidence: Double): ParsedCandidate? {
        val numericMatches = NUMERIC_LITERAL.findAll(text.substring(0, unitMatch.start)).toList()
            .filterNot { match -> isReferenceNumber(text, match.range.first) }
        val relationship = parseRelationship(text)
        val last = numericMatches.lastOrNull()
        if (last != null) {
            val secondLast = numericMatches.dropLast(1).lastOrNull()
            val range = secondLast?.takeIf {
                val separator = text.substring(it.range.last + 1, last.range.first)
                RANGE_SEPARATOR.matches(separator)
            }
            val firstValue = normalizeNumber((range ?: last).value) ?: return null
            val lastValue = normalizeNumber(last.value) ?: return null
            val value = if (range != null) {
                val low = minOf(firstValue.value, lastValue.value)
                val high = maxOf(firstValue.value, lastValue.value)
                MeasurementValue.Range(low, high)
            } else {
                MeasurementValue.Scalar(lastValue.value)
            }
            val start = (range ?: last).range.first
            val ambiguities = linkedSetOf<AmbiguityReason>()
            if (firstValue.separatorAmbiguous || lastValue.separatorAmbiguous) ambiguities += AmbiguityReason.DECIMAL_SEPARATOR
            if (range != null) ambiguities += AmbiguityReason.RANGE_STATEMENT
            return ParsedCandidate(
                MeasurementCandidate(
                    value = value,
                    unit = unitMatch.unit,
                    relationship = relationship,
                    sourceSpan = SourceSpan(start, unitMatch.end, text.substring(start, unitMatch.end)),
                    confidence = adjustedConfidence(transcriptConfidence, 0.99, ambiguities.size),
                ),
                ambiguities,
            )
        }

        val words = findWordNumber(text, unitMatch.start) ?: return null
        return ParsedCandidate(
            MeasurementCandidate(
                value = MeasurementValue.Scalar(words.value),
                unit = unitMatch.unit,
                relationship = relationship,
                sourceSpan = SourceSpan(words.start, unitMatch.end, text.substring(words.start, unitMatch.end)),
                confidence = adjustedConfidence(transcriptConfidence, 0.94, 0),
            ),
            emptySet(),
        )
    }

    private fun normalizeNumber(raw: String): NormalizedNumber? {
        if (raw.length > 64) return null
        val negative = raw.startsWith('-')
        val positive = raw.startsWith('+')
        val unsigned = raw.removePrefix("-").removePrefix("+")
        if (unsigned.isBlank() || unsigned.any { !it.isDigit() && it != '.' && it != ',' }) return null
        val decimal = if (config.decimalConvention == DecimalConvention.DOT) '.' else ','
        val grouping = if (decimal == '.') ',' else '.'
        val decimalCount = unsigned.count { it == decimal }
        val groupingCount = unsigned.count { it == grouping }
        var ambiguous = false
        val normalized = when {
            decimalCount > 1 -> return null
            decimalCount == 1 -> {
                val parts = unsigned.split(decimal)
                if (parts.size != 2 || parts[1].isEmpty()) return null
                val integer = normalizeGrouping(parts[0], grouping) ?: return null
                "$integer.${parts[1]}"
            }
            groupingCount > 0 && isValidGrouping(unsigned, grouping) -> unsigned.replace(grouping.toString(), "")
            groupingCount == 1 -> {
                ambiguous = true
                unsigned.replace(grouping, '.')
            }
            groupingCount > 1 -> return null
            else -> unsigned
        }
        if (normalized.length > 48) return null
        return runCatching {
            val sign = if (negative) "-" else if (positive) "+" else ""
            NormalizedNumber(BigDecimal(sign + normalized, MATH_CONTEXT).stripTrailingZeros(), ambiguous)
        }.getOrNull()
    }

    private fun normalizeGrouping(integer: String, grouping: Char): String? = when {
        grouping !in integer -> integer
        isValidGrouping(integer, grouping) -> integer.replace(grouping.toString(), "")
        else -> null
    }

    private fun isValidGrouping(value: String, separator: Char): Boolean {
        val parts = value.split(separator)
        return parts.size > 1 && parts.first().length in 1..3 && parts.first().all(Char::isDigit) &&
            parts.drop(1).all { it.length == 3 && it.all(Char::isDigit) }
    }

    private fun findWordNumber(text: String, endExclusive: Int): WordNumber? {
        val tokens = WORD.findAll(text.substring(0, endExclusive)).toList()
        if (tokens.isEmpty()) return null
        val selected = mutableListOf<MatchResult>()
        for (token in tokens.asReversed()) {
            val word = token.value.lowercase()
            if (word !in NUMBER_WORDS && word !in NUMBER_CONTROL_WORDS) {
                if (selected.isNotEmpty()) break
                continue
            }
            if (selected.isNotEmpty()) {
                val gap = text.substring(token.range.last + 1, selected.last().range.first)
                if (!WORD_GAP.matches(gap)) break
            }
            selected += token
        }
        if (selected.isEmpty()) return null
        val ordered = selected.asReversed()
        if (ordered.none { it.value.lowercase() in NUMBER_WORDS }) return null
        val value = runCatching { wordsToDecimal(ordered.map { it.value.lowercase() }) }.getOrNull() ?: return null
        return WordNumber(value, ordered.first().range.first)
    }

    private fun wordsToDecimal(words: List<String>): BigDecimal? {
        var negative = false
        var decimal = false
        val decimals = StringBuilder()
        var current = 0L
        var total = 0L
        for (word in words) {
            when {
                word == "negative" || word == "minus" -> if (current == 0L && total == 0L && decimals.isEmpty()) negative = true else return null
                word == "and" -> Unit
                word == "point" || word == "dot" -> if (!decimal) decimal = true else return null
                decimal -> {
                    val digit = SMALL_NUMBERS[word]?.takeIf { it in 0..9 } ?: return null
                    decimals.append(digit)
                }
                word == "hundred" -> current = (if (current == 0L) 1L else current) * 100L
                word == "thousand" -> {
                    total = Math.addExact(total, Math.multiplyExact(if (current == 0L) 1L else current, 1_000L))
                    current = 0L
                }
                word in TENS -> current = Math.addExact(current, checkNotNull(TENS[word]).toLong())
                word in SMALL_NUMBERS -> current = Math.addExact(current, checkNotNull(SMALL_NUMBERS[word]).toLong())
                else -> return null
            }
        }
        if (decimal && decimals.isEmpty()) return null
        val integer = Math.addExact(total, current)
        val raw = buildString {
            if (negative) append('-')
            append(integer)
            if (decimals.isNotEmpty()) append('.').append(decimals)
        }
        return runCatching { BigDecimal(raw, MATH_CONTEXT).stripTrailingZeros() }.getOrNull()
    }

    private fun parseRelationship(text: String): MeasurementRelationship? {
        BETWEEN_RELATIONSHIP.find(text)?.let { match ->
            return MeasurementRelationship(
                RelationshipKind.BETWEEN,
                normalizeNode(match.groupValues[1]),
                normalizeNode(match.groupValues[2]),
            )
        }
        ACROSS_RELATIONSHIP.find(text)?.let { match ->
            return MeasurementRelationship(RelationshipKind.ACROSS, normalizeNode(match.groupValues[1]))
        }
        AT_RELATIONSHIP.find(text)?.let { match ->
            return MeasurementRelationship(RelationshipKind.AT, normalizeNode(match.groupValues[1]))
        }
        return null
    }

    private fun normalizeNode(raw: String): String = if (raw.equals("ground", true) || raw.equals("gnd", true)) "GND" else raw.uppercase()

    private fun findUnits(text: String): List<UnitMatch> {
        val found = UNIT_PATTERNS.flatMap { alias ->
            alias.pattern.findAll(text).map { UnitMatch(it.range.first, it.range.last + 1, alias.unit) }.toList()
        }.sortedWith(compareBy<UnitMatch> { it.start }.thenByDescending { it.end - it.start })
        val accepted = mutableListOf<UnitMatch>()
        for (candidate in found) {
            if (accepted.none { candidate.start < it.end && candidate.end > it.start }) accepted += candidate
        }
        return accepted.sortedBy { it.start }
    }

    private fun isReferenceNumber(text: String, start: Int): Boolean = start > 0 && text[start - 1].isLetter()

    private fun exceedsBound(candidate: MeasurementCandidate): Boolean {
        fun exceeds(value: BigDecimal) = value.abs() > config.maximumAbsoluteSiValue
        return when (val normalized = candidate.normalizedSiValue) {
            is MeasurementValue.Scalar -> exceeds(normalized.value)
            is MeasurementValue.Range -> exceeds(normalized.low) || exceeds(normalized.high)
        }
    }

    private fun adjustedConfidence(transcript: Double, parser: Double, ambiguities: Int): Double =
        minOf(transcript, parser) * (1.0 - ambiguities * 0.08).coerceAtLeast(0.5)

    private fun incomplete(original: String, reason: IncompleteReason, prompt: String) =
        MeasurementParseResult.Incomplete(original, reason, prompt)

    private data class UnitAlias(val pattern: Regex, val unit: MeasurementUnit)
    private data class UnitMatch(val start: Int, val end: Int, val unit: MeasurementUnit)
    private data class NormalizedNumber(val value: BigDecimal, val separatorAmbiguous: Boolean)
    private data class WordNumber(val value: BigDecimal, val start: Int)
    private data class ParsedCandidate(val candidate: MeasurementCandidate, val ambiguities: Set<AmbiguityReason>)

    companion object {
        private val MATH_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
        private const val MAX_TRANSCRIPT_CHARACTERS = 4_096
        private val NUMERIC_LITERAL = Regex("[-+]?\\d[\\d.,]*")
        private val SCIENTIFIC_NOTATION = Regex("(?i)(?<![a-z0-9])[-+]?\\d+(?:[.,]\\d+)?\\s*[eE]\\s*[-+]?\\d+")
        private val RANGE_SEPARATOR = Regex("(?i)\\s*(?:to|through|until|[-–—])\\s*")
        private val CORRECTION_MARKER = Regex("(?i)\\b(?:no,?\\s+sorry|sorry|correction|rather|actually|I mean)\\b")
        private val UNSUPPORTED_UNIT_WORDS = Regex("(?i)\\b(?:celsius|fahrenheit|degrees?|rpm|decibels?|dB|seconds?|minutes?)\\b")
        private val WORD = Regex("[A-Za-z]+")
        private val WORD_GAP = Regex("[\\s-]*")
        private val SMALL_NUMBERS = mapOf(
            "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
            "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        )
        private val NUMBER_WORDS = SMALL_NUMBERS.keys + TENS.keys
        private val NUMBER_CONTROL_WORDS = setOf("negative", "minus", "point", "dot", "hundred", "thousand", "and")
        private val NODE = "(ground|gnd|[A-Za-z]{1,5}\\d{1,5})"
        private val BETWEEN_RELATIONSHIP = Regex("(?i)(?:from\\s+)?$NODE\\s+(?:to|and)\\s+$NODE")
        private val ACROSS_RELATIONSHIP = Regex("(?i)\\bacross\\s+$NODE")
        private val AT_RELATIONSHIP = Regex("(?i)\\bat\\s+$NODE")

        private fun words(unit: MeasurementUnit, pattern: String) = UnitAlias(
            Regex("(?i)(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])"),
            unit,
        )

        private fun exact(unit: MeasurementUnit, pattern: String) = UnitAlias(
            Regex("(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])"),
            unit,
        )

        private val UNIT_PATTERNS = listOf(
            exact(MeasurementUnit.MEGAOHM, "MΩ"),
            exact(MeasurementUnit.MILLIOHM, "mΩ"),
            words(MeasurementUnit.MICROVOLT, "micro[ -]?volts?|uV|µV"),
            words(MeasurementUnit.MILLIVOLT, "milli[ -]?volts?|mV"),
            words(MeasurementUnit.KILOVOLT, "kilo[ -]?volts?|kV"),
            words(MeasurementUnit.VOLT, "volts?|V"),
            words(MeasurementUnit.MICROAMPERE, "micro[ -]?(?:amps?|amperes?)|uA|µA"),
            words(MeasurementUnit.MILLIAMPERE, "milli[ -]?(?:amps?|amperes?)|mA"),
            words(MeasurementUnit.AMPERE, "amps?|amperes?|A"),
            words(MeasurementUnit.MILLIOHM, "milli[ -]?ohms?"),
            words(MeasurementUnit.KILOOHM, "kilo[ -]?ohms?|kΩ"),
            words(MeasurementUnit.MEGAOHM, "mega[ -]?ohms?"),
            words(MeasurementUnit.OHM, "ohms?|Ω"),
            words(MeasurementUnit.PICOFARAD, "pico[ -]?farads?|pF"),
            words(MeasurementUnit.NANOFARAD, "nano[ -]?farads?|nF"),
            words(MeasurementUnit.MICROFARAD, "micro[ -]?farads?|uF|µF"),
            words(MeasurementUnit.MILLIFARAD, "milli[ -]?farads?|mF"),
            words(MeasurementUnit.FARAD, "farads?|F"),
            words(MeasurementUnit.MICROHENRY, "micro[ -]?henr(?:y|ies)|uH|µH"),
            words(MeasurementUnit.MILLIHENRY, "milli[ -]?henr(?:y|ies)|mH"),
            words(MeasurementUnit.HENRY, "henr(?:y|ies)|H"),
            words(MeasurementUnit.KILOHERTZ, "kilo[ -]?hertz|kHz"),
            words(MeasurementUnit.MEGAHERTZ, "mega[ -]?hertz|MHz"),
            words(MeasurementUnit.HERTZ, "hertz|Hz"),
            words(MeasurementUnit.MILLIWATT, "milli[ -]?watts?|mW"),
            words(MeasurementUnit.WATT, "watts?|W"),
        )
    }
}
