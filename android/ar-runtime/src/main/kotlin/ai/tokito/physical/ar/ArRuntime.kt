package ai.tokito.physical.ar

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class Registration(
    val revision: Long,
    val transform: FrameTransform,
    val boardCornersInPreview: List<Point2>,
)

class ManualBoardRegistrar {
    private val revisions = AtomicLong(0)

    fun register(
        previewCornersClockwise: List<Point2>,
        nowNanos: Long,
        validityNanos: Long = 30_000_000_000L,
        uncertaintyPx: Double = 8.0,
    ): Registration? {
        if (
            previewCornersClockwise.size != 4 || nowNanos < 0 || validityNanos <= 0 ||
            !uncertaintyPx.isFinite() || uncertaintyPx < 0
        ) return null
        val expiresAt = runCatching { Math.addExact(nowNanos, validityNanos) }.getOrNull() ?: return null
        val board = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        val matrix = Matrix3.homography(board, previewCornersClockwise) ?: return null
        val revision = revisions.incrementAndGet()
        return Registration(
            revision,
            FrameTransform(
                CoordinateFrame.BOARD,
                CoordinateFrame.PREVIEW,
                matrix,
                revision,
                nowNanos,
                expiresAt,
                uncertaintyPx,
            ),
            previewCornersClockwise.toList(),
        )
    }
}

/** Thread-safe coordinator shared by a detector/guide producer and the UI thread. */
class ArRuntime(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val graph = AtomicReference(FrameGraph(emptyList()))
    private val revision = AtomicLong(-1)
    private val scenes = OverlaySceneStore()
    private val projector = OverlayProjector()

    fun installTransforms(transforms: Collection<FrameTransform>, transformRevision: Long): Boolean {
        if (transformRevision < 0 || transforms.any { it.revision != transformRevision }) return false
        val next = runCatching { FrameGraph(transforms) }.getOrNull() ?: return false
        while (true) {
            val prior = revision.get()
            if (transformRevision <= prior) return false
            if (revision.compareAndSet(prior, transformRevision)) {
                graph.set(next)
                scenes.invalidate()
                return true
            }
        }
    }

    fun submit(scene: OverlayScene): SceneSubmission {
        if (scene.transformRevision != revision.get()) return SceneSubmission.Rejected("scene transform revision is not current")
        return scenes.submit(scene)
    }

    fun render(): RenderDecision {
        val now = clockNanos()
        val activeRevision = revision.get()
        if (activeRevision < 0) return RenderDecision.Suppressed("registration unavailable")
        val scene = scenes.snapshot(now, activeRevision) ?: return RenderDecision.Suppressed("current scene unavailable")
        return projector.project(scene, graph.get(), now)
    }

    fun suspend() {
        revision.set(-1)
        graph.set(FrameGraph(emptyList()))
        scenes.invalidate()
    }
}
