package ai.tokito.physical.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MetricBoardPoseTest {
    private val intrinsics = CameraIntrinsics(1000.0, 1000.0, 640.0, 360.0, 1280, 720)
    private val board = listOf(Point2(0.0, 0.0), Point2(0.12, 0.0), Point2(0.12, 0.08), Point2(0.0, 0.08))

    @Test fun recoversMetricFrontFacingPose() {
        val image = board.map { Point2(640.0 + 2000.0 * it.x, 360.0 + 2000.0 * it.y) }
        val result = MetricPoseEstimator().estimate(board, image, intrinsics, 7, 100)
        val pose = assertIs<PoseEstimation.Accepted>(result).pose
        assertTrue(kotlin.math.abs(pose.translationMetres.z - 0.5) < 1e-6)
        assertTrue(pose.reprojectionRmsPx < 1e-6)
        assertEquals(7, pose.revision)
    }

    @Test fun rejectsInconsistentCorrespondences() {
        val image = listOf(Point2(100.0, 100.0), Point2(900.0, 120.0), Point2(650.0, 150.0), Point2(120.0, 650.0))
        assertIs<PoseEstimation.Rejected>(MetricPoseEstimator(maximumRmsPx = 0.01).estimate(board, image, intrinsics, 1, 1))
    }

    @Test fun recoversTiltedBoardWithNonzeroSourceOrigin() {
        val angle = 0.5
        val r1 = Vector3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle))
        val r2 = Vector3(0.0, 1.0, 0.0)
        val translation = Vector3(-0.08, -0.04, 0.6)
        val landmarks = board.map { Point2(it.x + 0.025, it.y - 0.015) }
        val image = landmarks.map { point ->
            val x = r1.x * point.x + r2.x * point.y + translation.x
            val y = r1.y * point.x + r2.y * point.y + translation.y
            val z = r1.z * point.x + r2.z * point.y + translation.z
            Point2(intrinsics.fx * x / z + intrinsics.cx, intrinsics.fy * y / z + intrinsics.cy)
        }
        val pose = assertIs<PoseEstimation.Accepted>(MetricPoseEstimator()
            .estimate(landmarks, image, intrinsics, 9, 100)).pose
        assertTrue(pose.reprojectionRmsPx < 1e-5)
        assertTrue(kotlin.math.abs(pose.r1.z - r1.z) < 1e-5)
        assertTrue(kotlin.math.abs(pose.translationMetres.z - translation.z) < 1e-5)
        val transform = pose.filamentTransformForYUpAsset()
        assertTrue(kotlin.math.abs(transform[2] + r1.z) < 1e-5)
        assertTrue(kotlin.math.abs(transform[14] + translation.z) < 1e-5)
    }

    @Test fun trackerExpiresAndDoesNotReuseStalePose() {
        val image = board.map { Point2(640.0 + 2000.0 * it.x, 360.0 + 2000.0 * it.y) }
        val pose = assertIs<PoseEstimation.Accepted>(MetricPoseEstimator(validityNanos = 50).estimate(board, image, intrinsics, 1, 10)).pose
        val tracker = MetricPoseTracker()
        assertTrue(tracker.submit(pose))
        assertIs<PoseTrackingState.Tracking>(tracker.snapshot(60))
        assertIs<PoseTrackingState.Lost>(tracker.snapshot(61))
    }
}
