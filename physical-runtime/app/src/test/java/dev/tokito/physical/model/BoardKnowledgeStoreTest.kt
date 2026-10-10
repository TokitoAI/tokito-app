package dev.tokito.physical.model

import android.database.sqlite.SQLiteDatabase
import dev.tokito.physical.dialogue.BoardKnowledgeStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class BoardKnowledgeStoreTest {
    private val corpus = "1".repeat(64)
    private val encoder = "2".repeat(64)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun database(): File {
        val file = File.createTempFile("knowledge-", ".sqlite", RuntimeEnvironment.getApplication().cacheDir)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,board_id TEXT NOT NULL,component_id TEXT NOT NULL,source_json TEXT NOT NULL,vector BLOB NOT NULL,vector_sha256 TEXT NOT NULL)")
            mapOf("corpus_sha256" to JSONObject.quote(corpus), "encoder_model_sha256" to JSONObject.quote(encoder),
                "dataset_commit" to JSONObject.quote("a".repeat(40)), "dimensions" to "384").forEach { (key, value) ->
                db.execSQL("INSERT INTO metadata VALUES(?,?)", arrayOf(key, value))
            }
            for (board in listOf("board-a", "board-b")) {
                val source = JSONObject().put("record_id", "$board:U1:source-0").put("board_id", board)
                    .put("component_id", "$board:U1").put("reference_designator", "U1")
                    .put("authority", "source_design_not_observed_component").put("physical_identity_proven", false)
                    .put("ar_node_id", JSONObject.NULL).put("facts", JSONObject().put("description", "Source controller"))
                val vector = ByteBuffer.allocate(384 * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.putFloat(1f) }.array()
                db.execSQL("INSERT INTO records VALUES(?,?,?,?,?,?)", arrayOf("$board:U1:source-0", board, "$board:U1", source.toString(), vector, hash(vector)))
            }
        }
        return file
    }

    @Test fun retrievalCannotCrossBoardOrManufacturePhysicalAuthority() {
        val file = database()
        try {
            BoardKnowledgeStore(file, hash(file.readBytes()), corpus, encoder).use { store ->
                val found = store.retrieve("board-a", FloatArray(384).also { it[0] = 1f })
                assertEquals(listOf("board-a:U1"), found.map { it.componentId })
                assertEquals(1.0, found.single().similarity, 0.00001)
                assertTrue(store.retrieve("unknown-board", FloatArray(384).also { it[0] = 1f }).isEmpty())
                val context = JSONObject(store.context(found, "board-a"))
                assertEquals(corpus, context.getString("corpus_sha256"))
                assertFalse(context.getJSONArray("records").getJSONObject(0).getBoolean("physical_identity_proven"))
                assertThrows(IllegalArgumentException::class.java) { store.context(found, "board-b") }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun changedArtifactOrEncoderIsRejectedBeforeRetrieval() {
        val file = database()
        try {
            assertThrows(IllegalArgumentException::class.java) { BoardKnowledgeStore(file, "0".repeat(64), corpus, encoder) }
            assertThrows(IllegalArgumentException::class.java) { BoardKnowledgeStore(file, hash(file.readBytes()), corpus, "3".repeat(64)) }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun zeroQueryOrCorruptVectorCannotBecomeEvidence() {
        val file = database()
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("UPDATE records SET vector=X'00' WHERE board_id='board-a'")
            }
            BoardKnowledgeStore(file, hash(file.readBytes()), corpus, encoder).use { store ->
                assertThrows(IllegalArgumentException::class.java) { store.retrieve("board-a", FloatArray(384)) }
                assertThrows(IllegalArgumentException::class.java) { store.retrieve("board-a", FloatArray(384).also { it[0] = 1f }) }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
