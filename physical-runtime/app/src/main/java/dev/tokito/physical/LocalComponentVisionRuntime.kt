package dev.tokito.physical

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class ComponentDetection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ComponentDetectionResult(
    val detections: List<ComponentDetection>,
    val inferenceNanos: Long,
    val inputWidth: Int,
    val inputHeight: Int,
)

/**
 * Bounded native Android runtime for compact YOLO component-detection experiments.
 *
 * The loader verifies the exact model digest and tensor contract. Model admission
 * remains the responsibility of [dev.tokito.physical.model.ModelBundleRepository]
 * in production; device instrumentation may use this runtime with local,
 * release-ineligible experiment assets.
 */
class LocalComponentVisionRuntime private constructor(
    private val manifest: Manifest,
    private val interpreter: Interpreter,
    private val inputLayout: InputLayout,
    private val outputLayout: OutputLayout,
) : AutoCloseable {
    data class Manifest(
        val task: String,
        val modelSha256: String,
        val inputSize: Int,
        val classNames: List<String>,
        val confidenceThreshold: Float,
        val iouThreshold: Float,
        val maximumDetections: Int,
        val coordinatesNormalized: Boolean,
    )

    private enum class InputLayout { NCHW, NHWC }
    private data class OutputLayout(val channelsFirst: Boolean, val anchors: Int)

    private var closed = false

    @Synchronized
    fun analyze(source: Bitmap): ComponentDetectionResult {
        check(!closed) { "component vision runtime is closed" }
        require(source.width > 0 && source.height > 0) { "component image is empty" }
        val prepared = letterbox(source)
        val output = ByteBuffer.allocateDirect((4 + manifest.classNames.size) * outputLayout.anchors * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val started = SystemClock.elapsedRealtimeNanos()
        interpreter.run(prepared.tensor, output)
        val elapsed = SystemClock.elapsedRealtimeNanos() - started
        output.rewind()
        val values = output.asFloatBuffer()
        val proposals = ArrayList<ComponentDetection>()
        for (anchor in 0 until outputLayout.anchors) {
            var classId = -1
            var confidence = Float.NEGATIVE_INFINITY
            for (candidateClass in manifest.classNames.indices) {
                val score = value(values, 4 + candidateClass, anchor)
                if (ComponentTensorContract.isProbability(score) && score > confidence) {
                    classId = candidateClass
                    confidence = score
                }
            }
            if (classId < 0 || confidence < manifest.confidenceThreshold) continue
            val multiplier = if (manifest.coordinatesNormalized) manifest.inputSize.toFloat() else 1f
            val cx = value(values, 0, anchor) * multiplier
            val cy = value(values, 1, anchor) * multiplier
            val width = value(values, 2, anchor) * multiplier
            val height = value(values, 3, anchor) * multiplier
            if (!cx.isFinite() || !cy.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) continue
            val left = ((cx - width / 2f - prepared.padX) / prepared.scale).coerceIn(0f, source.width.toFloat())
            val top = ((cy - height / 2f - prepared.padY) / prepared.scale).coerceIn(0f, source.height.toFloat())
            val right = ((cx + width / 2f - prepared.padX) / prepared.scale).coerceIn(0f, source.width.toFloat())
            val bottom = ((cy + height / 2f - prepared.padY) / prepared.scale).coerceIn(0f, source.height.toFloat())
            if (right <= left || bottom <= top) continue
            proposals += ComponentDetection(classId, manifest.classNames[classId], confidence, left, top, right, bottom)
        }
        return ComponentDetectionResult(
            detections = nonMaximumSuppression(proposals),
            inferenceNanos = elapsed,
            inputWidth = source.width,
            inputHeight = source.height,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }

    private data class PreparedFrame(val tensor: ByteBuffer, val scale: Float, val padX: Float, val padY: Float)

    private fun letterbox(source: Bitmap): PreparedFrame {
        val scale = min(manifest.inputSize.toFloat() / source.width, manifest.inputSize.toFloat() / source.height)
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val padX = (manifest.inputSize - width) / 2f
        val padY = (manifest.inputSize - height) / 2f
        val target = Bitmap.createBitmap(manifest.inputSize, manifest.inputSize, Bitmap.Config.ARGB_8888)
        Canvas(target).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                source,
                null,
                android.graphics.RectF(padX, padY, padX + width, padY + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        val pixels = IntArray(manifest.inputSize * manifest.inputSize)
        target.getPixels(pixels, 0, manifest.inputSize, 0, 0, manifest.inputSize, manifest.inputSize)
        target.recycle()
        val tensor = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        when (inputLayout) {
            InputLayout.NHWC -> pixels.forEach { pixel ->
                tensor.putFloat(Color.red(pixel) / 255f)
                tensor.putFloat(Color.green(pixel) / 255f)
                tensor.putFloat(Color.blue(pixel) / 255f)
            }
            InputLayout.NCHW -> for (channel in 0..2) pixels.forEach { pixel ->
                tensor.putFloat(
                    when (channel) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.blue(pixel)
                    } / 255f,
                )
            }
        }
        tensor.rewind()
        return PreparedFrame(tensor, scale, padX, padY)
    }

    private fun value(values: java.nio.FloatBuffer, channel: Int, anchor: Int): Float =
        if (outputLayout.channelsFirst) values[channel * outputLayout.anchors + anchor]
        else values[anchor * (4 + manifest.classNames.size) + channel]

    private fun nonMaximumSuppression(proposals: List<ComponentDetection>): List<ComponentDetection> {
        val accepted = ArrayList<ComponentDetection>()
        for (candidate in proposals.sortedByDescending(ComponentDetection::confidence)) {
            if (accepted.size >= manifest.maximumDetections) break
            if (accepted.none { it.classId == candidate.classId && intersectionOverUnion(it, candidate) > manifest.iouThreshold }) {
                accepted += candidate
            }
        }
        return accepted
    }

    private fun intersectionOverUnion(first: ComponentDetection, second: ComponentDetection): Float {
        val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = (first.right - first.left) * (first.bottom - first.top) +
            (second.right - second.left) * (second.bottom - second.top) - intersection
        return if (union > 0f) intersection / union else 0f
    }

    companion object {
        fun load(bundleDirectory: File): LocalComponentVisionRuntime {
            val manifestFile = File(bundleDirectory, MANIFEST_FILE)
            val modelFile = File(bundleDirectory, MODEL_FILE)
            require(manifestFile.isFile && modelFile.isFile) { "component vision experiment is not installed" }
            require(manifestFile.length() in 1..MAXIMUM_MANIFEST_BYTES && modelFile.length() in 1..MAXIMUM_MODEL_BYTES) {
                "component vision experiment exceeds its size budget"
            }
            val document = JSONObject(manifestFile.readText())
            require(document.getString("schema_version") == "1.0") { "unsupported component vision manifest" }
            val task = document.getString("task")
            require(task == "component_detection" || task == "board_detection") { "unexpected local vision task" }
            require(!document.optBoolean("release_eligible", true)) { "experimental loader requires release_eligible=false" }
            val digest = document.getString("model_sha256").removePrefix("sha256:").lowercase()
            require(digest.matches(Regex("[a-f0-9]{64}"))) { "invalid component model digest" }
            val actual = modelFile.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    total += size
                    require(total <= MAXIMUM_MODEL_BYTES) { "component model grew beyond its byte bound" }
                    hash.update(buffer, 0, size)
                }
                hash.digest().toHex()
            }
            require(MessageDigest.isEqual(actual.toByteArray(), digest.toByteArray())) { "component model digest mismatch" }
            val namesDocument = document.getJSONArray("class_names")
            val classNames = (0 until namesDocument.length()).map(namesDocument::getString)
            require(classNames.isNotEmpty() && classNames.size <= 64 && classNames.distinct().size == classNames.size && classNames.none(String::isBlank)) {
                "invalid component class names"
            }
            val manifest = Manifest(
                task = task,
                modelSha256 = digest,
                inputSize = document.getInt("input_size").also { require(it in 320..1280) },
                classNames = classNames,
                confidenceThreshold = document.getDouble("confidence_threshold").toFloat().also { require(it in 0f..1f) },
                iouThreshold = document.getDouble("iou_threshold").toFloat().also { require(it in 0f..1f) },
                maximumDetections = document.getInt("maximum_detections").also { require(it in 1..1000) },
                coordinatesNormalized = document.getBoolean("coordinates_normalized"),
            )
            val options = Interpreter.Options().setNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
            val interpreter = Interpreter(modelFile, options)
            try {
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) { "unexpected component model interface" }
            require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 && interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32) {
                "component experiment currently requires float32 tensors"
            }
            val inputShape = interpreter.getInputTensor(0).shape()
            val inputLayout = when {
                inputShape.contentEquals(intArrayOf(1, 3, manifest.inputSize, manifest.inputSize)) -> InputLayout.NCHW
                inputShape.contentEquals(intArrayOf(1, manifest.inputSize, manifest.inputSize, 3)) -> InputLayout.NHWC
                else -> error("unexpected component vision input shape: ${inputShape.contentToString()}")
            }
            val outputShape = interpreter.getOutputTensor(0).shape()
            require(outputShape.size == 3 && outputShape[0] == 1) { "unexpected component vision output shape: ${outputShape.contentToString()}" }
            val channels = 4 + manifest.classNames.size
            val outputLayout = when {
                outputShape[1] == channels && outputShape[2] > 0 -> OutputLayout(true, outputShape[2])
                outputShape[2] == channels && outputShape[1] > 0 -> OutputLayout(false, outputShape[1])
                else -> error("unexpected component vision output shape: ${outputShape.contentToString()}")
            }
            ComponentTensorContract.requireBoundedOutput(outputLayout.anchors, manifest.classNames.size)
            return LocalComponentVisionRuntime(manifest, interpreter, inputLayout, outputLayout)
            } catch (failure: Throwable) {
                interpreter.close()
                throw failure
            }
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        private const val MANIFEST_FILE = "component-manifest.json"
        private const val MODEL_FILE = "model.tflite"
        private const val MAXIMUM_MANIFEST_BYTES = 64L * 1024L
        private const val MAXIMUM_MODEL_BYTES = 128L * 1024L * 1024L
    }
}

internal object ComponentTensorContract {
    fun isProbability(value: Float): Boolean = value.isFinite() && value in 0f..1f

    fun requireBoundedOutput(anchors: Int, classes: Int) {
        require(classes in 1..64 && anchors in 1..100_000 &&
            (4L + classes) * anchors * Float.SIZE_BYTES <= 32L * 1024L * 1024L) {
            "component output tensor exceeds its allocation budget"
        }
    }
}
