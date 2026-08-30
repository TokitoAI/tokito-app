package dev.tokito.physical.dialogue

import org.json.JSONArray
import org.json.JSONObject

enum class DesignFactAuthority(val wireValue: String) {
    AUTHORED_PLACEMENT("authored_placement"),
    DATASHEET("datasheet"),
    SUPPORTING("supporting"),
}

data class DesignContextRecord(
    val recordId: String,
    val authority: DesignFactAuthority,
    val exactKeys: Set<String>,
    val text: String,
    val sourceIds: Set<String>,
    val spokenAliases: Set<String> = emptySet(),
    val spokenSummary: String = text,
    val detailAnswers: List<DesignContextAnswer> = emptyList(),
    val boardTarget: DesignBoardTarget? = null,
)

data class DesignBoardTarget(
    val label: String,
    val xNormalized: Double,
    val yNormalized: Double,
)

data class DesignContextAnswer(
    val requiredTokens: Set<String>,
    val text: String,
)

data class GroundedDesignAnswer(
    val fallbackText: String,
    val designContext: String,
    val requiredAnchors: Set<String>,
    val requiredResponseAnchors: Set<String>,
    val allowedNumbers: Set<String>,
    val boardTarget: DesignBoardTarget?,
)

data class DesignContextIndexLimits(
    val maxRecords: Int = 6,
    val maxContextChars: Int = 4_000,
    val maxRecordChars: Int = 1_500,
) {
    init {
        require(maxRecords > 0)
        require(maxContextChars > 0)
        require(maxRecordChars in 1..maxContextChars)
    }
}

/** Bounded local retrieval over facts emitted by a verified assembly context. */
class LocalDesignContextIndex(
    private val contextRevision: String,
    records: List<DesignContextRecord>,
    private val limits: DesignContextIndexLimits = DesignContextIndexLimits(),
) {
    private val records = records.toList()

    init {
        requireCanonical(contextRevision, "context revision")
        require(this.records.map { it.recordId }.distinct().size == this.records.size) {
            "record IDs must be unique"
        }
        this.records.forEach { record ->
            requireCanonical(record.recordId, "record ID")
            require(record.text.length <= limits.maxRecordChars) { "record exceeds its text limit" }
            requireCanonical(record.text, "record text")
            require(record.exactKeys.all(::isCanonicalKey)) { "exact keys must be canonical" }
            require(record.sourceIds.isNotEmpty()) { "record must retain source evidence" }
            record.sourceIds.forEach { requireCanonical(it, "source ID") }
            requireCanonical(record.spokenSummary, "spoken summary")
            require(record.spokenSummary.length <= limits.maxRecordChars) { "spoken summary exceeds its text limit" }
            record.spokenAliases.forEach { requireCanonical(it, "spoken alias") }
            record.detailAnswers.forEach { answer ->
                require(answer.requiredTokens.isNotEmpty()) { "detail answer must declare required tokens" }
                answer.requiredTokens.forEach { requireCanonical(it, "detail answer token") }
                requireCanonical(answer.text, "detail answer")
                require(answer.text.length <= limits.maxRecordChars) { "detail answer exceeds its text limit" }
            }
            record.boardTarget?.let { target ->
                requireCanonical(target.label, "board target label")
                require(target.xNormalized in 0.0..1.0 && target.yNormalized in 0.0..1.0) {
                    "board target must use normalized coordinates"
                }
            }
        }
    }

    fun retrieve(query: String): String? {
        requireCanonical(query, "query")
        val ranked = rank(query)

        if (ranked.isEmpty()) return null
        val selected = JSONArray()
        for (candidate in ranked.take(limits.maxRecords)) {
            val encoded = encode(candidate.record)
            selected.put(encoded)
            if (envelope(selected).length > limits.maxContextChars) {
                selected.remove(selected.length() - 1)
                break
            }
        }
        return selected.takeIf { it.length() > 0 }?.let(::envelope)
    }

    /** Returns only text constructed from the admitted record, never generated prose. */
    fun answer(query: String): String? {
        requireCanonical(query, "query")
        val queryTokens = tokenize(query)
        val record = rank(query).firstOrNull()?.record ?: return null
        return record.detailAnswers
            .filter { answer -> answer.requiredTokens.all { it.lowercase() in queryTokens } }
            .maxByOrNull { it.requiredTokens.size }
            ?.text
            ?: record.spokenSummary
    }

    fun groundedAnswer(query: String): GroundedDesignAnswer? {
        requireCanonical(query, "query")
        val queryTokens = tokenize(query)
        val matchedRecords = rank(query).take(limits.maxRecords).map(RankedRecord::record)
        val record = matchedRecords.firstOrNull() ?: return null
        val selectedDetail = record.detailAnswers
            .filter { answer -> answer.requiredTokens.all { it.lowercase() in queryTokens } }
            .maxByOrNull { it.requiredTokens.size }
        val selected = selectedDetail?.text ?: record.spokenSummary
        val anchors = matchedRecords.flatMap { it.exactKeys + it.spokenAliases }
            .filter { it.length >= MIN_ANCHOR_CHARS }
            .toSet()
        val responseAnchors = buildSet {
            addAll(record.exactKeys.filter { selected.contains(it, ignoreCase = true) })
            record.boardTarget?.label?.takeIf { selected.contains(it, ignoreCase = true) }?.let(::add)
        }
        val encoded = JSONArray()
        matchedRecords.forEachIndexed { index, candidate ->
            encoded.put(encode(candidate, if (index == 0) selectedDetail?.text else null))
            if (envelope(encoded).length > limits.maxContextChars) encoded.remove(encoded.length() - 1)
        }
        return GroundedDesignAnswer(
            fallbackText = selected,
            designContext = envelope(encoded),
            requiredAnchors = anchors,
            requiredResponseAnchors = responseAnchors,
            allowedNumbers = NUMBER.findAll(matchedRecords.joinToString(" ") { it.text } + " " + selected)
                .map(MatchResult::value)
                .toSet(),
            boardTarget = record.boardTarget,
        )
    }

    private fun rank(query: String): List<RankedRecord> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val lexical = records.mapNotNull { record ->
            val exact = record.exactKeys.any { it.lowercase() in queryTokens }
            val searchable = tokenize(record.text) +
                record.exactKeys.map { it.lowercase() } +
                record.spokenAliases.flatMap(::tokenize)
            val overlap = queryTokens.count(searchable::contains)
            if (!exact && overlap == 0) null else RankedRecord(record, exact, overlap)
        }.sortedWith(RANKING)
        if (lexical.isNotEmpty()) return lexical

        val queryPhrase = normalizedPhrase(query)
        if (queryPhrase.length < MIN_FUZZY_PHRASE_CHARS) return emptyList()
        val candidates = records.mapNotNull { record ->
            record.spokenAliases
                .map(::normalizedPhrase)
                .filter { it.length >= MIN_FUZZY_PHRASE_CHARS }
                .minOfOrNull { normalizedEditDistance(queryPhrase, it) }
                ?.let { FuzzyRecord(record, it) }
        }.sortedWith(compareBy<FuzzyRecord> { it.distance }.thenBy { it.record.recordId })
        val best = candidates.firstOrNull() ?: return emptyList()
        val runnerUp = candidates.firstOrNull { it.record.recordId != best.record.recordId }
        if (best.distance > MAX_FUZZY_DISTANCE ||
            runnerUp?.let { it.distance - best.distance < MIN_FUZZY_MARGIN } == true
        ) {
            return emptyList()
        }
        return listOf(RankedRecord(best.record, exact = false, overlap = 0))
    }

    private fun envelope(records: JSONArray): String = JSONObject()
        .put("context_revision", contextRevision)
        .put("records", records)
        .toString()

    private fun encode(record: DesignContextRecord, textOverride: String? = null): JSONObject = JSONObject()
        .put("record_id", record.recordId)
        .put("authority", record.authority.wireValue)
        .put("exact_keys", JSONArray(record.exactKeys.sorted()))
        .put("text", textOverride ?: record.text)
        .put("source_ids", JSONArray(record.sourceIds.sorted()))

    private data class RankedRecord(
        val record: DesignContextRecord,
        val exact: Boolean,
        val overlap: Int,
    )

    private data class FuzzyRecord(
        val record: DesignContextRecord,
        val distance: Double,
    )

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        val NUMBER = Regex("(?<![A-Za-z])(?:[0-9]+(?:\\.[0-9]+)?)(?![A-Za-z])")
        val KEY = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        val STOP_WORDS = setOf(
            "a", "about", "an", "and", "are", "board", "component", "do", "does",
            "for", "had", "has", "have", "how", "is", "it", "many", "me", "of", "please",
            "tell", "that", "the", "these", "this", "those", "to", "what", "where", "which",
        )

        fun tokenize(value: String): Set<String> = TOKEN.findAll(normalizeDomainTerms(value))
            .map { it.value.trim('.', '-').lowercase() }
            .filter(String::isNotEmpty)
            .filterNot(STOP_WORDS::contains)
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .map(::singularize)
            .toSet()

        fun isCanonicalKey(value: String): Boolean =
            value == value.trim() && KEY.matches(value) && value == value.uppercase()

        fun requireCanonical(value: String, label: String) {
            require(value.isNotEmpty() && value == value.trim() && value.none(Char::isISOControl)) {
                "$label must be non-empty canonical text"
            }
        }

        val RANKING = compareByDescending<RankedRecord> { it.exact }
            .thenByDescending { it.overlap }
            .thenBy { it.record.recordId }

        const val MIN_FUZZY_PHRASE_CHARS = 4
        const val MIN_ANCHOR_CHARS = 2
        const val MAX_FUZZY_DISTANCE = 0.40
        const val MIN_FUZZY_MARGIN = 0.12

        fun normalizedPhrase(value: String): String = TOKEN.findAll(normalizeDomainTerms(value))
            .map { it.value.trim('.', '-').lowercase() }
            .filter(String::isNotEmpty)
            .filterNot(STOP_WORDS::contains)
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .map(::singularize)
            .joinToString(" ")

        fun normalizeDomainTerms(value: String): String = IO_TERM.replace(value, " io ")

        fun singularize(value: String): String =
            if (value.length > 3 && value.endsWith('s') && !value.endsWith("ss")) value.dropLast(1) else value

        val IO_TERM = Regex("(?i)(?<![A-Za-z0-9])I\\s*/\\s*O(?![A-Za-z0-9])")

        fun normalizedEditDistance(left: String, right: String): Double {
            if (left == right) return 0.0
            if (left.isEmpty() || right.isEmpty()) return 1.0
            var previous = IntArray(right.length + 1) { it }
            for (leftIndex in left.indices) {
                val current = IntArray(right.length + 1)
                current[0] = leftIndex + 1
                for (rightIndex in right.indices) {
                    val substitution = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
                    current[rightIndex + 1] = minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        substitution,
                    )
                }
                previous = current
            }
            return previous[right.length].toDouble() / maxOf(left.length, right.length)
        }
    }
}
