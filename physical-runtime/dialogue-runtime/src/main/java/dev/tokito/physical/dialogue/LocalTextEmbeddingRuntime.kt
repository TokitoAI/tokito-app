package dev.tokito.physical.dialogue

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs

/** Explicit CPU-only MiniLM encoder, separate from the existing Qwen NPU generator.
 *
 * The signed bundle importer owns trust admission. This factory rechecks its fixed payload
 * digests and tensor contract; it cannot turn an unsigned directory into trusted evidence.
 * Invoke construction and inference off the main thread. No inference queue or remote fallback.
 */
class LocalTextEmbeddingRuntime private constructor(
    private val session: OrtSession,
    private val tokenizer: BertWordPieceTokenizer,
    override val modelIdentity: String,
    override val dimensions: Int,
) : TextEmbeddingEncoder {
    private val closed = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val activeRun = AtomicReference<OrtSession.RunOptions?>()

    override fun embed(text: String): FloatArray {
        requireBackgroundThread()
        check(!closed.get()) { "Embedding runtime is closed" }
        check(lock.tryLock()) { "Embedding runtime is busy" }
        try {
            check(!closed.get()) { "Embedding runtime is closed" }
            val ids = tokenizer.encode(text)
            val mask = LongArray(ids.size) { 1L }
            val shape = longArrayOf(1, ids.size.toLong())
            val env = OrtEnvironment.getEnvironment()
            val inputs = linkedMapOf<String, OnnxTensor>()
            try {
                inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
                if ("token_type_ids" in session.inputNames) {
                    inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
                }
                OrtSession.RunOptions().use { options ->
                    options.setLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
                    check(activeRun.compareAndSet(null, options))
                    val expired = AtomicBoolean(false)
                    val timer = deadlineWorker.schedule({
                        synchronized(options) {
                            if (activeRun.get() === options) {
                                expired.set(true)
                                runCatching { options.setTerminate(true) }
                            }
                        }
                    }, INFERENCE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    try {
                        check(!closed.get()) { "Embedding runtime is closed" }
                        session.run(inputs, setOf("last_hidden_state"), options).use { result ->
                            check(!expired.get() && !closed.get()) { "Embedding inference expired or closed" }
                            val tensor = result.get("last_hidden_state").orElse(null) as? OnnxTensor
                                ?: error("Embedding model returned a non-tensor output")
                            val info = tensor.info as TensorInfo
                            require(info.type == OnnxJavaType.FLOAT && info.shape.contentEquals(
                                longArrayOf(1, ids.size.toLong(), dimensions.toLong()),
                            )) { "Embedding output violates its admitted tensor contract" }
                            val buffer = tensor.floatBuffer
                            val tokens = Array(ids.size) { FloatArray(dimensions).also { buffer.get(it) } }
                            val vector = EmbeddingMath.meanPool(tokens, mask, dimensions)
                            check(!expired.get() && !closed.get()) { "Embedding inference expired or closed" }
                            return vector
                        }
                    } finally {
                        timer.cancel(false)
                        // Serialize options termination with the timer before closing its native handle.
                        synchronized(options) { activeRun.compareAndSet(options, null) }
                    }
                }
            } finally {
                inputs.values.forEach(OnnxTensor::close)
            }
        } finally {
            try { if (closed.get()) dispose() } finally { lock.unlock() }
        }
    }

    /** Revokes output immediately; an in-flight native call owns its session until it returns. */
    override fun close() {
        closed.set(true)
        activeRun.get()?.let { options -> synchronized(options) {
            if (activeRun.get() === options) runCatching { options.setTerminate(true) }
        } }
        if (lock.tryLock()) {
            try { dispose() } finally { lock.unlock() }
        }
    }

    private fun dispose() {
        if (disposed.compareAndSet(false, true)) session.close()
    }

    companion object {
        private const val INFERENCE_DEADLINE_SECONDS = 10L
        private val deadlineWorker = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "tokito-embedding-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /** Caller must supply an immutable directory already admitted by the signed importer. */
        fun open(admittedDirectory: File): LocalTextEmbeddingRuntime {
            requireBackgroundThread()
            val root = admittedDirectory.canonicalFile
            require(root.isDirectory)
            val configFile = inside(root, "embedding.config.json", 16384)
            val config = JSONObject(configFile.readText(Charsets.UTF_8))
            require(config.keys().asSequence().toSet() == setOf("schema_version", "model_id", "model_sha256", "vocab_sha256",
                "max_tokens", "dimensions", "backend", "pooling", "lowercase", "strip_accents"))
            require(listOf("schema_version", "max_tokens", "dimensions").all { config.get(it) is Int })
            require(listOf("lowercase", "strip_accents").all { config.get(it) is Boolean })
            require(config.getInt("schema_version") == 1 && config.getString("backend") == "onnx_cpu")
            require(config.getString("pooling") == "masked_mean_l2" && config.getBoolean("lowercase") && config.getBoolean("strip_accents"))
            require(config.getInt("dimensions") == 384 && config.getInt("max_tokens") == 256)
            val modelId = config.getString("model_id")
            require(modelId.matches(Regex("[A-Za-z0-9._/-]{1,128}")))
            val model = inside(root, "model.onnx", 128L * 1024 * 1024)
            val vocabulary = inside(root, "vocab.txt", 2L * 1024 * 1024)
            val modelDigest = digest(model)
            val vocabularyDigest = digest(vocabulary)
            require(modelDigest == config.getString("model_sha256") && vocabularyDigest == config.getString("vocab_sha256")) {
                "Embedding model or vocabulary integrity mismatch"
            }
            val tokenizer = BertWordPieceTokenizer(vocabulary.readLines(Charsets.UTF_8), config.getInt("max_tokens"))
            val identity = "$modelId:onnx_cpu-1.27.1:bert-wordpiece-v1:${digest(configFile)}:$modelDigest:$vocabularyDigest"
            val environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "tokito-embedding")
            require(environment.version == "1.27.1") { "Embedding ONNX Runtime core does not match the admitted voice runtime" }
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                // No NNAPI/QNN/GPU EP is registered: CPU is the explicit admitted backend.
                environment.createSession(model.absolutePath, options)
            }
            try {
                require(session.inputNames == setOf("input_ids", "attention_mask", "token_type_ids") ||
                    session.inputNames == setOf("input_ids", "attention_mask"))
                session.inputInfo.values.forEach { node ->
                    val info = node.info as? TensorInfo ?: error("Invalid embedding input")
                    require(info.type == OnnxJavaType.INT64 && info.shape.size == 2)
                }
                val output = session.outputInfo["last_hidden_state"]?.info as? TensorInfo
                    ?: error("Missing embedding token output")
                require(output.type == OnnxJavaType.FLOAT && output.shape.size == 3 && output.shape[2] == 384L)
                return LocalTextEmbeddingRuntime(session, tokenizer, identity, 384)
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
        }

        /** Numerical/tokenizer conformance is checked before model activation, not just model load. */
        fun verifyConformance(admittedDirectory: File) {
            requireBackgroundThread()
            val root = admittedDirectory.canonicalFile
            val file = inside(root, "conformance.json", 512L * 1024)
            val document = JSONObject(file.readText(Charsets.UTF_8))
            require(document.keys().asSequence().toSet() == setOf("schema_version", "cases"))
            require(document.get("schema_version") is Int)
            require(document.getInt("schema_version") == 1)
            val cases = document.getJSONArray("cases")
            require(cases.length() in 3..16)
            val tokenizer = BertWordPieceTokenizer(inside(root, "vocab.txt", 2L * 1024 * 1024).readLines(Charsets.UTF_8))
            open(root).use { runtime ->
                for (index in 0 until cases.length()) {
                    val case = cases.getJSONObject(index)
                    require(case.keys().asSequence().toSet() == setOf("text", "token_ids", "vector"))
                    val text = case.getString("text")
                    val ids = case.getJSONArray("token_ids")
                    require(ids.length() in 3..256 && (0 until ids.length()).all { ids.get(it) is Int })
                    val expectedIds = LongArray(ids.length()) { ids.getLong(it) }
                    require(tokenizer.encode(text).contentEquals(expectedIds)) { "Embedding tokenizer conformance mismatch" }
                    val reference = case.getJSONArray("vector")
                    require(reference.length() == runtime.dimensions)
                    val expected = FloatArray(reference.length()) { reference.getDouble(it).toFloat() }
                    require(expected.all(Float::isFinite))
                    val actual = runtime.embed(text)
                    require(actual.indices.all { abs(actual[it] - expected[it]) <= 0.002f }) { "Embedding numerical conformance mismatch" }
                    val cosine = actual.indices.sumOf { actual[it].toDouble() * expected[it] }
                    require(cosine >= 0.999) { "Embedding reference vector is not equivalent" }
                }
            }
        }

        private fun inside(root: File, name: String, maximumBytes: Long): File = File(root, name).also {
            require(it.canonicalFile.parentFile == root && !java.nio.file.Files.isSymbolicLink(it.toPath()))
            require(it.isFile && it.length() in 1..maximumBytes)
        }

        private fun digest(file: File): String = file.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun requireBackgroundThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Embedding inference must not block the UI thread" }
        }
    }
}
