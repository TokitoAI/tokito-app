package dev.tokito.physical

import ai.tokito.physical.ar.ArCameraSurface
import ai.tokito.physical.ar.Point2
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class PoseVisionObservation(
    val corners: List<Point2>,
    val confidence: Double,
    val timestampNanos: Long,
)

/** Admits and executes a local YOLO pose bundle without any network capability. */
class LocalPoseVisionRuntime private constructor(
    private val manifest: Manifest,
    private val interpreter: Interpreter,
) : AutoCloseable {
    data class Manifest(
        val modelSha256: String,
        val inputSize: Int,
        val classConfidence: Float,
        val keypointConfidence: Float,
        val minimumBoardAreaRatio: Double,
        val maximumCornerJitterRatio: Double,
        val stableFrameCount: Int,
        val maximumObservationAgeNanos: Long,
        val coordinatesNormalized: Boolean,
    )

    private val stabilizer = PoseStabilizer(manifest)
    private val output = Array(1) { Array(17) { FloatArray(8400) } }
    private var analyzedFrames = 0L

    fun analyze(frame: ArCameraSurface.CameraFrame): PoseVisionObservation? {
        if (frame.previewWidth <= 0 || frame.previewHeight <= 0) {
            frame.bitmap.recycle()
            return null
        }
        val prepared = letterbox(frame.bitmap, manifest.inputSize)
        frame.bitmap.recycle()
        analyzedFrames += 1
        if (analyzedFrames == 1L) Log.d(TAG, "Analyzing first camera frame")
        interpreter.run(prepared.tensor, output)
        val candidate = PoseDecoder.decode(output[0], prepared, frame, manifest)
        if (analyzedFrames == 1L || analyzedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
            val index = output[0][4].indices.maxByOrNull { output[0][4][it] } ?: 0
            val minimumKeypointConfidence = (0 until 4).minOf { output[0][7 + it * 3][index] }
            Log.d(
                TAG,
                "frame=$analyzedFrames classConfidence=${output[0][4][index]} " +
                    "keypointConfidence=$minimumKeypointConfidence geometryAccepted=${candidate != null}",
            )
        }
        candidate ?: return stabilizer.reject()
        return stabilizer.accept(candidate)
    }

    override fun close() {
        interpreter.close()
    }

    private data class PreparedFrame(
        val tensor: java.nio.FloatBuffer,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val scale: Double,
        val padX: Double,
        val padY: Double,
    )

    private fun letterbox(source: Bitmap, size: Int): PreparedFrame {
        val scale = min(size.toDouble() / source.width, size.toDouble() / source.height)
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val padX = (size - width) / 2.0
        val padY = (size - height) / 2.0
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(target).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(source, null, android.graphics.RectF(padX.toFloat(), padY.toFloat(), (padX + width).toFloat(), (padY + height).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(size * size)
        target.getPixels(pixels, 0, size, 0, 0, size, size)
        target.recycle()
        val buffer = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0..2) for (pixel in pixels) {
            val value = when (channel) {
                0 -> Color.red(pixel)
                1 -> Color.green(pixel)
                else -> Color.blue(pixel)
            }
            buffer.put(value / 255f)
        }
        buffer.rewind()
        return PreparedFrame(buffer, source.width, source.height, scale, padX, padY)
    }

    companion object {
        fun load(bundleDirectory: File): LocalPoseVisionRuntime {
            val manifestFile = File(bundleDirectory, "manifest.json")
            val modelFile = File(bundleDirectory, "model.tflite")
            require(manifestFile.isFile && modelFile.isFile) { "local vision bundle is not installed" }
            require(manifestFile.length() in 1..MAXIMUM_MANIFEST_BYTES && modelFile.length() in 1..MAXIMUM_MODEL_BYTES) {
                "local vision bundle exceeds its size budget"
            }
            val document = JSONObject(manifestFile.readText())
            require(document.getString("schema_version") == "1.0") { "unsupported vision manifest" }
            val digest = document.getString("model_sha256").removePrefix("sha256:").lowercase()
            require(digest.matches(Regex("[a-f0-9]{64}"))) { "invalid vision model digest" }
            val actual = MessageDigest.getInstance("SHA-256").digest(modelFile.readBytes()).toHex()
            require(MessageDigest.isEqual(actual.toByteArray(), digest.toByteArray())) { "vision model digest mismatch" }
            val manifest = Manifest(
                modelSha256 = digest,
                inputSize = document.getInt("input_size").also { require(it in 320..1280) },
                classConfidence = document.getDouble("class_confidence").toFloat().also { require(it in 0.0f..1.0f) },
                keypointConfidence = document.getDouble("keypoint_confidence").toFloat().also { require(it in 0.0f..1.0f) },
                minimumBoardAreaRatio = document.getDouble("minimum_board_area_ratio").also { require(it in 0.001..0.8) },
                maximumCornerJitterRatio = document.getDouble("maximum_corner_jitter_ratio").also { require(it in 0.001..0.2) },
                stableFrameCount = document.getInt("stable_frame_count").also { require(it in 2..12) },
                maximumObservationAgeNanos = document.getLong("maximum_observation_age_ms").also { require(it in 100..5000) } * 1_000_000L,
                coordinatesNormalized = document.getBoolean("coordinates_normalized"),
            )
            val options = Interpreter.Options().setNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
            val interpreter = Interpreter(modelFile, options)
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) { "unexpected vision model interface" }
            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 3, manifest.inputSize, manifest.inputSize))) {
                "unexpected vision input shape"
            }
            require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 17, 8400))) {
                "unexpected vision output shape"
            }
            return LocalPoseVisionRuntime(manifest, interpreter)
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        private const val MAXIMUM_MANIFEST_BYTES = 64L * 1024L
        private const val MAXIMUM_MODEL_BYTES = 64L * 1024L * 1024L
        private const val DIAGNOSTIC_FRAME_INTERVAL = 30L
        private const val TAG = "TokitoPoseVision"
    }

    private object PoseDecoder {
        fun decode(
            output: Array<FloatArray>,
            prepared: PreparedFrame,
            frame: ArCameraSurface.CameraFrame,
            config: Manifest,
        ): PoseVisionObservation? {
            if (output.size != 17 || output.any { it.size != output[0].size }) return null
            val index = output[4].indices.maxByOrNull { output[4][it] } ?: return null
            if (output[4][index] < config.classConfidence) return null
            val points = (0 until 4).map { keypoint ->
                val offset = 5 + keypoint * 3
                if (output[offset + 2][index] < config.keypointConfidence) return null
                val sourceX = (coordinate(output[offset][index], config) - prepared.padX) / prepared.scale
                val sourceY = (coordinate(output[offset + 1][index], config) - prepared.padY) / prepared.scale
                Point2(sourceX, sourceY)
            }
            if (!validGeometry(points, prepared.sourceWidth, prepared.sourceHeight, output, index, prepared, config)) return null
            val mappedPoints = FloatArray(points.size * 2)
            points.forEachIndexed { i, point -> mappedPoints[i * 2] = point.x.toFloat(); mappedPoints[i * 2 + 1] = point.y.toFloat() }
            val cameraTransform = frame.analysisToPreview
            if (cameraTransform != null) {
                cameraTransform.mapPoints(mappedPoints)
            } else {
                val previewScale = max(frame.previewWidth.toDouble() / prepared.sourceWidth, frame.previewHeight.toDouble() / prepared.sourceHeight)
                val offsetX = (frame.previewWidth - prepared.sourceWidth * previewScale) / 2.0
                val offsetY = (frame.previewHeight - prepared.sourceHeight * previewScale) / 2.0
                points.forEachIndexed { i, point ->
                    mappedPoints[i * 2] = (point.x * previewScale + offsetX).toFloat()
                    mappedPoints[i * 2 + 1] = (point.y * previewScale + offsetY).toFloat()
                }
            }
            return PoseVisionObservation(
                corners = points.indices.map { Point2(mappedPoints[it * 2].toDouble(), mappedPoints[it * 2 + 1].toDouble()) },
                confidence = min(output[4][index], (0 until 4).minOf { output[7 + it * 3][index] }).toDouble(),
                timestampNanos = frame.timestampNanos,
            )
        }

        private fun validGeometry(
            points: List<Point2>,
            width: Int,
            height: Int,
            output: Array<FloatArray>,
            index: Int,
            prepared: PreparedFrame,
            config: Manifest,
        ): Boolean {
            if (points.any { it.x !in 0.0..width.toDouble() || it.y !in 0.0..height.toDouble() }) return false
            val crosses = points.indices.map { i ->
                val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
                (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            }
            if (crosses.any { it <= 0.0 }) return false
            val area = abs(points.indices.sumOf { i ->
                val a = points[i]; val b = points[(i + 1) % 4]; a.x * b.y - b.x * a.y
            }) / 2.0
            if (area / (width.toDouble() * height) < config.minimumBoardAreaRatio) return false
            val boxCx = (coordinate(output[0][index], config) - prepared.padX) / prepared.scale
            val boxCy = (coordinate(output[1][index], config) - prepared.padY) / prepared.scale
            val boxW = coordinate(output[2][index], config) / prepared.scale
            val boxH = coordinate(output[3][index], config) / prepared.scale
            if (boxW <= 0 || boxH <= 0) return false
            return points.all { it.x in (boxCx - boxW * 0.7)..(boxCx + boxW * 0.7) && it.y in (boxCy - boxH * 0.7)..(boxCy + boxH * 0.7) }
        }

        private fun coordinate(value: Float, config: Manifest): Double =
            value.toDouble() * if (config.coordinatesNormalized) config.inputSize else 1
    }

    private class PoseStabilizer(private val config: Manifest) {
        private val history = ArrayDeque<PoseVisionObservation>()

        fun reject(): PoseVisionObservation? {
            history.clear()
            return null
        }

        fun accept(candidate: PoseVisionObservation): PoseVisionObservation? {
            val diagonal = hypot(
                candidate.corners[2].x - candidate.corners[0].x,
                candidate.corners[2].y - candidate.corners[0].y,
            ).coerceAtLeast(1.0)
            val previous = history.lastOrNull()
            if (previous != null && previous.corners.indices.any { i ->
                    hypot(candidate.corners[i].x - previous.corners[i].x, candidate.corners[i].y - previous.corners[i].y) / diagonal > config.maximumCornerJitterRatio
                }) history.clear()
            history.addLast(candidate)
            while (history.size > config.stableFrameCount) history.removeFirst()
            if (history.size < config.stableFrameCount) return null
            val now = System.nanoTime()
            if (now - candidate.timestampNanos > config.maximumObservationAgeNanos) return reject()
            return candidate.copy(
                corners = candidate.corners.indices.map { index ->
                    Point2(history.map { it.corners[index].x }.average(), history.map { it.corners[index].y }.average())
                },
                confidence = history.minOf { it.confidence },
            )
        }
    }
}
