package dev.tokito.physical.model

import android.database.sqlite.SQLiteDatabase
import dev.tokito.physical.dialogue.SqliteDesignVectorCache
import dev.tokito.physical.dialogue.SemanticDesignSearch
import dev.tokito.physical.dialogue.DesignContextRecord
import dev.tokito.physical.dialogue.DesignFactAuthority
import dev.tokito.physical.dialogue.TextEmbeddingEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SqliteDesignVectorCacheTest {
    @Test fun corruptProjectionReencodesOnlyAdmittedRecordsAndThenReusesCache() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "derived-vector-rebuild.sqlite")
        SQLiteDatabase.deleteDatabase(file)
        var encodes = 0
        val encoder = object : TextEmbeddingEncoder {
            override val modelIdentity = "verified-encoder-digest"
            override val dimensions = 2
            override fun embed(text: String): FloatArray { encodes++; return floatArrayOf(1f, 0f) }
            override fun close() = Unit
        }
        val records = listOf(DesignContextRecord("reviewed", DesignFactAuthority.DATASHEET,
            setOf("R1"), "Reviewed source fact.", setOf("reviewed-source")))
        try {
            SqliteDesignVectorCache(file).use { cache ->
                SemanticDesignSearch("signed-context-revision", records, encoder, cache)
                assertEquals(1, encodes)
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE embedding_vectors SET vector=X'00'")
                }
                SemanticDesignSearch("signed-context-revision", records, encoder, cache)
                assertEquals(2, encodes)
                SemanticDesignSearch("signed-context-revision", records, encoder, cache)
                assertEquals(2, encodes)
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun corruptDigestProjectionIsRetiredAndRebuiltUnderExactIdentity() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "derived-vector-test.sqlite")
        SQLiteDatabase.deleteDatabase(file)
        try {
            SqliteDesignVectorCache(file).use { cache ->
                cache.replace("signed-records@encoder-v1", 2, listOf("r1"), listOf(floatArrayOf(1f, 0f)))
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE embedding_vectors SET digest='corrupt'")
                }
                assertNull(cache.load("signed-records@encoder-v1", 2, listOf("r1")))
                cache.replace("signed-records@encoder-v1", 2, listOf("r1"), listOf(floatArrayOf(0f, 1f)))
                assertArrayEquals(floatArrayOf(0f, 1f), cache.load("signed-records@encoder-v1", 2, listOf("r1"))!![0], 0f)
                assertNull(cache.load("different-signed-records@encoder-v1", 2, listOf("r1")))
                assertNull(cache.load("signed-records@encoder-v2", 2, listOf("r1")))
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun corruptInventoryAndInvalidVectorNeverReachRanking() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "derived-vector-inventory.sqlite")
        SQLiteDatabase.deleteDatabase(file)
        try {
            SqliteDesignVectorCache(file).use { cache ->
                cache.replace("identity", 2, listOf("r1"), listOf(floatArrayOf(1f, 0f)))
                assertNull(cache.load("identity", 2, listOf("r2")))
                assertNull(cache.load("identity", 2, listOf("r1")))
                try { cache.replace("identity", 2, listOf("r1"), listOf(floatArrayOf(Float.NaN, 0f))); fail("Invalid vector admitted") }
                catch (_: IllegalArgumentException) { }
                assertNull(cache.load("identity", 2, listOf("r1")))
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
