package dev.tokito.physical.voice.internal

import dev.tokito.physical.voice.BargeInConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBargeInDetectorTest {
    @Test
    fun ignoresCalibrationAndTriggersOnlyAfterSustainedSpeech() {
        val events = mutableListOf<Pair<Float, Float>>()
        val detector = AdaptiveBargeInDetector(
            sampleRate = 1_000,
            config = BargeInConfig(
                calibrationMillis = 100,
                minimumRms = 0.02f,
                noiseMultiplier = 3f,
                requiredActiveFrames = 3,
                rollingWindowFrames = 4,
            ),
        ) { rms, threshold -> events += rms to threshold }

        repeat(2) { detector.accept(FloatArray(50) { 0.005f }) }
        repeat(2) { detector.accept(FloatArray(20) { 0.08f }) }
        assertTrue(events.isEmpty())
        detector.accept(FloatArray(20) { 0.08f })

        assertEquals(1, events.size)
        assertTrue(events.single().first >= events.single().second)
    }

    @Test
    fun resetRequiresFreshCalibration() {
        var triggers = 0
        val detector = AdaptiveBargeInDetector(
            1_000,
            BargeInConfig(40, 0.01f, 2f, 1, 1),
        ) { _, _ -> triggers++ }
        detector.accept(FloatArray(40) { 0.001f })
        detector.accept(FloatArray(20) { 0.1f })
        assertEquals(1, triggers)

        detector.reset()
        detector.accept(FloatArray(20) { 0.1f })
        assertEquals(1, triggers)
    }
}
