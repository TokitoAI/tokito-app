package dev.tokito.physical.dialogue

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenTokenBudgetTest {
    @Test fun usesRemainingCompiledContextInsteadOfFixed512TokenCeiling() {
        val budget = QwenTokenBudget.availableContext(1000, 4096, 512)
        assertEquals(3095, budget.reservedOutputTokens)
        budget.validateReceipt(1000, 900, "eos")
        assertFailure { QwenTokenBudget.availableContext(3600, 4096, 512) }
        assertFailure { budget.validateReceipt(1000, 3096, "eos") }
    }
    @Test fun accountsForSignedBosWithoutDoubleInsertionOrMagicOffset() {
        val context = QairtContextMetadata(4096, 17)
        assertEquals(3, context.freshPromptTokenCount(intArrayOf(2, 3)))
        assertEquals(2, context.freshPromptTokenCount(intArrayOf(17, 3)))
        assertEquals(2, QairtContextMetadata(4096).freshPromptTokenCount(intArrayOf(2, 3)))
        assertFailure { context.freshPromptTokenCount(intArrayOf()) }
    }
    @Test fun reservesOutputAgainstCompletePromptTokens() {
        assertEquals(4096, QwenTokenBudget(3904, 192, 4096).capacity)
        assertFailure { QwenTokenBudget(3905, 192, 4096) }
        assertFailure { QwenTokenBudget(Int.MAX_VALUE, Int.MAX_VALUE, 4096) }
    }

    @Test fun receiptsMustHaveExactPromptCountAndBoundedActualOutput() {
        val budget = QwenTokenBudget(42, 192, 4096)
        budget.validateReceipt(42, 10, "eos")
        assertFailure { budget.validateReceipt(0, 10, "eos") }
        assertFailure { budget.validateReceipt(43, 10, "eos") }
        assertFailure { budget.validateReceipt(42, 0, "eos") }
        assertFailure { budget.validateReceipt(42, 193, "eos") }
        assertFailure { budget.validateReceipt(42, 10, "context_length") }
        assertFailure { budget.validateReceipt(42, 10, "prompt_too_long") }
    }

    @Test fun admitsExportedAttentionAndKvCapacityNotSourceModelMaximum() {
        assertEquals(3072, QairtContextMetadata.admit(config(3072), metadata(3072), inventory(3072)).capacity)
    }

    @Test fun admits4096ActualVariantEvenWhenJsonRepresentativeIs3072() {
        val metadata = metadata(3072)
        metadata.getJSONObject("genie").put("context_lengths", JSONArray(listOf(3072, 4096)))
        assertEquals(4096, QairtContextMetadata.admit(config(4096), metadata, inventory(3072, 4096)).capacity)
        assertFailure { QairtContextMetadata.admit(config(4096), metadata, inventory(3072)) }
        assertFailure { QairtContextMetadata.admit(config(3072), metadata, inventory(3072, 4096)) }
    }

    @Test fun rejectsUnsupportedKvLayoutAndInconsistentExportShapes() {
        val metadata = metadata(3072)
        metadata.getJSONObject("model_files").getJSONObject("part.bin").getJSONObject("inputs")
            .getJSONObject("past_key_0_in").put("shape", JSONArray(listOf(8, 1, 128, 4095)))
        assertFailure { QairtContextMetadata.admit(config(3072), metadata, inventory(3072)) }
        assertFailure { QairtContextMetadata.admit(config(2048), metadata(3072), inventory(3072)) }
    }

    @Test fun rejectsMissingShardDuplicateVariantAndUnrecognizedGraphs() {
        assertFailure { QairtContextMetadata.admit(config(4096), metadata(4096), mapOf("part.bin" to listOf("token_ar1_cl4096_1_of_2"))) }
        assertFailure { QairtContextMetadata.admit(config(4096), metadata(4096), mapOf("part.bin" to listOf("token_ar1_cl4096_1_of_1", "prompt_ar1_cl4096_1_of_1"))) }
        assertFailure { QairtContextMetadata.admit(config(4096), metadata(4096), mapOf("part.bin" to listOf("unknown"))) }
        assertFailure { QairtContextMetadata.admit(config(4096), metadata(4096), mapOf("unsigned.bin" to listOf("token_ar1_cl4096_1_of_1"))) }
    }

    private fun inventory(vararg capacities: Int) = mapOf("part.bin" to capacities.flatMap { capacity ->
        listOf("token_ar1_cl${capacity}_1_of_1", "prompt_ar128_cl${capacity}_1_of_1")
    })

    private fun config(capacity: Int) = JSONObject().put("dialog",
        JSONObject().put("context", JSONObject().put("size", capacity)))

    private fun metadata(capacity: Int) = JSONObject()
        .put("genie", JSONObject().put("context_lengths", JSONArray(listOf(capacity))))
        .put("model_files", JSONObject().put("part.bin", JSONObject().put("inputs", JSONObject()
            .put("attention_mask", JSONObject().put("shape", JSONArray(listOf(1, 1, 1, capacity))))
            .put("past_key_0_in", JSONObject().put("shape", JSONArray(listOf(8, 1, 128, capacity - 1))))
            .put("past_value_0_in", JSONObject().put("shape", JSONArray(listOf(8, 1, capacity - 1, 128)))))))

    private fun assertFailure(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
}
