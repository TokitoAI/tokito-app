package dev.tokito.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.dialogue.*
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Real unified app sandbox; signed models must be UI-imported first. */
class LocalQwenTokensDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun exactNativeQwenIdsAndActualQnnInventory() {
        val repository = ModelBundleRepository(context)
        val directory = requireNotNull(repository.activeDirectory(ModelCapability.DIALOGUE))
        val model = DialogueModelProvisioner.fromAdmittedBundle(directory).requireInstalled()
        val root = model.path.canonicalFile.parentFile
        // Inspect release-preserved internal ABI without adding a public testing
        // API or bypassing the production signed-model/hash checks.
        val metadataType = Class.forName("dev.tokito.physical.dialogue.QairtContextMetadata")
        val metadataCompanion = metadataType.getField("Companion").get(null)
        val admitted = metadataCompanion.javaClass.getMethod("admit", android.content.Context::class.java,
            DialogueModel::class.java).invoke(metadataCompanion, context, model)
        assertEquals(4096, metadataType.getMethod("getCapacity").invoke(admitted))
        val type = Class.forName("dev.tokito.physical.dialogue.NativeQwenTokenizer")
        val companion = type.getField("Companion").get(null)
        val tokenizer = companion.javaClass.getMethod("open", android.content.Context::class.java, File::class.java)
            .invoke(companion, context, root.resolve("tokenizer.json")) as AutoCloseable
        try {
            val encode = type.getMethod("encode", String::class.java)
            val cases = JSONObject(REFERENCE).getJSONArray("cases")
            repeat(cases.length()) { index ->
                val case = cases.getJSONObject(index)
                val ids = encode.invoke(tokenizer, case.getString("text")) as IntArray
                assertEquals(case.getInt("count"), ids.size)
                val bytes = ByteBuffer.allocate(ids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                ids.forEach(bytes::putInt)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(case.getString("ids_sha256"), digest)
            }
        } finally { tokenizer.close() }
    }

    @Test fun actualNativeReceiptsAreAcceptedAtMultipleOutputCaps() = runBlocking {
        val foreground = ForegroundDeviceQa.launch()
        val directory = requireNotNull(ModelBundleRepository(context).activeDirectory(ModelCapability.DIALOGUE))
        val model = DialogueModelProvisioner.fromAdmittedBundle(directory).requireInstalled()
        // The production completion boundary must compare actual native prompt
        // and generated token receipts. Response success proves that boundary;
        // stream chunks or re-tokenized answer strings cannot substitute for it.
        for (cap in listOf(64, 128, 192)) {
            val runtime = ForegroundDeviceQa.runtime(foreground, DialogueLimits(maxPredictTokens = cap))
            try {
                withTimeout(120_000) { runtime.initialize(model) }
                for (prompt in listOf("In general, what does a resistor do?", "In general, what is circuit ground?")) {
                    ForegroundDeviceQa.requireResumed(foreground)
                    val response = withTimeout(65_000) { runtime.respond(DialogueRequest(prompt, deadlineMs = 60_000)) }
                    assertTrue("Cap $cap failed actual native receipt validation: $response", response is DialogueOutcome.Response)
                    instrumentation.sendStatus(2, android.os.Bundle().apply {
                        putInt("tokito_qwen_output_cap", cap)
                        putString("tokito_qwen_receipt_validated_response", (response as DialogueOutcome.Response).text)
                    })
                }
            } finally { runtime.close() }
        }
    }

    private companion object {
        // Genuine tokenizers 0.21.2 exact-ID fixtures also checked by the host
        // verification tool, not invented token counts or output quality scores.
        val REFERENCE = """
{
  "reference_tokenizers": "0.21.2",
  "tokenizer_sha256": "be75606093db2094d7cd20f3c2f385c212750648bd6ea4fb2bf507a6a4c55506",
  "cases": [
    {"text":"Hello","count":1,"ids_sha256":"c7f35e833d3235ee678e613f85ffa7aa17a0234c722b0549bf7ec89594a7478f"},
    {"text":"R14 10 k\u03a9 +3.3V GND U1.PA0","count":20,"ids_sha256":"345803821b3fa1d9b596d704fa240694c2bca893e3adc0637b9ac64007afb0db"},
    {"text":"caf\u00e9 cafe\u0301","count":3,"ids_sha256":"c8263fed810e69cfee951270ff1c27d1bef943aa587838891322599b71e26c3f"},
    {"text":"\u4f60\u597d\uff0c\u6d4b\u91cf\u7535\u538b\u3002","count":5,"ids_sha256":"ab615ddb80a2547ca3c6018741ae9ae7b4d2e0b3422ab7007d4c173ae825ee36"},
    {"text":"\ud83d\udd0c\u26a1 \u03a9 \u03bc \u00b1","count":6,"ids_sha256":"f5fb1859697eab4ecc14b63703df51d01c56b875d51f80238d603b5e0f428b37"},
    {"text":"a\u0000b","count":3,"ids_sha256":"685e7c26ba66b7e99fd20edfa7c0f766849799b2c685ee71e17085d43893cc0d"},
    {"text":"<|im_start|>system\nYou are helpful.<|im_end|>\n<|im_start|>user\nWhere is R14?<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n","count":27,"ids_sha256":"131773ed0c2f868d37f384c201eaa4eb5f69a0a35b0352bc2fd2012026dea417"},
    {"text":"line one\nline two\tend","count":6,"ids_sha256":"84f5f9c648827f46ad0dbfeaa5ae90aff83952e5aae960f562dbab810c0943c6"}
  ]
}
        """.trimIndent()
    }
}
