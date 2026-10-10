package dev.tokito.physical

import ai.tokito.physical.ar.Point2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoseStabilizerTest {
    private val manifest = LocalPoseVisionRuntime.Manifest(
        modelSha256 = "0".repeat(64),
        inputSize = 640,
        classConfidence = 0.7f,
        keypointConfidence = 0.45f,
        minimumBoardAreaRatio = 0.02,
        maximumCornerJitterRatio = 0.04,
        stableFrameCount = 3,
        maximumObservationAgeNanos = 1_500_000_000L,
        coordinatesNormalized = true,
    )

    private fun observation(timestampNanos: Long, shift: Double = 0.0) = PoseVisionObservation(
        corners = listOf(
            Point2(100.0 + shift, 100.0),
            Point2(500.0 + shift, 100.0),
            Point2(500.0 + shift, 300.0),
            Point2(100.0 + shift, 300.0),
        ),
        confidence = 0.9,
        timestampNanos = timestampNanos,
    )

    @Test
    fun `emits only after the stability window fills`() {
        val stabilizer = PoseStabilizer(manifest)
        val base = System.nanoTime()
        assertNull(stabilizer.accept(observation(base)))
        assertNull(stabilizer.accept(observation(base + 20_000_000L)))
        assertNotNull(stabilizer.accept(observation(base + 40_000_000L)))
    }

    @Test
    fun `camera geometry reset requires a fresh full stability window`() {
        val stabilizer = PoseStabilizer(manifest)
        val base = System.nanoTime()
        stabilizer.accept(observation(base))
        stabilizer.accept(observation(base + 20_000_000L))
        stabilizer.reset()
        assertNull(stabilizer.accept(observation(base + 40_000_000L)))
        assertNull(stabilizer.accept(observation(base + 60_000_000L)))
        assertNotNull(stabilizer.accept(observation(base + 80_000_000L)))
    }

    @Test
    fun `a single rejected frame does not reset the stability window`() {
        val stabilizer = PoseStabilizer(manifest)
        val base = System.nanoTime()
        assertNull(stabilizer.accept(observation(base)))
        assertNull(stabilizer.accept(observation(base + 20_000_000L)))
        assertNull(stabilizer.reject())
        // Before the fix this required three further consecutive accepted
        // frames; the fresh window must survive one sub-threshold frame.
        assertNotNull(stabilizer.accept(observation(base + 40_000_000L)))
    }

    @Test
    fun `a detection gap beyond the freshness bound drains the window`() {
        val stabilizer = PoseStabilizer(manifest)
        val base = System.nanoTime()
        assertNull(stabilizer.accept(observation(base)))
        assertNull(stabilizer.accept(observation(base + 20_000_000L)))
        val afterGap = base + manifest.maximumObservationAgeNanos + 40_000_000L
        // Stale entries are evicted, so the window restarts from one frame.
        assertNull(stabilizer.accept(observation(afterGap)))
        assertNull(stabilizer.accept(observation(afterGap + 20_000_000L)))
        assertNotNull(stabilizer.accept(observation(afterGap + 40_000_000L)))
    }

    @Test
    fun `a corner jump beyond the jitter bound restarts the window`() {
        val stabilizer = PoseStabilizer(manifest)
        val base = System.nanoTime()
        assertNull(stabilizer.accept(observation(base)))
        assertNull(stabilizer.accept(observation(base + 20_000_000L)))
        // Shift far beyond maximumCornerJitterRatio of the diagonal.
        assertNull(stabilizer.accept(observation(base + 40_000_000L, shift = 200.0)))
        assertNull(stabilizer.accept(observation(base + 60_000_000L, shift = 200.0)))
        assertNotNull(stabilizer.accept(observation(base + 80_000_000L, shift = 200.0)))
    }

    @Test
    fun `emitted pose averages the window and reports its weakest confidence`() {
        val stabilizer = PoseStabilizer(manifest)
        val base = System.nanoTime()
        stabilizer.accept(observation(base, shift = 0.0))
        stabilizer.accept(observation(base + 20_000_000L, shift = 3.0))
        val emitted = stabilizer.accept(observation(base + 40_000_000L, shift = 6.0))
        assertNotNull(emitted)
        assertEquals(103.0, emitted!!.corners[0].x, 1e-6)
        assertEquals(0.9, emitted.confidence, 1e-6)
    }
}
