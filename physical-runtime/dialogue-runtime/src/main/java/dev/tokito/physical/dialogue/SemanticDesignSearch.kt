package dev.tokito.physical.dialogue

import java.security.MessageDigest
import kotlin.math.sqrt

data class SemanticDesignMatch(val recordId: String, val similarity: Double)

/** Embeddings rank admitted records; scores never confer factual authority. */
interface DesignSemanticSearch {
    val contextRevision: String
    fun search(query: String): List<SemanticDesignMatch>
}

interface DesignVectorCache {
    fun load(identity: String, dimensions: Int, recordIds: List<String>): List<FloatArray>?
    fun replace(identity: String, dimensions: Int, recordIds: List<String>, vectors: List<FloatArray>)
}

class SemanticDesignSearch(
    override val contextRevision: String,
    records: List<DesignContextRecord>,
    private val encoder: TextEmbeddingEncoder,
    cache: DesignVectorCache,
    private val minimumSimilarity: Double = 0.60,
    private val minimumMargin: Double = 0.08,
    private val checkpoint: () -> Unit = {},
) : DesignSemanticSearch {
    private val ids: List<String>
    private val vectors: List<FloatArray>
    // A single last-query slot avoids retaining private conversation history.
    private var previousQuery: String? = null
    private var previousMatches: List<SemanticDesignMatch> = emptyList()

    init {
        require(contextRevision.isNotBlank())
        require(records.size in 1..4096 && records.map { it.recordId }.distinct().size == records.size)
        require(encoder.dimensions in 1..1024 && encoder.modelIdentity.isNotBlank())
        require(minimumSimilarity in 0.0..1.0 && minimumMargin in 0.0..1.0)
        val ordered = records.sortedBy { it.recordId }
        ids = ordered.map { it.recordId }
        val identity = digest(buildString {
            append(contextRevision).append('\u0000').append(encoder.modelIdentity).append('\u0000')
            ordered.forEach { record ->
                append(record.recordId).append('\u0000').append(record.text).append('\u0000')
                append(record.authority.wireValue).append('\u0000')
                listOf(record.sourceIds.sorted(), record.exactKeys.sorted(), record.spokenAliases.sorted()).forEach { values ->
                    append(values.size).append('\u0000')
                    values.forEach { append(it.length).append(':').append(it).append('\u0000') }
                }
                append(record.evidenceLocators.size).append('\u0000')
                record.evidenceLocators.forEach { locator ->
                    append(locator.sourceId.length).append(':').append(locator.sourceId).append('\u0000')
                    append(locator.page).append('\u0000').append(locator.locator.length).append(':').append(locator.locator).append('\u0000')
                }
            }
        })
        checkpoint()
        val cached = cache.load(identity, encoder.dimensions, ids)
        vectors = if (cached != null) {
            require(cached.size == ids.size)
            cached.map { normalized(it, encoder.dimensions) }
        } else {
            ordered.map { checkpoint(); normalized(encoder.embed(it.text), encoder.dimensions) }.also {
                checkpoint()
                cache.replace(identity, encoder.dimensions, ids, it)
            }
        }
    }

    @Synchronized
    override fun search(query: String): List<SemanticDesignMatch> {
        checkpoint()
        require(query.isNotBlank() && query.length <= 1000 && query.none(Char::isISOControl))
        if (query == previousQuery) return previousMatches.toList()
        val vector = normalized(encoder.embed(query), encoder.dimensions)
        checkpoint()
        val ranked = ids.indices.map { index ->
            SemanticDesignMatch(ids[index], vector.indices.sumOf { vector[it].toDouble() * vectors[index][it] })
        }.sortedWith(compareByDescending<SemanticDesignMatch> { it.similarity }.thenBy { it.recordId })
        val best = ranked.first()
        // Similarity is not calibrated confidence. Ambiguous retrieval abstains.
        val admitted = if (best.similarity < minimumSimilarity ||
            ranked.getOrNull(1)?.let { best.similarity - it.similarity < minimumMargin } == true
        ) emptyList() else listOf(best)
        previousQuery = query
        previousMatches = admitted
        return admitted.toList()
    }

    companion object {
        internal fun normalized(vector: FloatArray, dimensions: Int): FloatArray {
            require(vector.size == dimensions && vector.all(Float::isFinite)) { "invalid embedding vector" }
            val norm = sqrt(vector.sumOf { it.toDouble() * it })
            require(norm.isFinite() && norm > 1e-12) { "embedding has no direction" }
            return FloatArray(dimensions) { (vector[it] / norm).toFloat() }
        }

        internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
