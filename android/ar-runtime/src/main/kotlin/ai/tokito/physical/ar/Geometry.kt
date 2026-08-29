package ai.tokito.physical.ar

import kotlin.math.abs
import kotlin.math.hypot

data class Point2(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) { "point must be finite" } }
}

data class Size2(val width: Double, val height: Double) {
    init { require(width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0) }
}

/** Row-major projective 3x3 transform. */
class Matrix3 private constructor(private val m: DoubleArray) {
    init { require(m.size == 9 && m.all(Double::isFinite)) }

    fun map(point: Point2): Point2? {
        val w = m[6] * point.x + m[7] * point.y + m[8]
        if (!w.isFinite() || abs(w) < EPSILON) return null
        val x = (m[0] * point.x + m[1] * point.y + m[2]) / w
        val y = (m[3] * point.x + m[4] * point.y + m[5]) / w
        return if (x.isFinite() && y.isFinite()) Point2(x, y) else null
    }

    /** `a then b` is represented as `b * a`. */
    fun then(next: Matrix3): Matrix3 = of(DoubleArray(9) { index ->
        val row = index / 3
        val col = index % 3
        (0..2).sumOf { k -> next.m[row * 3 + k] * m[k * 3 + col] }
    })

    fun inverse(): Matrix3? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (!det.isFinite() || abs(det) < EPSILON) return null
        return of(doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        ))
    }

    fun approximatelyEquals(other: Matrix3, tolerance: Double = 1e-8): Boolean =
        m.indices.all { abs(m[it] - other.m[it]) <= tolerance }

    companion object {
        private const val EPSILON = 1e-10
        val IDENTITY = of(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun of(values: DoubleArray): Matrix3 = Matrix3(values.copyOf())

        /** Exact four-point projective fit. Points must be ordered consistently. */
        fun homography(source: List<Point2>, destination: List<Point2>): Matrix3? {
            if (source.size != 4 || destination.size != 4) return null
            if (!isConvex(source) || !isConvex(destination)) return null
            val a = Array(8) { DoubleArray(9) }
            for (index in 0..3) {
                val s = source[index]
                val d = destination[index]
                val r = index * 2
                a[r][0] = s.x; a[r][1] = s.y; a[r][2] = 1.0
                a[r][6] = -d.x * s.x; a[r][7] = -d.x * s.y; a[r][8] = d.x
                a[r + 1][3] = s.x; a[r + 1][4] = s.y; a[r + 1][5] = 1.0
                a[r + 1][6] = -d.y * s.x; a[r + 1][7] = -d.y * s.y; a[r + 1][8] = d.y
            }
            for (col in 0 until 8) {
                val pivot = (col until 8).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < EPSILON) return null
                val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
                val divisor = a[col][col]
                for (j in col..8) a[col][j] /= divisor
                for (row in 0 until 8) if (row != col) {
                    val factor = a[row][col]
                    for (j in col..8) a[row][j] -= factor * a[col][j]
                }
            }
            val h = DoubleArray(9) { if (it == 8) 1.0 else a[it][8] }
            return runCatching { of(h) }.getOrNull()?.takeIf { candidate ->
                source.indices.all { idx ->
                    candidate.map(source[idx])?.let {
                        hypot(it.x - destination[idx].x, it.y - destination[idx].y) < 1e-4
                    } == true
                }
            }
        }

        private fun polygonArea(points: List<Point2>): Double = abs(points.indices.sumOf { i ->
            val next = points[(i + 1) % points.size]
            points[i].x * next.y - next.x * points[i].y
        }) / 2.0

        private fun isConvex(points: List<Point2>): Boolean {
            if (points.size != 4 || polygonArea(points) < EPSILON) return false
            val crosses = points.indices.map { index ->
                val a = points[index]
                val b = points[(index + 1) % points.size]
                val c = points[(index + 2) % points.size]
                (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            }
            return crosses.all { it > EPSILON } || crosses.all { it < -EPSILON }
        }
    }
}
