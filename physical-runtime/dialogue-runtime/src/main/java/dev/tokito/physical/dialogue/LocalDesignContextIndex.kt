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
    val rankingAliases: Set<String> = spokenAliases,
    val spokenSummary: String = text,
    val detailAnswers: List<DesignContextAnswer> = emptyList(),
    val boardTarget: DesignBoardTarget? = null,
    val evidenceLocators: List<DesignEvidenceLocator> = emptyList(),
)

data class DesignEvidenceLocator(
    val sourceId: String,
    val page: Int,
    val locator: String,
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
    private val semanticSearch: DesignSemanticSearch? = null,
) {
    private val records = records.map { record -> record.copy(
        exactKeys = record.exactKeys.toSet(), sourceIds = record.sourceIds.toSet(),
        spokenAliases = record.spokenAliases.toSet(), rankingAliases = record.rankingAliases.toSet(),
        detailAnswers = record.detailAnswers.map { it.copy(requiredTokens = it.requiredTokens.toSet()) },
        evidenceLocators = record.evidenceLocators.toList(),
    ) }
    private val indexedRecords: List<IndexedRecord>
    private val tokenPostings: Map<String, List<IndexedRecord>>

    init {
        requireCanonical(contextRevision, "context revision")
        require(semanticSearch == null || semanticSearch.contextRevision == contextRevision) { "semantic context revision mismatch" }
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
            record.rankingAliases.forEach { alias ->
                requireCanonical(alias, "ranking alias")
                require(alias in record.spokenAliases) { "ranking aliases must also be spoken aliases" }
            }
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
            record.evidenceLocators.forEach { evidence ->
                require(evidence.sourceId in record.sourceIds) { "evidence locator must cite record evidence" }
                require(evidence.page > 0) { "evidence page must be positive" }
                requireCanonical(evidence.locator, "evidence locator")
            }
        }
        indexedRecords = this.records.map { record ->
            IndexedRecord(
                record = record,
                searchableTokens = tokenize(record.text) +
                    record.exactKeys.map { it.lowercase() } +
                    record.spokenAliases.flatMap(::tokenize),
                aliasPhrases = record.spokenAliases.map(::normalizedPhrase)
                    .filter { it.length >= MIN_FUZZY_PHRASE_CHARS }
                    .toSet(),
                rankingAliasPhrases = record.rankingAliases.map(::normalizedPhrase)
                    .filter { it.length >= MIN_FUZZY_PHRASE_CHARS }
                    .toSet(),
                rankingAliasTokens = record.rankingAliases.flatMap(::tokenize).toSet(),
            )
        }
        val postings = mutableMapOf<String, MutableList<IndexedRecord>>()
        indexedRecords.forEach { indexed ->
            indexed.searchableTokens.forEach { token ->
                postings.getOrPut(token, ::mutableListOf).add(indexed)
            }
        }
        tokenPostings = postings.mapValues { (_, values) -> values.toList() }
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
        val queryTokens = tokenize(canonicalReferences(query))
        val record = rank(query).firstOrNull()?.record ?: return null
        return record.detailAnswers
            .filter { answer -> answer.requiredTokens.all { it.lowercase() in queryTokens } }
            .maxByOrNull { it.requiredTokens.size }
            ?.text
            ?: record.spokenSummary
    }

    fun groundedAnswer(query: String): GroundedDesignAnswer? {
        requireCanonical(query, "query")
        val canonicalQuery = canonicalReferences(query)
        val queryTokens = tokenize(canonicalQuery)
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
            boardTarget = record.boardTarget.takeIf {
                record.exactKeys.any { key -> key.lowercase() in queryTokens } ||
                    record.rankingAliases.any { alias -> phraseScore(normalizedPhrase(canonicalQuery), normalizedPhrase(alias)) == 2 }
            },
        )
    }

    fun withEmbeddings(encoder: TextEmbeddingEncoder, cache: DesignVectorCache, checkpoint: () -> Unit = {}): LocalDesignContextIndex =
        LocalDesignContextIndex(contextRevision, records, limits,
            SemanticDesignSearch(contextRevision, records, encoder, cache, checkpoint = checkpoint))

    private fun rank(query: String): List<RankedRecord> {
        val canonicalQuery = canonicalReferences(query)
        val lexical = lexicalRank(canonicalQuery)
        // Never turn an unknown reference/pin into a nearby semantic guess.
        val references = REFERENCE_QUERY.findAll(canonicalQuery).map { it.value.uppercase() }.toSet()
        if (references.isNotEmpty()) {
            if (references.any { reference -> records.none { reference in it.exactKeys } }) return emptyList()
            return lexical
        }
        if (PIN_INTENT.containsMatchIn(canonicalQuery)) {
            val tokens = tokenize(canonicalQuery)
            return lexical.filter { candidate -> candidate.exact || candidate.primaryPhrase == 2 ||
                candidate.record.rankingAliases.any { alias ->
                    val aliasTokens = tokenize(alias)
                    aliasTokens.size >= 2 && tokens.containsAll(aliasTokens)
                }
            }
        }
        if (lexical.any { it.exact || it.primaryPhrase == 2 }) return lexical
        val matches = semanticSearch?.search(query).orEmpty()
        if (matches.isEmpty()) return lexical
        val semantic = matches.map { match ->
            val record = records.single { it.recordId == match.recordId }
            RankedRecord(record.copy(boardTarget = null), false,
                authorityScore(record.authority), false, 0, 0, 0, 0)
        }
        return semantic + lexical.filterNot { item -> semantic.any { it.record.recordId == item.record.recordId } }
    }

    private fun lexicalRank(query: String): List<RankedRecord> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val pinNumber = PIN_QUERY.find(query)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        if (pinNumber != null) {
            val pinRecords = indexedRecords.filter { indexed ->
                indexed.record.detailAnswers.any { answer -> answer.requiredTokens.any { it.equals(pinNumber, ignoreCase = true) } }
            }
            val references = REFERENCE_QUERY.findAll(query).map { it.value.uppercase() }.toSet()
            val selected = if (references.isEmpty()) {
                pinRecords.singleOrNull()
            } else {
                pinRecords.singleOrNull { indexed -> references.all { it in indexed.record.exactKeys } }
            } ?: return emptyList()
            return listOf(
                RankedRecord(
                    selected.record,
                    target = false,
                    authority = authorityScore(selected.record.authority),
                    exact = false,
                    primaryPhrase = 2,
                    primaryOverlap = 2,
                    phrase = 2,
                    overlap = 2,
                ),
            )
        }
        // A pin/pad request without an exact admitted detail must not become a
        // component summary through generic word overlap or vector similarity.
        if (SINGULAR_PIN_INTENT.containsMatchIn(query)) return emptyList()

        val candidates = queryTokens.flatMap { tokenPostings[it].orEmpty() }.distinctBy { it.record.recordId }
        val queryPhrase = normalizedPhrase(query)
        val locationIntent = LOCATION_INTENT.containsMatchIn(query)
        val lexical = candidates.mapNotNull { indexed ->
            val record = indexed.record
            val exact = record.exactKeys.any { it.lowercase() in queryTokens }
            val primaryPhrase = indexed.rankingAliasPhrases.maxOfOrNull { alias -> phraseScore(queryPhrase, alias) } ?: 0
            val primaryOverlap = queryTokens.count(indexed.rankingAliasTokens::contains)
            val phrase = indexed.aliasPhrases.maxOfOrNull { alias -> phraseScore(queryPhrase, alias) } ?: 0
            val overlap = queryTokens.count(indexed.searchableTokens::contains)
            if (!exact && phrase == 0 && overlap == 0) {
                null
            } else {
                RankedRecord(
                    record = record,
                    target = locationIntent && record.boardTarget != null,
                    authority = authorityScore(record.authority),
                    exact = exact,
                    primaryPhrase = primaryPhrase,
                    primaryOverlap = primaryOverlap,
                    phrase = phrase,
                    overlap = overlap,
                )
            }
        }.sortedWith(RANKING)
        if (lexical.isNotEmpty()) {
            // Explicit component identity outranks another component's location
            // or alias match. Keep secondary evidence for aggregate questions,
            // and do not mistake generic keys such as USB for a reference.
            val references = REFERENCE_QUERY.findAll(query).map { it.value.uppercase() }.toSet()
            val explicit = lexical.filter { it.record.exactKeys.any(references::contains) }
            if (explicit.isNotEmpty()) return explicit + lexical.filterNot(explicit::contains)
            val targeted = lexical.filter(RankedRecord::target)
            if (targeted.isNotEmpty()) return targeted
            val strongestAliasOverlap = lexical.maxOf(RankedRecord::primaryOverlap)
            if (strongestAliasOverlap > 0) {
                return lexical.filter { it.primaryOverlap == strongestAliasOverlap }
            }
            val strongestPhrase = lexical.maxOf(RankedRecord::primaryPhrase)
            if (strongestPhrase > 0) return lexical.filter { it.primaryPhrase == strongestPhrase }
            return lexical
        }

        if (queryPhrase.length < MIN_FUZZY_PHRASE_CHARS || queryTokens.size != 1) return emptyList()
        val fuzzyCandidates = indexedRecords.mapNotNull { indexed ->
            indexed.rankingAliasPhrases.map { alias ->
                val aliasTokens = tokenize(alias)
                FuzzyRecord(
                    record = indexed.record,
                    distance = aliasTokens.minOf { aliasToken ->
                        normalizedEditDistance(queryTokens.single(), aliasToken)
                    },
                    aliasTokenCount = aliasTokens.size,
                )
            }.minWithOrNull(compareBy<FuzzyRecord> { it.distance }.thenBy { it.aliasTokenCount })
        }.sortedWith(
            compareBy<FuzzyRecord> { it.distance }
                .thenBy { it.aliasTokenCount }
                .thenBy { it.record.recordId },
        )
        val best = fuzzyCandidates.firstOrNull() ?: return emptyList()
        val runnerUp = fuzzyCandidates.firstOrNull { it.record.recordId != best.record.recordId }
        if (best.distance > MAX_FUZZY_DISTANCE ||
            runnerUp?.let {
                it.aliasTokenCount == best.aliasTokenCount &&
                    it.distance - best.distance < MIN_FUZZY_MARGIN
            } == true
        ) {
            return emptyList()
        }
        return listOf(
            RankedRecord(
                best.record,
                target = locationIntent && best.record.boardTarget != null,
                authority = authorityScore(best.record.authority),
                exact = false,
                primaryPhrase = 0,
                primaryOverlap = 0,
                phrase = 1,
                overlap = 0,
            ),
        )
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
        .put(
            "evidence_locators",
            JSONArray(record.evidenceLocators.map { evidence ->
                JSONObject()
                    .put("source_id", evidence.sourceId)
                    .put("page", evidence.page)
                    .put("locator", evidence.locator)
            }),
        )

    private data class IndexedRecord(
        val record: DesignContextRecord,
        val searchableTokens: Set<String>,
        val aliasPhrases: Set<String>,
        val rankingAliasPhrases: Set<String>,
        val rankingAliasTokens: Set<String>,
    )

    private data class RankedRecord(
        val record: DesignContextRecord,
        val target: Boolean,
        val authority: Int,
        val exact: Boolean,
        val primaryPhrase: Int,
        val primaryOverlap: Int,
        val phrase: Int,
        val overlap: Int,
    )

    private data class FuzzyRecord(
        val record: DesignContextRecord,
        val distance: Double,
        val aliasTokenCount: Int,
    )

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        val NUMBER = Regex("(?<![A-Za-z])(?:[0-9]+(?:\\.[0-9]+)?)(?![A-Za-z])")
        val KEY = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        val REFERENCE_QUERY = Regex(
            "(?i)(?<![A-Z0-9])(?:R|C|U|D|Q|L|J|SW|TP|F|FB|Y|X|CN|RN|RV|BT|BH|SJ)[0-9]{1,5}(?![A-Z0-9])",
        )
        val PIN_QUERY = Regex("(?i)\\b(?:pin|pad)(?:\\s+([A-Z0-9][A-Z0-9_.-]{0,63})|([0-9]+))\\b")
        val PIN_INTENT = Regex("(?i)\\b(?:pins?|pads?)\\b")
        val SINGULAR_PIN_INTENT = Regex("(?i)\\b(?:pin|pad)\\b")
        val SPACED_REFERENCE = Regex("(?i)(?<![A-Z0-9])((?:SW|TP|FB|CN|RN|RV|BT|BH|SJ|R|C|U|D|Q|L|J|F|Y|X))\\s+([0-9]{1,5})(?![A-Z0-9])")
        fun canonicalReferences(query: String): String = SPACED_REFERENCE.replace(query) { it.groupValues[1].uppercase() + it.groupValues[2] }
        val LOCATION_INTENT = Regex("(?i)\\b(where|locate|show|highlight|point)\\b")
        val STOP_WORDS = setOf(
            "a", "about", "an", "and", "are", "board", "can", "component", "do", "does",
            "for", "had", "has", "have", "how", "is", "it", "many", "me", "of", "on", "please",
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

        val RANKING = compareByDescending<RankedRecord> { it.target }
            .thenByDescending { it.authority }
            .thenByDescending { it.primaryPhrase }
            .thenByDescending { it.primaryOverlap }
            .thenByDescending { it.exact }
            .thenByDescending { it.phrase }
            .thenByDescending { it.overlap }
            .thenBy { it.record.recordId }

        const val MIN_FUZZY_PHRASE_CHARS = 4
        const val MIN_ANCHOR_CHARS = 2
        const val MAX_FUZZY_DISTANCE = 0.40
        const val MIN_FUZZY_MARGIN = 0.12

        fun authorityScore(authority: DesignFactAuthority): Int = when (authority) {
            DesignFactAuthority.AUTHORED_PLACEMENT -> 3
            DesignFactAuthority.DATASHEET -> 2
            DesignFactAuthority.SUPPORTING -> 1
        }

        fun normalizedPhrase(value: String): String = TOKEN.findAll(normalizeDomainTerms(value))
            .map { it.value.trim('.', '-').lowercase() }
            .filter(String::isNotEmpty)
            .filterNot(STOP_WORDS::contains)
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .map(::singularize)
            .joinToString(" ")

        fun normalizeDomainTerms(value: String): String = PIN_TERM.replace(
            IO_TERM.replace(CAN_TERM.replace(value, " canbus "), " io "),
            " pin $1 ",
        )

        fun singularize(value: String): String =
            if (value.length > 3 && value.endsWith('s') && !value.endsWith("ss")) value.dropLast(1) else value

        val IO_TERM = Regex("(?i)(?<![A-Za-z0-9])I\\s*/\\s*O(?![A-Za-z0-9])")
        val CAN_TERM = Regex("(?<![A-Za-z0-9])CAN(?![A-Za-z0-9])")
        val PIN_TERM = Regex("(?i)\\bpin\\s*([0-9]+)\\b")

        fun phraseScore(query: String, alias: String): Int = when {
            query == alias || query.contains(alias) -> 2
            alias.contains(query) -> 1
            else -> 0
        }

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
