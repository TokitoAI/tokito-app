package dev.tokito.physical.dialogue

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Requires the real signed dialogue model in the target app; absence is a failure, not a skipped pass. */
class NativeQwenTokenizerConformanceTest {
    @Test fun actualSignedQnnBinariesExposeComplete4096ContextVariants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.filesDir.resolve("models/dialogue/active/bundle")
        val metadata = JSONObject(root.resolve("metadata.json").readText())
        val config = JSONObject(root.resolve("genie_config.json").readText())
        val files = metadata.getJSONObject("model_files").keys().asSequence().map { root.resolve(it) }.toList()
        val inventory = NativeQnnInventory.read(context, files)
        assertEquals(4096, QairtContextMetadata.admit(config, metadata, inventory).capacity)
        assertEquals(4, inventory.size)
        inventory.values.forEach { names ->
            assertTrue(names.any { it.startsWith("token_ar1_cl4096_") })
            assertTrue(names.any { it.startsWith("prompt_ar128_cl4096_") })
        }
    }

    @Test fun matchesExactQwenReferenceIdsIncludingCompleteChatTemplate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = JSONObject(instrumentation.context.assets.open("qwen-tokenizer-conformance.json").bufferedReader().use { it.readText() })
        assertEquals(NativeQwenTokenizer.TOKENIZER_SHA256, fixture.getString("tokenizer_sha256"))
        val tokenizerFile = context.filesDir.resolve("models/dialogue/active/bundle/tokenizer.json")
        NativeQwenTokenizer.open(context, tokenizerFile).use { tokenizer ->
            val cases = fixture.getJSONArray("cases")
            repeat(cases.length()) { index ->
                val case = cases.getJSONObject(index)
                val ids = tokenizer.encode(case.getString("text"))
                assertEquals(case.getInt("count"), ids.size)
                val bytes = ByteBuffer.allocate(ids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                ids.forEach(bytes::putInt)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
                assertEquals(case.getString("ids_sha256"), hash)
                assertArrayEquals(ids, tokenizer.encode(case.getString("text")))
            }
            assertTrue(runCatching { tokenizer.encode("x".repeat(NativeQwenTokenizer.MAX_TEXT_BYTES + 1)) }.isFailure)
        }
    }
}
