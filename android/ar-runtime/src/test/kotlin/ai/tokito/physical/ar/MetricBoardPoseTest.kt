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

    @Test fun trackerExpiresAndDoesNotReuseStalePose() {
        val image = board.map { Point2(640.0 + 2000.0 * it.x, 360.0 + 2000.0 * it.y) }
        val pose = assertIs<PoseEstimation.Accepted>(MetricPoseEstimator(validityNanos = 50).estimate(board, image, intrinsics, 1, 10)).pose
        val tracker = MetricPoseTracker()
        assertTrue(tracker.submit(pose))
        assertIs<PoseTrackingState.Tracking>(tracker.snapshot(60))
        assertIs<PoseTrackingState.Lost>(tracker.snapshot(61))
    }
}
