package dev.tokito.physical.dialogue

import org.json.JSONObject
import java.io.File
import android.content.Context

/** Context is an export/binary property, never Qwen's source positional maximum. */
internal data class QairtContextMetadata(val capacity: Int) {
    companion object {
        fun admit(context: Context, model: DialogueModel): QairtContextMetadata {
            require(model.runtime == DialogueRuntime.QAIRT)
            val root = model.path.canonicalFile.parentFile
            fun signedMetadata(name: String): JSONObject {
                val artifact = model.artifacts.singleOrNull { it.file.canonicalFile == File(root, name).canonicalFile }
                    ?: error("Signed QAIRT metadata is missing: $name")
                require(artifact.file.length() in 1..2L * 1024 * 1024) { "QAIRT metadata exceeds byte bound" }
                return JSONObject(artifact.file.readText(Charsets.UTF_8))
            }
            val metadata = signedMetadata("metadata.json")
            val names = metadata.getJSONObject("model_files").keys().asSequence().toList()
            val files = names.map { name ->
                require(File(name).name == name && name.endsWith(".bin")) { "Invalid signed QNN shard name" }
                model.artifacts.singleOrNull { it.file.canonicalFile == File(root, name).canonicalFile }?.file
                    ?: error("QNN shard is absent from signed inventory: $name")
            }
            return admit(signedMetadata("genie_config.json"), metadata, NativeQnnInventory.read(context, files))
        }

        fun admit(config: JSONObject, metadata: JSONObject, inventory: Map<String, List<String>>): QairtContextMetadata {
            val configured = config.getJSONObject("dialog").getJSONObject("context").getInt("size")
            require(configured in 1..65536) { "Invalid QAIRT context declaration" }
            val supported = metadata.getJSONObject("genie").getJSONArray("context_lengths")
            require((0 until supported.length()).any { supported.getInt(it) == configured }) {
                "Configured context is absent from signed export metadata"
            }
            val capacities = mutableSetOf<Int>()
            val files = metadata.getJSONObject("model_files")
            require(files.length() in 1..256)
            files.keys().forEach { filename ->
                val inputs = files.getJSONObject(filename).getJSONObject("inputs")
                require(inputs.length() <= 512)
                if (inputs.has("attention_mask")) {
                    val shape = inputs.getJSONObject("attention_mask").getJSONArray("shape")
                    require(shape.length() in 2..8)
                    val capacity = shape.getInt(shape.length() - 1)
                    require(capacity in 1..65536)
                    capacities.add(capacity)
                    inputs.keys().forEach { input ->
                        if (input.startsWith("past_key_") || input.startsWith("past_value_")) {
                            val kv = inputs.getJSONObject(input).getJSONArray("shape")
                            require(kv.length() == 4) { "Unsupported compiled QAIRT KV layout" }
                            val past = kv.getInt(if (input.startsWith("past_key_")) 3 else 2)
                            val batch = shape.getInt(shape.length() - 2)
                            require(past > 0 && batch > 0 && past.toLong() + batch == capacity.toLong()) {
                                "QAIRT attention and KV capacities disagree"
                            }
                        }
                    }
                }
            }
            require(capacities.isNotEmpty()) { "Signed export has no representative attention shape" }
            // Export JSON describes representative graphs only. Admission uses
            // QNN System's actual binary inventory, matching vendor graph selection.
            val filenames = files.keys().asSequence().toSet()
            require(inventory.keys == filenames) { "QNN inventory and signed shard metadata disagree" }
            val pattern = Regex("(?:[A-Za-z]+_)?ar(\\d+)_cl(\\d+)_(\\d+)_of_(\\d+)")
            val graphSets = inventory.values.map { names ->
                require(names.size in 1..1024 && names.distinct().size == names.size)
                names.map { name ->
                    val match = pattern.matchEntire(name) ?: error("Unrecognized QNN graph name")
                    val (ar, cl, shard, total) = match.destructured
                    listOf(ar.toInt(), cl.toInt(), shard.toInt(), total.toInt()).also { values ->
                        require(values[0] in 1..values[1] && values[1] in 1..65536 &&
                            values[3] == filenames.size && values[2] in 1..values[3])
                    }
                }.also { graphs -> require(graphs.distinct().size == graphs.size) { "Duplicated QNN graph variant" } }.toSet()
            }
            val all = graphSets.flatten()
            require(all.distinct().size == all.size) { "Duplicated QNN graph shard" }
            require(graphSets.all { graphs -> graphs.map { it[2] }.distinct().size == 1 }) { "Mixed shards in a QNN binary" }
            val variants = all.groupBy { it[0] to it[1] }
            require(variants.values.all { graphs -> graphs.map { it[2] }.toSet() == (1..filenames.size).toSet() }) {
                "Incomplete compiled QNN graph variant"
            }
            val compiled = variants.keys.map { it.second }.toSet()
            val declared = (0 until supported.length()).map { supported.getInt(it) }.toSet()
            require(compiled == declared && configured == compiled.maxOrNull() && capacities.all { it in compiled }) {
                "Configured, declared, and actual compiled QNN context capacities disagree"
            }
            return QairtContextMetadata(configured)
        }
    }
}

internal data class QwenTokenBudget(val promptTokens: Int, val reservedOutputTokens: Int, val capacity: Int) {
    init {
        require(promptTokens > 0 && reservedOutputTokens > 0 && capacity > 0)
        if (promptTokens.toLong() + reservedOutputTokens > capacity) {
            throw DialogueFailure(DialogueFailureCode.INVALID_REQUEST, "Complete Qwen prompt and reserved output exceed compiled context")
        }
    }

    fun validateReceipt(promptTokens: Long, generatedTokens: Long, stopReason: String) {
        if (promptTokens != this.promptTokens.toLong() || generatedTokens <= 0 || generatedTokens > reservedOutputTokens ||
            promptTokens + generatedTokens > capacity) {
            throw DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Native Qwen token receipt disagrees with admitted budget")
        }
        if (stopReason.lowercase() in setOf("context_length", "prompt_too_long", "context_limit", "context_length_exceeded")) {
            throw DialogueFailure(DialogueFailureCode.OUTPUT_LIMIT, "Qwen exhausted its compiled context")
        }
    }
}
