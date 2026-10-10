package dev.tokito.physical.dialogue

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Immutable, integrity-admitted source corpus. Retrieval is not physical identity. */
class BoardKnowledgeStore(
    file: File,
    expectedDatabaseSha256: String,
    private val expectedCorpusSha256: String,
    val expectedEncoderSha256: String,
) : AutoCloseable {
    private val database: SQLiteDatabase
    val datasetCommit: String
    val corpusRevision: String get() = expectedCorpusSha256

    init {
        require(listOf(expectedDatabaseSha256, expectedCorpusSha256, expectedEncoderSha256)
            .all { it.matches(Regex("[a-f0-9]{64}")) })
        require(file.isFile && file.length() in 1..64L * 1024 * 1024)
        require(listOf("-wal", "-shm", "-journal").none { File(file.path + it).exists() })
        require(file.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            hex(digest.digest()) == expectedDatabaseSha256
        }) { "Knowledge database integrity mismatch" }
        database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            require(database.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" })
            val metadata = mutableMapOf<String, String>()
            database.rawQuery("SELECT key,value FROM metadata", null).use { rows ->
                while (rows.moveToNext()) {
                    require(metadata.size < 32)
                    metadata[rows.getString(0)] = rows.getString(1)
                }
            }
            fun text(key: String) = org.json.JSONTokener(metadata.getValue(key)).nextValue() as String
            require(text("corpus_sha256") == expectedCorpusSha256)
            require(text("encoder_model_sha256") == expectedEncoderSha256)
            require(metadata["dimensions"] == "384")
            datasetCommit = text("dataset_commit").also { require(it.matches(Regex("[a-f0-9]{40}"))) }
            require(database.rawQuery("SELECT COUNT(*) FROM records", null).use { it.moveToFirst() && it.getInt(0) in 1..16384 })
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    data class Evidence(
        val recordId: String,
        val boardId: String,
        val componentId: String,
        val reference: String,
        val sourceJson: String,
        val similarity: Double,
    )

    /** Board selection is required; ANN/global nearest-neighbour cannot choose a board. */
    @Synchronized
    fun retrieve(boardId: String, query: FloatArray, limit: Int = 6): List<Evidence> {
        require(boardId.isNotBlank() && boardId.length <= 128 && boardId.none(Char::isISOControl))
        require(limit in 1..12)
        val normalized = SemanticDesignSearch.normalized(query, 384)
        val top = mutableListOf<Evidence>()
        database.rawQuery("SELECT id,component_id,source_json,vector,vector_sha256 FROM records WHERE board_id=? ORDER BY id", arrayOf(boardId)).use { rows ->
            var count = 0
            while (rows.moveToNext()) {
                require(++count <= 16384)
                val bytes = rows.getBlob(3)
                require(bytes.size == 384 * 4 && hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == rows.getString(4))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val vector = FloatArray(384) { buffer.float }
                require(vector.all(Float::isFinite))
                val norm = sqrt(vector.sumOf { it.toDouble() * it })
                require(norm in 0.999..1.001)
                val source = rows.getString(2)
                require(source.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
                val record = JSONObject(source)
                require(record.getString("record_id") == rows.getString(0) && record.getString("board_id") == boardId)
                require(record.getString("component_id") == rows.getString(1))
                require(record.getString("authority") == "source_design_not_observed_component")
                require(!record.getBoolean("physical_identity_proven") && record.isNull("ar_node_id"))
                val score = vector.indices.sumOf { vector[it].toDouble() * normalized[it] }
                top.add(Evidence(rows.getString(0), boardId, rows.getString(1), record.getString("reference_designator"), source, score))
                top.sortWith(compareByDescending<Evidence> { it.similarity }.thenBy { it.recordId })
                if (top.size > limit) top.removeAt(top.lastIndex)
            }
        }
        return top.toList()
    }

    /** JSON evidence remains quoted data, never instructions; no silent partial record truncation. */
    fun context(evidence: List<Evidence>, boardId: String, maxBytes: Int = 12_000): String {
        require(maxBytes in 1024..32_000 && evidence.size in 1..12)
        require(evidence.all { it.boardId == boardId } && evidence.map { it.recordId }.distinct().size == evidence.size)
        val records = org.json.JSONArray()
        for (item in evidence) {
            val candidate = JSONObject(item.sourceJson)
            records.put(candidate)
            val envelope = JSONObject().put("corpus_sha256", expectedCorpusSha256).put("dataset_commit", datasetCommit)
                .put("board_id", boardId).put("authority", "source_design_not_observed_component").put("records", records)
            if (envelope.toString().toByteArray(Charsets.UTF_8).size > maxBytes) {
                records.remove(records.length() - 1)
                break
            }
        }
        require(records.length() > 0) { "Source record exceeds context budget" }
        return JSONObject().put("corpus_sha256", expectedCorpusSha256).put("dataset_commit", datasetCommit)
            .put("board_id", boardId).put("authority", "source_design_not_observed_component").put("records", records).toString()
    }

    @Synchronized
    override fun close() = database.close()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
