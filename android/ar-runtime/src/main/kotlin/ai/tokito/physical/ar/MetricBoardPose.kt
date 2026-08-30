package ai.tokito.physical.ar

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(listOf(fx, fy, cx, cy).all(Double::isFinite) && fx > 0 && fy > 0)
        require(widthPx > 0 && heightPx > 0)
    }
}

data class Vector3(val x: Double, val y: Double, val z: Double) {
    operator fun times(value: Double) = Vector3(x * value, y * value, z * value)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    fun dot(other: Vector3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vector3) = Vector3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun norm() = sqrt(dot(this))
    fun normalized(): Vector3? = norm().takeIf { it.isFinite() && it > 1e-10 }?.let { this * (1.0 / it) }
}

/** Board-to-camera pose in OpenCV coordinates: x right, y down, z forward. */
data class MetricBoardPose(
    val r1: Vector3,
    val r2: Vector3,
    val r3: Vector3,
    val translationMetres: Vector3,
    val revision: Long,
    val observedAtNanos: Long,
    val expiresAtNanos: Long,
    val reprojectionRmsPx: Double,
    val confidence: Double,
) {
    init {
        require(revision >= 0 && observedAtNanos >= 0 && expiresAtNanos >= observedAtNanos)
        require(reprojectionRmsPx.isFinite() && reprojectionRmsPx >= 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(translationMetres.z > 0)
    }

    /**
     * Column-major glTF-Y-up asset to Filament-camera transform. Asset X/Z are
     * board X/Y; asset +Y is physical height toward the observing camera.
     */
    fun filamentTransformForYUpAsset(): FloatArray = floatArrayOf(
        r1.x.toFloat(), (-r1.y).toFloat(), (-r1.z).toFloat(), 0f,
        (-r3.x).toFloat(), r3.y.toFloat(), r3.z.toFloat(), 0f,
        r2.x.toFloat(), (-r2.y).toFloat(), (-r2.z).toFloat(), 0f,
        translationMetres.x.toFloat(), (-translationMetres.y).toFloat(), (-translationMetres.z).toFloat(), 1f,
    )
}

fun CameraIntrinsics.filamentProjection(near: Double = 0.01, far: Double = 10.0): DoubleArray {
    require(near > 0.0 && far > near)
    return doubleArrayOf(
        2.0 * fx / widthPx, 0.0, 0.0, 0.0,
        0.0, 2.0 * fy / heightPx, 0.0, 0.0,
        1.0 - 2.0 * cx / widthPx, 2.0 * cy / heightPx - 1.0, -(far + near) / (far - near), -1.0,
        0.0, 0.0, -2.0 * far * near / (far - near), 0.0,
    )
}

sealed interface PoseEstimation {
    data class Accepted(val pose: MetricBoardPose) : PoseEstimation
    data class Rejected(val reason: String) : PoseEstimation
}

class MetricPoseEstimator(
    private val maximumRmsPx: Double = 8.0,
    private val validityNanos: Long = 500_000_000L,
) {
    fun estimate(
        boardPointsMetres: List<Point2>,
        imagePointsPx: List<Point2>,
        intrinsics: CameraIntrinsics,
        revision: Long,
        nowNanos: Long,
    ): PoseEstimation {
        if (boardPointsMetres.size != imagePointsPx.size || boardPointsMetres.size < 4) {
            return PoseEstimation.Rejected("at least four matched board/image points are required")
        }
        val normalized = imagePointsPx.map { Point2((it.x - intrinsics.cx) / intrinsics.fx, (it.y - intrinsics.cy) / intrinsics.fy) }
        val homography = fitHomography(boardPointsMetres, normalized)
            ?: return PoseEstimation.Rejected("correspondences do not define a stable plane")
        val h = homography.values()
        var c1 = Vector3(h[0], h[3], h[6])
        var c2 = Vector3(h[1], h[4], h[7])
        var t = Vector3(h[2], h[5], h[8])
        val scale = 2.0 / (c1.norm() + c2.norm())
        if (!scale.isFinite()) return PoseEstimation.Rejected("pose scale is invalid")
        c1 *= scale; c2 *= scale; t *= scale
        if (t.z < 0) { c1 *= -1.0; c2 *= -1.0; t *= -1.0 }
        val r1 = c1.normalized() ?: return PoseEstimation.Rejected("first pose axis is degenerate")
        val r2 = (c2 - r1 * c2.dot(r1)).normalized() ?: return PoseEstimation.Rejected("second pose axis is degenerate")
        val r3 = r1.cross(r2).normalized() ?: return PoseEstimation.Rejected("pose normal is degenerate")
        val rms = reprojectionRms(boardPointsMetres, imagePointsPx, intrinsics, r1, r2, t)
        if (!rms.isFinite() || rms > maximumRmsPx) return PoseEstimation.Rejected("reprojection error exceeds the accepted bound")
        val diagonal = hypot(intrinsics.widthPx.toDouble(), intrinsics.heightPx.toDouble())
        val confidence = (1.0 - rms / maximumRmsPx).coerceIn(0.0, 1.0)
        if (rms / diagonal > 0.005) return PoseEstimation.Rejected("normalized reprojection error exceeds release gate")
        return PoseEstimation.Accepted(MetricBoardPose(r1, r2, r3, t, revision, nowNanos, nowNanos + validityNanos, rms, confidence))
    }

    private fun reprojectionRms(
        board: List<Point2>, image: List<Point2>, k: CameraIntrinsics,
        r1: Vector3, r2: Vector3, t: Vector3,
    ): Double {
        val sum = board.indices.sumOf { index ->
            val p = r1 * board[index].x
            val q = r2 * board[index].y
            val x = p.x + q.x + t.x; val y = p.y + q.y + t.y; val z = p.z + q.z + t.z
            if (z <= 1e-8) return Double.POSITIVE_INFINITY
            val u = k.fx * x / z + k.cx; val v = k.fy * y / z + k.cy
            (u - image[index].x) * (u - image[index].x) + (v - image[index].y) * (v - image[index].y)
        }
        return sqrt(sum / board.size)
    }

    private fun fitHomography(source: List<Point2>, destination: List<Point2>): Matrix3? {
        val normal = Array(8) { DoubleArray(9) }
        fun accumulate(row: DoubleArray, target: Double) {
            for (i in 0..7) {
                for (j in 0..7) normal[i][j] += row[i] * row[j]
                normal[i][8] += row[i] * target
            }
        }
        source.indices.forEach { i ->
            val x = source[i].x; val y = source[i].y; val u = destination[i].x; val v = destination[i].y
            accumulate(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y), u)
            accumulate(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y), v)
        }
        for (col in 0..7) {
            val pivot = (col..7).maxBy { abs(normal[it][col]) }
            if (abs(normal[pivot][col]) < 1e-12) return null
            val swap = normal[col]; normal[col] = normal[pivot]; normal[pivot] = swap
            val divisor = normal[col][col]
            for (j in col..8) normal[col][j] /= divisor
            for (row in 0..7) if (row != col) {
                val factor = normal[row][col]
                for (j in col..8) normal[row][j] -= factor * normal[col][j]
            }
        }
        return runCatching { Matrix3.of(DoubleArray(9) { if (it == 8) 1.0 else normal[it][8] }) }.getOrNull()
    }
}

sealed interface PoseTrackingState {
    data object Unregistered : PoseTrackingState
    data class Tracking(val pose: MetricBoardPose) : PoseTrackingState
    data class Lost(val reason: String, val lastRevision: Long?) : PoseTrackingState
}

class MetricPoseTracker(private val minimumConfidence: Double = 0.15) {
    private val state = AtomicReference<PoseTrackingState>(PoseTrackingState.Unregistered)
    fun submit(pose: MetricBoardPose): Boolean {
        if (pose.confidence < minimumConfidence) return false
        val previous = state.get()
        val previousRevision = when (previous) {
            is PoseTrackingState.Tracking -> previous.pose.revision
            is PoseTrackingState.Lost -> previous.lastRevision
            PoseTrackingState.Unregistered -> null
        }
        if (previousRevision != null && pose.revision < previousRevision) return false
        state.set(PoseTrackingState.Tracking(pose)); return true
    }
    fun snapshot(nowNanos: Long): PoseTrackingState {
        val current = state.get()
        if (current is PoseTrackingState.Tracking && nowNanos > current.pose.expiresAtNanos) {
            val lost = PoseTrackingState.Lost("pose expired", current.pose.revision)
            state.compareAndSet(current, lost)
            return state.get()
        }
        return current
    }
    fun invalidate(reason: String) {
        val revision = (state.get() as? PoseTrackingState.Tracking)?.pose?.revision
        state.set(PoseTrackingState.Lost(reason, revision))
    }
    fun clear() = state.set(PoseTrackingState.Unregistered)
}
