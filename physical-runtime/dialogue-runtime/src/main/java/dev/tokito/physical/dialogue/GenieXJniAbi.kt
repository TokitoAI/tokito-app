package dev.tokito.physical.dialogue

import java.lang.reflect.Modifier

/** Fail safely before a vendor JNI lookup can dereference a missing field. */
fun requireGenieXJniAbi() {
    val fields = mapOf(
        "LlmCreateInput" to listOf("model_name", "model_path", "tokenizer_path", "config", "runtime_id", "compute_unit"),
        "ModelConfig" to listOf("nCtx", "nThreads", "nThreadsBatch", "nBatch", "nUBatch", "nSeqMax", "nGpuLayers",
            "chat_template_path", "chat_template_content", "max_tokens", "enable_thinking", "verbose"),
        "GenerationConfig" to listOf("maxTokens", "stopWords", "stopCount", "nPast", "samplerConfig",
            "imagePaths", "imageCount", "audioPaths", "audioCount"),
        "SamplerConfig" to listOf("temperature", "topP", "topK", "minP", "repetitionPenalty",
            "presencePenalty", "frequencyPenalty", "seed", "grammarPath", "grammarString"),
        "ChatMessage" to listOf("role", "content"),
    )
    fields.forEach { (name, names) ->
        val type = Class.forName("com.geniex.sdk.bean.$name")
        names.forEach { field ->
            val member = type.getDeclaredField(field)
            val expected = when {
                field == "config" -> Class.forName("com.geniex.sdk.bean.ModelConfig")
                field == "samplerConfig" -> Class.forName("com.geniex.sdk.bean.SamplerConfig")
                field in setOf("stopWords", "imagePaths", "audioPaths") -> arrayOf<String>().javaClass
                field in setOf("enable_thinking", "verbose") -> Boolean::class.javaPrimitiveType
                field in setOf("temperature", "topP", "minP", "repetitionPenalty", "presencePenalty", "frequencyPenalty") -> Float::class.javaPrimitiveType
                field in setOf("nCtx", "nThreads", "nThreadsBatch", "nBatch", "nUBatch", "nSeqMax", "nGpuLayers",
                    "max_tokens", "maxTokens", "stopCount", "nPast", "imageCount", "audioCount", "topK", "seed") -> Int::class.javaPrimitiveType
                else -> String::class.java
            }
            check(!Modifier.isStatic(member.modifiers) && member.type == expected) {
                "Invalid GenieX JNI field $name.$field"
            }
        }
    }
    val llm = Class.forName("com.geniex.sdk.jni.Llm")
    val create = llm.getDeclaredMethod("create", Class.forName("com.geniex.sdk.bean.LlmCreateInput"))
    check(Modifier.isNative(create.modifiers) && create.returnType == Long::class.javaPrimitiveType) {
        "Invalid GenieX JNI create signature"
    }
}
