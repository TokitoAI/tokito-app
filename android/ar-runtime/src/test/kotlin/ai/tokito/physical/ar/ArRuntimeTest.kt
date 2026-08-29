package ai.tokito.physical.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArRuntimeTest {
    @Test
    fun homography_maps_all_four_corners_and_round_trips() {
        val source = unitSquare()
        val destination = listOf(Point2(40.0, 80.0), Point2(500.0, 50.0), Point2(470.0, 700.0), Point2(65.0, 680.0))
        val matrix = assertNotNull(Matrix3.homography(source, destination))
        source.indices.forEach { assertPoint(destination[it], assertNotNull(matrix.map(source[it]))) }
        val inverse = assertNotNull(matrix.inverse())
        source.indices.forEach { assertPoint(source[it], assertNotNull(inverse.map(destination[it]))) }
    }

    @Test
    fun homography_rejects_degenerate_points() {
        val line = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(2.0, 0.0), Point2(3.0, 0.0))
        assertNull(Matrix3.homography(unitSquare(), line))
    }

    @Test
    fun frame_graph_composes_and_inverts_without_mixing_revisions() {
        val sensor = CoordinateFrame.SENSOR
        val crop = CoordinateFrame.CROP
        val preview = CoordinateFrame.PREVIEW
        val translate = Matrix3.of(doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, 20.0, 0.0, 0.0, 1.0))
        val scale = Matrix3.of(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 1.0))
        val graph = FrameGraph(listOf(
            transform(sensor, crop, translate, revision = 4),
            transform(crop, preview, scale, revision = 4),
            transform(crop, preview, scale, revision = 5),
        ))
        val forward = assertNotNull(graph.resolve(sensor, preview, 4, 10))
        assertPoint(Point2(22.0, 66.0), assertNotNull(forward.matrix.map(Point2(1.0, 2.0))))
        val reverse = assertNotNull(graph.resolve(preview, sensor, 4, 10))
        assertPoint(Point2(1.0, 2.0), assertNotNull(reverse.matrix.map(Point2(22.0, 66.0))))
        assertNull(graph.resolve(sensor, preview, 5, 10))
    }

    @Test
    fun frame_graph_rejects_expired_paths_and_cycles_terminate() {
        val graph = FrameGraph(listOf(
            transform(CoordinateFrame.BOARD, CoordinateFrame.DESIGN, Matrix3.IDENTITY, expires = 20),
            transform(CoordinateFrame.DESIGN, CoordinateFrame.PREVIEW, Matrix3.IDENTITY, expires = 30),
        ))
        assertNotNull(graph.resolve(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, 1, 20))
        assertNull(graph.resolve(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, 1, 21))
        assertNull(graph.resolve(CoordinateFrame.BOARD, CoordinateFrame.SENSOR, 1, 10))
    }

    @Test
    fun frame_graph_rejects_supplied_cycles() {
        val a = CoordinateFrame("a")
        val b = CoordinateFrame("b")
        assertFailsWith<IllegalArgumentException> {
            FrameGraph(listOf(transform(a, b, Matrix3.IDENTITY), transform(b, a, Matrix3.IDENTITY)))
        }
    }

    @Test
    fun scene_store_is_bounded_ordered_and_revision_scoped() {
        val store = OverlaySceneStore()
        assertIs<SceneSubmission.Accepted>(store.submit(scene(sequence = 2, revision = 3)))
        assertIs<SceneSubmission.Rejected>(store.submit(scene(sequence = 2, revision = 3)))
        assertIs<SceneSubmission.Rejected>(store.submit(scene(sequence = 1, revision = 3)))
        assertEquals(2, store.snapshot(10, 3)?.sequence)
        assertNull(store.snapshot(10, 4))
        assertNull(store.snapshot(101, 3))
        store.invalidate()
        assertNull(store.snapshot(10, 3))
    }

    @Test
    fun projector_fails_closed_when_any_required_transform_is_missing() {
        val result = OverlayProjector().project(scene(1, 8), FrameGraph(emptyList()), 10)
        assertIs<RenderDecision.Suppressed>(result)
    }

    @Test
    fun projector_suppresses_the_scene_when_a_primitive_expires() {
        val expiring = scene(1, 8).let { original ->
            original.copy(
                expiresAtNanos = 200,
                primitives = original.primitives.map { (it as OverlayPrimitive.Outline).copy(expiresAtNanos = 20) },
            )
        }
        val graph = FrameGraph(listOf(transform(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, Matrix3.IDENTITY, 8, 200)))
        assertIs<RenderDecision.Suppressed>(OverlayProjector().project(expiring, graph, 21))
    }

    @Test
    fun abstention_renders_guidance_but_no_geometry() {
        val abstaining = scene(1, 8, guidance = Guidance("Cannot place", "Move closer", abstentionReason = "registration confidence too low"))
        val decision = OverlayProjector().project(abstaining, FrameGraph(emptyList()), 10)
        assertIs<RenderDecision.Ready>(decision)
        assertTrue(decision.scene.primitives.isEmpty())
    }

    @Test
    fun runtime_invalidates_scenes_on_new_registration_and_suspend() {
        var now = 10L
        val runtime = ArRuntime { now }
        assertTrue(runtime.installTransforms(listOf(transform(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, Matrix3.IDENTITY, 1)), 1))
        assertIs<SceneSubmission.Accepted>(runtime.submit(scene(1, 1)))
        assertIs<RenderDecision.Ready>(runtime.render())
        assertTrue(runtime.installTransforms(listOf(transform(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, Matrix3.IDENTITY, 2)), 2))
        assertIs<RenderDecision.Suppressed>(runtime.render())
        assertFalse(runtime.installTransforms(listOf(transform(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, Matrix3.IDENTITY, 1)), 1))
        runtime.suspend()
        assertIs<RenderDecision.Suppressed>(runtime.render())
    }

    @Test
    fun runtime_rejects_scene_from_wrong_transform_revision() {
        val runtime = ArRuntime { 10 }
        assertTrue(runtime.installTransforms(listOf(transform(CoordinateFrame.BOARD, CoordinateFrame.PREVIEW, Matrix3.IDENTITY, 7)), 7))
        assertIs<SceneSubmission.Rejected>(runtime.submit(scene(1, 6)))
    }

    @Test
    fun manual_registration_revision_is_monotonic() {
        val registrar = ManualBoardRegistrar()
        val first = assertNotNull(registrar.register(unitSquare(), 1))
        val second = assertNotNull(registrar.register(unitSquare(), 2))
        assertEquals(first.revision + 1, second.revision)
    }

    @Test
    fun manual_registration_rejects_invalid_timing_and_uncertainty_without_throwing() {
        val registrar = ManualBoardRegistrar()
        assertNull(registrar.register(unitSquare(), -1))
        assertNull(registrar.register(unitSquare(), Long.MAX_VALUE, validityNanos = 1))
        assertNull(registrar.register(unitSquare(), 1, uncertaintyPx = Double.NaN))
    }

    private fun scene(sequence: Long, revision: Long, guidance: Guidance = Guidance("Place part", "Align with target")): OverlayScene =
        OverlayScene(sequence, revision, 0, 100, listOf(
            OverlayPrimitive.Outline(
                "board", CoordinateFrame.BOARD, revision, 0, 100, 1.0, "Board", OverlayTone.Primary, unitSquare(),
            ),
        ), guidance)

    private fun transform(
        source: CoordinateFrame,
        destination: CoordinateFrame,
        matrix: Matrix3,
        revision: Long = 1,
        expires: Long = 100,
    ) = FrameTransform(source, destination, matrix, revision, 0, expires, 1.0)

    private fun unitSquare() = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))

    private fun assertPoint(expected: Point2, actual: Point2) {
        assertTrue(kotlin.math.abs(expected.x - actual.x) < 1e-5, "$expected != $actual")
        assertTrue(kotlin.math.abs(expected.y - actual.y) < 1e-5, "$expected != $actual")
    }
}
