package ai.tokito.physical.ar

import java.util.ArrayDeque

@JvmInline
value class CoordinateFrame(val id: String) {
    init { require(id.isNotBlank()) }
    companion object {
        val SENSOR = CoordinateFrame("sensor")
        val UPRIGHT_IMAGE = CoordinateFrame("upright_image")
        val CROP = CoordinateFrame("crop")
        val PREVIEW = CoordinateFrame("preview")
        val BOARD = CoordinateFrame("board")
        val DESIGN = CoordinateFrame("design")
    }
}

data class FrameTransform(
    val source: CoordinateFrame,
    val destination: CoordinateFrame,
    val matrix: Matrix3,
    val revision: Long,
    val capturedAtNanos: Long,
    val expiresAtNanos: Long,
    val uncertaintyPx: Double,
) {
    init {
        require(source != destination)
        require(revision >= 0 && capturedAtNanos >= 0 && expiresAtNanos >= capturedAtNanos)
        require(uncertaintyPx.isFinite() && uncertaintyPx >= 0)
        require(matrix.inverse() != null) { "transform must be invertible" }
    }
}

data class ResolvedTransform(
    val matrix: Matrix3,
    val revision: Long,
    val uncertaintyPx: Double,
    val expiresAtNanos: Long,
)

/** Immutable, cycle-safe graph. A resolution never mixes registration revisions. */
class FrameGraph(transforms: Collection<FrameTransform>) {
    private val edges: Map<CoordinateFrame, List<FrameTransform>>

    init {
        require(transforms.groupBy { it.revision }.values.none(::containsDirectedCycle)) {
            "supplied transforms must not contain a directed cycle"
        }
        val directed = mutableListOf<FrameTransform>()
        transforms.forEach { transform ->
            directed += transform
            directed += transform.copy(
                source = transform.destination,
                destination = transform.source,
                matrix = requireNotNull(transform.matrix.inverse()),
            )
        }
        require(directed.groupBy { it.source to it.destination }.values.none { candidates ->
            candidates.groupBy { it.revision }.values.any { it.size > 1 }
        }) { "duplicate edge for the same revision" }
        edges = directed.groupBy { it.source }
    }

    private fun containsDirectedCycle(revisionEdges: List<FrameTransform>): Boolean {
        val adjacency = revisionEdges.groupBy { it.source }
        val visited = mutableSetOf<CoordinateFrame>()
        val active = mutableSetOf<CoordinateFrame>()
        fun visit(frame: CoordinateFrame): Boolean {
            if (frame in active) return true
            if (!visited.add(frame)) return false
            active += frame
            val cyclic = adjacency[frame].orEmpty().any { visit(it.destination) }
            active -= frame
            return cyclic
        }
        return revisionEdges.flatMap { listOf(it.source, it.destination) }.any(::visit)
    }

    fun resolve(
        source: CoordinateFrame,
        destination: CoordinateFrame,
        revision: Long,
        nowNanos: Long,
    ): ResolvedTransform? {
        if (source == destination) return ResolvedTransform(Matrix3.IDENTITY, revision, 0.0, Long.MAX_VALUE)
        data class Cursor(
            val frame: CoordinateFrame,
            val matrix: Matrix3,
            val uncertainty: Double,
            val expiry: Long,
            val visited: Set<CoordinateFrame>,
        )
        val queue = ArrayDeque<Cursor>()
        queue += Cursor(source, Matrix3.IDENTITY, 0.0, Long.MAX_VALUE, setOf(source))
        while (queue.isNotEmpty()) {
            val cursor = queue.removeFirst()
            for (edge in edges[cursor.frame].orEmpty()) {
                if (edge.revision != revision || nowNanos > edge.expiresAtNanos || edge.destination in cursor.visited) continue
                val next = Cursor(
                    edge.destination,
                    cursor.matrix.then(edge.matrix),
                    cursor.uncertainty + edge.uncertaintyPx,
                    minOf(cursor.expiry, edge.expiresAtNanos),
                    cursor.visited + edge.destination,
                )
                if (next.frame == destination) {
                    return ResolvedTransform(next.matrix, revision, next.uncertainty, next.expiry)
                }
                queue += next
            }
        }
        return null
    }
}
