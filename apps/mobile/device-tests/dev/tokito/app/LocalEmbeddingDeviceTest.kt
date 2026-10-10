package dev.tokito.app

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.dialogue.*
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Executes the signed neural encoder, not fake test vectors or remote inference. */
class LocalEmbeddingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun signedEncoderMatchesReferenceAndReportsDeviceCost() {
        val context = instrumentation.targetContext
        val directory = requireNotNull(ModelBundleRepository(context).activeDirectory(ModelCapability.EMBEDDING)) {
            "Install the signed Knowledge search bundle before device acceptance"
        }
        LocalTextEmbeddingRuntime.verifyConformance(directory)
        val started = SystemClock.elapsedRealtimeNanos()
        LocalTextEmbeddingRuntime.open(directory).use { encoder ->
            val loadMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            val timings = mutableListOf<Long>()
            repeat(20) { index ->
                val before = SystemClock.elapsedRealtimeNanos()
                val vector = encoder.embed(QUERIES[index % QUERIES.size])
                assertEquals(384, vector.size)
                assertTrue(vector.all(Float::isFinite))
                val norm = vector.sumOf { it.toDouble() * it }
                assertEquals(1.0, norm, 0.0001)
                timings += (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putLong("tokito_embedding_load_ms", loadMs)
                putInt("tokito_embedding_iterations", timings.size)
                putLong("tokito_embedding_query_p50_ms", timings.sorted()[timings.size / 2])
                putLong("tokito_embedding_query_max_ms", timings.max())
                putLong("tokito_embedding_process_pss_kib", Debug.getPss())
                putLong("tokito_embedding_native_heap_bytes", Debug.getNativeHeapAllocatedSize())
            })
        }
    }

    @Test fun hybridEvidenceSurvivesSqliteReloadAndRejectsMissingIdentities() {
        val context = instrumentation.targetContext
        val directory = requireNotNull(ModelBundleRepository(context).activeDirectory(ModelCapability.EMBEDDING))
        val file = File(context.cacheDir, "embedding-qa-${UUID.randomUUID()}.sqlite")
        // Synthetic reviewed fixture corpus, not a claim about the user's physical board.
        val records = listOf(
            DesignContextRecord("supply", DesignFactAuthority.DATASHEET, setOf("U1"),
                "The voltage regulator supplies stable power to the microcontroller.", setOf("qa-source-a")),
            DesignContextRecord("antenna", DesignFactAuthority.DATASHEET, setOf("J1"),
                "The antenna connector links the radio transceiver to the external antenna.", setOf("qa-source-b")),
            DesignContextRecord("button", DesignFactAuthority.DATASHEET, setOf("SW1"),
                "The reset button restarts the microcontroller.", setOf("qa-source-c")),
        )
        try {
            LocalTextEmbeddingRuntime.open(directory).use { encoder ->
                val source = LocalDesignContextIndex("device-fixture-rev-1", records)
                val index = SqliteDesignVectorCache(file).use { source.withEmbeddings(encoder, it) }
                assertTrue(requireNotNull(index.retrieve("U1")).contains("qa-source-a"))
                assertNull(index.retrieve("U99 power"))
                assertNull(index.retrieve("pin 999"))
                val before = index.retrieve("Which circuit stabilizes the controller supply?")
                val reloaded = SqliteDesignVectorCache(file).use { source.withEmbeddings(encoder, it) }
                assertEquals(before, reloaded.retrieve("Which circuit stabilizes the controller supply?"))
                // Assert independent semantic retrieval, not merely the lexical route.
                val search = SqliteDesignVectorCache(file).use {
                    SemanticDesignSearch("device-fixture-rev-1", records, encoder, it)
                }
                val match = search.search("voltage regulator for microcontroller power")
                assertEquals("supply", match.single().recordId)
            }
        } finally {
            // Delete only this test's unique scratch DB, never admitted model/project state.
            file.delete()
            File(file.path + "-journal").delete()
        }
    }

    private companion object {
        val QUERIES = listOf("Which device stabilizes the power supply?", "Where does the antenna attach?",
            "What restarts the controller?", "What is the saved value of R1?", "Show the programming connector.")
    }
}
