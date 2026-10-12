package dev.tokito.physical

import ai.tokito.physical.ar.ArCameraSurface
import ai.tokito.physical.ar.DigitalTwinValidation
import ai.tokito.physical.ar.DigitalTwinValidator
import ai.tokito.physical.ar.Point2
import android.graphics.BitmapFactory
import dev.tokito.physical.model.ReferenceTwinCatalog
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import java.io.File
import java.security.MessageDigest
import kotlin.math.hypot
import kotlin.math.min

/** Re-registers from signed reference features, never integrates frame-to-frame drift.
 * Matches establish image correspondence, not installed component identity or revision.
 */
class ReferenceBoardTracker private constructor(private val targets: List<Target>) : AutoCloseable {
    data class Observation(val entry: ReferenceTwinCatalog.Entry, val validation: DigitalTwinValidation.Accepted,
        val pose: PoseVisionObservation, val inliers: Int, val reprojectionPixels: Double)
    private data class Target(val entry: ReferenceTwinCatalog.Entry,
        val validation: DigitalTwinValidation.Accepted, val keys: Array<KeyPoint>,
        val descriptors: Mat, val boardImageCorners: List<Point>)
    private val orb = SIFT.create(3000, 3, 0.015)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE)
    private var closed = false

    @Synchronized
    fun analyze(frame: ArCameraSurface.CameraFrame): Observation? {
        check(!closed)
        val transform = frame.analysisToPreview ?: return null
        val image = Mat(); val gray = Mat(); val descriptors = Mat(); val keys = MatOfKeyPoint(); val empty = Mat()
        try {
            Utils.bitmapToMat(frame.bitmap, image)
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
            orb.detectAndCompute(gray, empty, keys, descriptors)
            if (descriptors.empty()) return null
            val live = keys.toArray()
            val candidates = targets.mapNotNull { target -> match(target, live, descriptors, frame) }
                .sortedByDescending { it.inliers }
            val best = candidates.firstOrNull() ?: return null
            // Similar evidence for two source packages cannot select a model.
            if (candidates.size > 1 && candidates[1].inliers >= best.inliers * 0.75) return null
            val points = FloatArray(8)
            best.pose.corners.forEachIndexed { i, p -> points[i * 2] = p.x.toFloat(); points[i * 2 + 1] = p.y.toFloat() }
            transform.mapPoints(points)
            return best.copy(pose = best.pose.copy(corners = (0..3).map { Point2(points[it*2].toDouble(), points[it*2+1].toDouble()) }))
        } finally { empty.release(); keys.release(); descriptors.release(); gray.release(); image.release() }
    }

    private fun match(target: Target, live: Array<KeyPoint>, descriptors: Mat,
        frame: ArCameraSurface.CameraFrame): Observation? {
        val neighbors = mutableListOf<MatOfDMatch>()
        val source = MatOfPoint2f(); val destination = MatOfPoint2f(); val mask = Mat()
        val board = MatOfPoint2f(); val projected = MatOfPoint2f(); val fitted = MatOfPoint2f()
        var homography: Mat? = null
        try {
            matcher.knnMatch(target.descriptors, descriptors, neighbors, 2)
            val matches = neighbors.mapNotNull { row -> row.toArray().let { pair ->
                if (pair.size == 2 && pair[0].distance < pair[1].distance * 0.75f) pair[0] else null
            } }.sortedBy { it.distance }.distinctBy { it.trainIdx }
            if (matches.size < 20) return null
            source.fromList(matches.map { target.keys[it.queryIdx].pt })
            destination.fromList(matches.map { live[it.trainIdx].pt })
            homography = Calib3d.findHomography(source, destination, Calib3d.RANSAC, 3.0, mask, 2000, 0.995)
            if (homography.empty()) return null
            val accepted = ByteArray(matches.size); mask.get(0, 0, accepted)
            val inliers = accepted.count { it.toInt() != 0 }
            if (inliers < 16 || inliers.toDouble() / matches.size < 0.35) return null
            Core.perspectiveTransform(source, fitted, homography)
            val predicted = fitted.toArray(); val actual = destination.toArray()
            val error = predicted.indices.filter { accepted[it].toInt() != 0 }.map {
                hypot(predicted[it].x - actual[it].x, predicted[it].y - actual[it].y)
            }.sorted().let { it[it.size / 2] }
            if (!error.isFinite() || error > 2.5) return null
            board.fromList(target.boardImageCorners)
            Core.perspectiveTransform(board, projected, homography)
            val corners = projected.toArray().map { Point2(it.x, it.y) }
            if (!TrackingGeometry.accept(corners, frame.bitmap.width, frame.bitmap.height)) return null
            val inlierPoints = actual.indices.filter { accepted[it].toInt() != 0 }.map { actual[it] }
            val extentX = inlierPoints.maxOf { it.x } - inlierPoints.minOf { it.x }
            val extentY = inlierPoints.maxOf { it.y } - inlierPoints.minOf { it.y }
            val boardWidth = corners.maxOf { it.x } - corners.minOf { it.x }
            val boardHeight = corners.maxOf { it.y } - corners.minOf { it.y }
            if (extentX < boardWidth * 0.25 || extentY < boardHeight * 0.25) return null
            return Observation(target.entry, target.validation,
                PoseVisionObservation(corners, inliers.toDouble()/matches.size, frame.timestampNanos), inliers, error)
        } finally {
            neighbors.forEach { it.release() }; source.release(); destination.release(); mask.release()
            board.release(); projected.release(); fitted.release(); homography?.release()
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        targets.forEach { it.descriptors.release() }; orb.clear(); matcher.clear()
    }

    companion object {
        fun load(entries: List<ReferenceTwinCatalog.Entry>): ReferenceBoardTracker? {
            val eligible = entries.filter { it.tracking != null }
            if (eligible.isEmpty()) return null
            require(eligible.size <= 8)
            check(OpenCVLoader.initLocal()) { "Native feature tracking unavailable" }
            val orb = SIFT.create(3000, 3, 0.015)
            val targets = mutableListOf<Target>()
            try {
                for (entry in eligible) {
                    val config = requireNotNull(entry.tracking)
                    require(config.length() in 1..65536)
                    val document = JSONObject(config.readText())
                    require(document.keys().asSequence().toSet() == setOf("schema_version", "image", "image_sha256", "image_landmarks", "metric_landmarks"))
                    require(document.getInt("schema_version") == 1)
                    val path = document.getString("image")
                    require(path.matches(Regex("[A-Za-z0-9_.-]+")) && !path.contains(".."))
                    val file = File(config.parentFile, path)
                    require(file.isFile && file.length() in 1..8_388_608)
                    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                    require(hash == document.getString("image_sha256"))
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    require(options.outWidth in 32..2048 && options.outHeight in 32..2048)
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                    val image = Mat(); val gray = Mat(); val mask = Mat(); val keys = MatOfKeyPoint(); val descriptors = Mat()
                    val metric = MatOfPoint2f(); val photo = MatOfPoint2f(); val board = MatOfPoint2f(); val projected = MatOfPoint2f()
                    var mapping: Mat? = null
                    var retained = false
                    try {
                        fun points(name: String): List<Point> {
                            val array = document.getJSONArray(name); require(array.length() == 4)
                            return (0..3).map { i -> val p = array.getJSONArray(i); require(p.length() == 2)
                                Point(p.getDouble(0), p.getDouble(1)).also { require(it.x.isFinite() && it.y.isFinite()) } }
                        }
                        val imagePoints = points("image_landmarks")
                        require(imagePoints.all { it.x in 0.0..bitmap.width.toDouble() && it.y in 0.0..bitmap.height.toDouble() })
                        val validation = DigitalTwinValidator().load(entry.manifest, entry.glb, entry.contextRevision)
                        require(validation is DigitalTwinValidation.Accepted)
                        metric.fromList(points("metric_landmarks")); photo.fromList(imagePoints)
                        mapping = Calib3d.findHomography(metric, photo)
                        require(!mapping.empty())
                        board.fromList(validation.packageData.manifest.boardPlaneCorners().map { Point(it.x, it.y) })
                        Core.perspectiveTransform(board, projected, mapping)
                        val corners = projected.toArray().toList()
                        require(TrackingGeometry.accept(corners.map { Point2(it.x, it.y) }, bitmap.width, bitmap.height))
                        Utils.bitmapToMat(bitmap, image); Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
                        // Exclude reference-photo background, which must never anchor a board.
                        mask.create(gray.rows(), gray.cols(), CvType.CV_8UC1); mask.setTo(Scalar(0.0))
                        val polygon = MatOfPoint(*corners.toTypedArray())
                        try { Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0)) } finally { polygon.release() }
                        orb.detectAndCompute(gray, mask, keys, descriptors)
                        require(descriptors.rows() >= 40) { "Reference board has insufficient feature evidence" }
                        targets += Target(entry, validation, keys.toArray(), descriptors, corners); retained = true
                    } finally {
                        bitmap.recycle(); image.release(); gray.release(); mask.release(); keys.release()
                        metric.release(); photo.release(); board.release(); projected.release(); mapping?.release()
                        if (!retained) descriptors.release()
                    }
                }
                return ReferenceBoardTracker(targets)
            } catch (failure: Throwable) { targets.forEach { it.descriptors.release() }; throw failure }
            finally { orb.clear() }
        }
    }
}

internal object TrackingGeometry {
    fun accept(points: List<Point2>, width: Int, height: Int): Boolean {
        if (points.size != 4 || width <= 0 || height <= 0 || points.any {
                !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..width.toDouble() || it.y !in 0.0..height.toDouble()
            }) return false
        val crosses = points.indices.map { i -> val a=points[i]; val b=points[(i+1)%4]; val c=points[(i+2)%4]
            (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x) }
        if (crosses.any { it <= 0.0 }) return false
        val area = kotlin.math.abs(points.indices.sumOf { i -> val a=points[i]; val b=points[(i+1)%4]; a.x*b.y-b.x*a.y })/2
        return area / (width.toDouble()*height) in 0.01..0.95
    }
}
