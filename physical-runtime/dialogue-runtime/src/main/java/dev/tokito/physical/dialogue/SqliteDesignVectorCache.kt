package dev.tokito.physical.dialogue

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** One bounded active corpus, atomically replaced; no independent source-of-truth data. */
class SqliteDesignVectorCache(private val file: File) : DesignVectorCache, AutoCloseable {
    private var database: SQLiteDatabase

    init {
        file.parentFile?.mkdirs()
        database = openDatabase()
    }

    private fun openDatabase(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).also {
        try {
            it.execSQL("CREATE TABLE IF NOT EXISTS embedding_meta (slot INTEGER PRIMARY KEY CHECK(slot=1), identity TEXT NOT NULL, dimensions INTEGER NOT NULL)")
            it.execSQL("CREATE TABLE IF NOT EXISTS embedding_vectors (record_id TEXT PRIMARY KEY, vector BLOB NOT NULL, digest TEXT NOT NULL)")
        } catch (failure: Throwable) { it.close(); throw failure }
    }

    @Synchronized
    override fun load(identity: String, dimensions: Int, recordIds: List<String>): List<FloatArray>? {
        require(dimensions in 1..1024 && recordIds.size in 1..4096)
        require(recordIds.distinct().size == recordIds.size)
        return try { read(identity, dimensions, recordIds) }
        catch (_: SQLiteDatabaseCorruptException) {
            database.close()
            check(SQLiteDatabase.deleteDatabase(file)) { "Could not retire corrupt derived cache" }
            database = openDatabase()
            null
        }
    }

    private fun read(identity: String, dimensions: Int, recordIds: List<String>): List<FloatArray>? {
        database.beginTransaction()
        try {
            val matches = database.rawQuery("SELECT identity,dimensions FROM embedding_meta WHERE slot=1", null).use {
                it.moveToFirst() && it.getString(0) == identity && it.getInt(1) == dimensions
            }
            if (!matches) return null
            val loaded = mutableMapOf<String, FloatArray>()
            database.rawQuery("SELECT record_id,vector,digest FROM embedding_vectors", null).use { rows ->
                while (rows.moveToNext()) {
                    require(loaded.size < 4096) { "vector cache exceeds quota" }
                    val bytes = rows.getBlob(1)
                    require(bytes.size == dimensions * 4 && hash(bytes) == rows.getString(2)) { "corrupt vector cache" }
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    loaded[rows.getString(0)] = FloatArray(dimensions) { buffer.float }
                }
            }
            require(loaded.keys == recordIds.toSet()) { "vector cache inventory mismatch" }
            val vectors = recordIds.map { SemanticDesignSearch.normalized(loaded.getValue(it), dimensions) }
            database.setTransactionSuccessful()
            return vectors
        } catch (_: IllegalArgumentException) {
            // This database is derived data, never source evidence. Drop the corrupt
            // projection and let the caller regenerate it from its admitted records
            // and exact encoder identity; never reuse partially validated vectors.
            database.execSQL("DELETE FROM embedding_vectors")
            database.execSQL("DELETE FROM embedding_meta")
            database.setTransactionSuccessful()
            return null
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun replace(identity: String, dimensions: Int, recordIds: List<String>, vectors: List<FloatArray>) {
        require(dimensions in 1..1024 && recordIds.size in 1..4096 && recordIds.size == vectors.size)
        require(recordIds.distinct().size == recordIds.size)
        val validated = vectors.map { SemanticDesignSearch.normalized(it, dimensions) }
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM embedding_vectors")
            recordIds.forEachIndexed { index, id ->
                val buffer = ByteBuffer.allocate(dimensions * 4).order(ByteOrder.LITTLE_ENDIAN)
                validated[index].forEach { buffer.putFloat(it) }
                val bytes = buffer.array()
                database.execSQL("INSERT INTO embedding_vectors(record_id,vector,digest) VALUES(?,?,?)", arrayOf(id, bytes, hash(bytes)))
            }
            database.execSQL("INSERT OR REPLACE INTO embedding_meta(slot,identity,dimensions) VALUES(1,?,?)", arrayOf<Any>(identity, dimensions))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun close() = database.close()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
