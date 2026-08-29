package ai.tokito.physical.ar

import java.util.concurrent.atomic.AtomicReference

enum class OverlayTone { Primary, Success, Warning, Danger, Muted }

sealed interface OverlayPrimitive {
    val id: String
    val frame: CoordinateFrame
    val transformRevision: Long
    val observedAtNanos: Long
    val expiresAtNanos: Long
    val uncertaintyPx: Double
    val label: String?
    val tone: OverlayTone

    data class Outline(
        override val id: String,
        override val frame: CoordinateFrame,
        override val transformRevision: Long,
        override val observedAtNanos: Long,
        override val expiresAtNanos: Long,
        override val uncertaintyPx: Double,
        override val label: String?,
        override val tone: OverlayTone,
        val points: List<Point2>,
        val closed: Boolean = true,
    ) : OverlayPrimitive { init { require(points.size >= 2); validateCommon() } }

    data class Target(
        override val id: String,
        override val frame: CoordinateFrame,
        override val transformRevision: Long,
        override val observedAtNanos: Long,
        override val expiresAtNanos: Long,
        override val uncertaintyPx: Double,
        override val label: String?,
        override val tone: OverlayTone,
        val center: Point2,
        val radius: Double,
        val directionRadians: Double? = null,
    ) : OverlayPrimitive {
        init {
            validateCommon()
            require(radius.isFinite() && radius > 0)
            require(directionRadians == null || directionRadians.isFinite())
        }
    }

    data class Region(
        override val id: String,
        override val frame: CoordinateFrame,
        override val transformRevision: Long,
        override val observedAtNanos: Long,
        override val expiresAtNanos: Long,
        override val uncertaintyPx: Double,
        override val label: String?,
        override val tone: OverlayTone,
        val topLeft: Point2,
        val bottomRight: Point2,
    ) : OverlayPrimitive {
        init {
            validateCommon()
            require(bottomRight.x > topLeft.x && bottomRight.y > topLeft.y)
        }
    }

    data class Anchor(
        override val id: String,
        override val frame: CoordinateFrame,
        override val transformRevision: Long,
        override val observedAtNanos: Long,
        override val expiresAtNanos: Long,
        override val uncertaintyPx: Double,
        override val label: String?,
        override val tone: OverlayTone,
        val point: Point2,
    ) : OverlayPrimitive { init { validateCommon() } }

}

private fun OverlayPrimitive.validateCommon() {
    require(id.isNotBlank())
    require(transformRevision >= 0)
    require(observedAtNanos >= 0 && expiresAtNanos >= observedAtNanos)
    require(uncertaintyPx.isFinite() && uncertaintyPx >= 0)
}

data class Guidance(
    val title: String,
    val detail: String,
    val step: Int? = null,
    val stepCount: Int? = null,
    val confidence: Double? = null,
    val abstentionReason: String? = null,
) {
    init {
        require(title.isNotBlank())
        require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0)
        require((step == null && stepCount == null) || (step != null && stepCount != null && step in 1..stepCount))
    }
}

data class OverlayScene(
    val sequence: Long,
    val transformRevision: Long,
    val generatedAtNanos: Long,
    val expiresAtNanos: Long,
    val primitives: List<OverlayPrimitive>,
    val guidance: Guidance,
) {
    init {
        require(sequence >= 0 && transformRevision >= 0)
        require(generatedAtNanos >= 0 && expiresAtNanos >= generatedAtNanos)
        require(primitives.map { it.id }.distinct().size == primitives.size)
        primitives.forEach {
            require(it.transformRevision == transformRevision)
            require(it.observedAtNanos <= generatedAtNanos)
        }
    }
}

sealed interface SceneSubmission {
    data object Accepted : SceneSubmission
    data class Rejected(val reason: String) : SceneSubmission
}

/** Single-slot, bounded mailbox: producers cannot accumulate stale render work. */
class OverlaySceneStore {
    private val current = AtomicReference<OverlayScene?>(null)

    fun submit(scene: OverlayScene): SceneSubmission {
        while (true) {
            val previous = current.get()
            if (previous != null && scene.sequence <= previous.sequence) {
                return SceneSubmission.Rejected("scene sequence is not newer")
            }
            if (current.compareAndSet(previous, scene)) return SceneSubmission.Accepted
        }
    }

    fun snapshot(nowNanos: Long, transformRevision: Long): OverlayScene? = current.get()?.takeIf {
        nowNanos <= it.expiresAtNanos && it.transformRevision == transformRevision
    }

    fun invalidate() { current.set(null) }
}

sealed interface RenderDecision {
    data class Ready(val scene: RenderScene) : RenderDecision
    data class Suppressed(val reason: String) : RenderDecision
}

data class RenderPrimitive(val source: OverlayPrimitive, val points: List<Point2>, val uncertaintyPx: Double)
data class RenderScene(val primitives: List<RenderPrimitive>, val guidance: Guidance)

class OverlayProjector(private val previewFrame: CoordinateFrame = CoordinateFrame.PREVIEW) {
    fun project(scene: OverlayScene, graph: FrameGraph, nowNanos: Long): RenderDecision {
        if (nowNanos > scene.expiresAtNanos) return RenderDecision.Suppressed("scene expired")
        if (scene.guidance.abstentionReason != null) {
            return RenderDecision.Ready(RenderScene(emptyList(), scene.guidance))
        }
        val projected = mutableListOf<RenderPrimitive>()
        for (primitive in scene.primitives) {
            if (nowNanos > primitive.expiresAtNanos) {
                return RenderDecision.Suppressed("primitive expired: ${primitive.id}")
            }
            val transform = graph.resolve(primitive.frame, previewFrame, primitive.transformRevision, nowNanos)
                ?: return RenderDecision.Suppressed("missing current transform: ${primitive.frame.id} -> ${previewFrame.id}")
            val points = primitive.points().map { transform.matrix.map(it) ?: return RenderDecision.Suppressed("non-finite projection") }
            projected += RenderPrimitive(primitive, points, primitive.uncertaintyPx + transform.uncertaintyPx)
        }
        return RenderDecision.Ready(RenderScene(projected, scene.guidance))
    }

    private fun OverlayPrimitive.points(): List<Point2> = when (this) {
        is OverlayPrimitive.Outline -> points
        is OverlayPrimitive.Target -> buildList {
            add(center)
            add(Point2(center.x + radius, center.y))
            directionRadians?.let { angle ->
                add(Point2(center.x + kotlin.math.cos(angle) * radius, center.y + kotlin.math.sin(angle) * radius))
            }
        }
        is OverlayPrimitive.Region -> listOf(
            topLeft,
            Point2(bottomRight.x, topLeft.y),
            bottomRight,
            Point2(topLeft.x, bottomRight.y),
        )
        is OverlayPrimitive.Anchor -> listOf(point)
    }
}
