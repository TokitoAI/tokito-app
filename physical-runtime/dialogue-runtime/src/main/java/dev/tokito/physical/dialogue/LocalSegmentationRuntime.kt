package dev.tokito.physical.dialogue

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Generic instance masks only; never grants board, refdes, pin or registration identity. */
class LocalSegmentationRuntime private constructor(
    private val session: OrtSession,
    val labels: List<String>,
    private val threshold: Float,
    private val inputSize: Int,
) : AutoCloseable {
    data class Box(val label: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class Result(val bitmap: Bitmap, val maskToImage: Matrix, val labels: List<String>, val count: Int, val boxes: List<Box>)
    private var closed = false

    @Synchronized
    fun analyze(source: Bitmap): Result {
        check(!closed) { "Segmentation runtime closed" }
        val scale = min(inputSize.toFloat() / source.width, inputSize.toFloat() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val x = ((inputSize - w) / 2).toFloat()
        val y = ((inputSize - h) / 2).toFloat()
        val prepared = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(inputSize * inputSize)
        try {
            Canvas(prepared).apply {
                drawColor(Color.rgb(114, 114, 114))
                drawBitmap(source, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            prepared.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        } finally { prepared.recycle() }
        val tensor = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            tensor[i] = Color.red(pixels[i]) / 255f
            tensor[i + pixels.size] = Color.green(pixels[i]) / 255f
            tensor[i + pixels.size * 2] = Color.blue(pixels[i]) / 255f
        }
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(tensor),
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val boxes = (outputs[0].value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val prototypes = (outputs[1].value as Array<Array<Array<FloatArray>>>)[0]
                val decoded = SegmentMaskDecoder.decode(boxes, prototypes, labels.size, inputSize, threshold)
                val bitmap = Bitmap.createBitmap(decoded.colors, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                val transform = Matrix().apply { setValues(floatArrayOf(
                    inputSize.toFloat() / decoded.width / scale, 0f, -x / scale,
                    0f, inputSize.toFloat() / decoded.height / scale, -y / scale, 0f, 0f, 1f)) }
                val detections = decoded.boxes.map { box -> Box(labels[box.cls], box.score,
                    box.left * decoded.width / inputSize, box.top * decoded.height / inputSize,
                    box.right * decoded.width / inputSize, box.bottom * decoded.height / inputSize) }
                return Result(bitmap, transform, detections.map { it.label }.distinct(), detections.size, detections)
            }
        }
    }

    @Synchronized override fun close() { if (!closed) { closed = true; session.close() } }

    companion object {
        fun isBundle(root: File): Boolean = File(root, "segmentation.json").isFile
        fun open(root: File): LocalSegmentationRuntime {
            val config = File(root, "segmentation.json")
            require(config.length() in 1..65536)
            val doc = JSONObject(config.readText())
            require(doc.getInt("schema_version") == 1 && doc.getString("task") == "segment")
            require(doc.getString("backend") == "onnx_cpu")
            val size = doc.getInt("input_size").also { require(it == 640) }
            val labels = doc.getJSONArray("class_names").let { list ->
                require(list.length() in 1..128)
                (0 until list.length()).map { list.getString(it).also { value -> require(value.length in 1..128) } }
            }
            val confidence = doc.getDouble("confidence_threshold").toFloat().also { require(it in 0.01f..1f) }
            val model = File(root, "segment.onnx")
            require(model.length() in 1..(192L * 1024 * 1024))
            val digest = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { stream -> val buffer = ByteArray(1024 * 1024); while (true) {
                val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == doc.getString("model_sha256"))
            val environment = OrtEnvironment.getEnvironment()
            require(environment.version == "1.27.1")
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                environment.createSession(model.absolutePath, options)
            }
            try {
                require(session.inputInfo.size == 1 && session.outputInfo.size == 2)
                require((session.inputInfo.values.single().info as TensorInfo).shape.contentEquals(longArrayOf(1,3,640,640)))
                val shapes = session.outputInfo.values.map { (it.info as TensorInfo).shape.toList() }
                require(shapes == listOf(listOf(1L, (4 + labels.size + 32).toLong(), 8400L), listOf(1L,32L,160L,160L)))
                // A deterministic reference probe verifies numerical export/backend parity at admission.
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(3*640*640){114f/255f}),
                    longArrayOf(1,3,640,640)).use { probe ->
                    session.run(mapOf(session.inputNames.single() to probe)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val anchors = (output[0].value as Array<Array<FloatArray>>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val prototypes = (output[1].value as Array<Array<Array<FloatArray>>>)[0]
                        val expectedAnchor = doc.getJSONArray("conformance_anchor")
                        val expectedProto = doc.getJSONArray("conformance_proto")
                        require(expectedAnchor.length() == anchors.size && expectedProto.length() == prototypes.size)
                        anchors.indices.forEach { i ->
                            val actual = anchors[i][0]; val expected = expectedAnchor.getDouble(i)
                            require(actual.isFinite() && kotlin.math.abs(actual-expected) <= .001 * max(1.0,kotlin.math.abs(expected)))
                        }
                        prototypes.indices.forEach { i ->
                            val actual = prototypes[i][0][0]; val expected = expectedProto.getDouble(i)
                            require(actual.isFinite() && kotlin.math.abs(actual-expected) <= .001 * max(1.0,kotlin.math.abs(expected)))
                        }
                    }
                }
                return LocalSegmentationRuntime(session, labels, confidence, size)
            } catch (failure: Throwable) { session.close(); throw failure }
        }
    }
}

/** YOLO coefficients × prototypes, class-aware NMS, box clipping, then logit > 0. */
internal object SegmentMaskDecoder {
    data class Box(val cls: Int, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class Decoded(val colors: IntArray, val width: Int, val height: Int, val classIds: List<Int>, val count: Int, val boxes: List<Box>)
    private data class Candidate(val index: Int, val cls: Int, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
    fun decode(pred: Array<FloatArray>, proto: Array<Array<FloatArray>>, classes: Int, size: Int, threshold: Float, renderMasks: Boolean = true): Decoded {
        require(classes > 0 && pred.size == 4 + classes + proto.size && pred.all { it.size == pred[0].size })
        require(proto.isNotEmpty() && proto[0].isNotEmpty() && proto[0][0].isNotEmpty())
        val height = proto[0].size; val width = proto[0][0].size
        require(proto.all { channel -> channel.size == height && channel.all { it.size == width && it.all(Float::isFinite) } })
        val candidates = pred[0].indices.mapNotNull { i ->
            if (pred.any { !it[i].isFinite() }) return@mapNotNull null
            val cls = (0 until classes).maxBy { pred[4 + it][i] }
            val score = pred[4 + cls][i]
            if (score < threshold || score > 1 || pred[2][i] <= 0 || pred[3][i] <= 0) return@mapNotNull null
            Candidate(i, cls, score, (pred[0][i] - pred[2][i]/2).coerceIn(0f,size.toFloat()),
                (pred[1][i] - pred[3][i]/2).coerceIn(0f,size.toFloat()),
                (pred[0][i] + pred[2][i]/2).coerceIn(0f,size.toFloat()),
                (pred[1][i] + pred[3][i]/2).coerceIn(0f,size.toFloat()))
        }.sortedByDescending { it.score }.take(300)
        val kept = mutableListOf<Candidate>()
        for (candidate in candidates) {
            if (candidate.right <= candidate.left || candidate.bottom <= candidate.top) continue
            if (kept.none { it.cls == candidate.cls && iou(it,candidate) > 0.7f }) kept += candidate
            if (kept.size == 32) break
        }
        val colors = IntArray(width * height)
        val visible = mutableListOf<Int>()
        for (candidate in if (renderMasks) kept.asReversed() else emptyList()) {
            val color = palette(candidate.cls)
            var occupied = false
            for (y in 0 until height) for (x in 0 until width) {
                val ix = x.toFloat() * size / width; val iy = y.toFloat() * size / height
                if (ix < candidate.left || ix >= candidate.right || iy < candidate.top || iy >= candidate.bottom) continue
                var logit = 0f
                for (c in proto.indices) logit += pred[4 + classes + c][candidate.index] * proto[c][y][x]
                if (logit.isFinite() && logit > 0f) { colors[y * width + x] = color; occupied = true }
            }
            if (occupied) visible += candidate.cls
        }
        return Decoded(colors,width,height,visible.distinct(),visible.size,
            kept.map { Box(it.cls,it.score,it.left,it.top,it.right,it.bottom) })
    }
    private fun iou(a: Candidate,b: Candidate): Float {
        val intersection = max(0f,min(a.right,b.right)-max(a.left,b.left)) * max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        return intersection / ((a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-intersection).coerceAtLeast(1e-6f)
    }
    private fun palette(id: Int): Int {
        // Deterministic generic palette; no class- or board-specific behavior.
        val channel = (id * 73 + 47) % 180
        return (110 shl 24) or ((75 + channel) shl 16) or ((255 - channel) shl 8) or (100 + channel/2)
    }
}
