package dev.tokito.physical.voice.internal

import dev.tokito.physical.voice.ActivationConfig
import dev.tokito.physical.voice.VadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalActivationFrontEndTest {
    @Test
    fun vadCalibratesAndUsesAttackAndReleaseHysteresis() {
        val detector = AdaptiveVadDetector(
            16_000,
            VadConfig(calibrationMillis = 40, minimumRms = 0.01f, noiseMultiplier = 2f, attackFrames = 2, releaseFrames = 2),
        )
        repeat(3) { assertFalse(detector.accept(frame(0.001f)).isSpeech) }
        assertFalse(detector.accept(frame(0.1f)).isSpeech)
        val started = detector.accept(frame(0.1f))
        assertTrue(started.isSpeech)
        assertTrue(started.speechStarted)
        assertTrue(detector.accept(frame(0.001f)).isSpeech)
        assertFalse(detector.accept(frame(0.001f)).isSpeech)
    }

    @Test
    fun audioGateIncludesBoundedPreRollWhenSpeechStarts() {
        val gate = ActivationAudioGate(
            16_000,
            VadConfig(calibrationMillis = 20, minimumRms = 0.01f, noiseMultiplier = 2f, attackFrames = 1, releaseFrames = 2, preRollMillis = 40),
        )
        val forwarded = mutableListOf<FloatArray>()
        repeat(3) { gate.accept(frame(0.001f), forwarded::add) }
        gate.accept(frame(0.1f), forwarded::add)
        assertEquals(3, forwarded.size)
        assertTrue(forwarded.last().all { it != 0f })
    }

    @Test
    fun matcherAcceptsConfiguredPhraseAndRejectsUnrelatedText() {
        val matcher = WakePhraseMatcher(ActivationConfig(minimumWakeConfidence = 0.82f))
        val match = matcher.match("please, hey Tokito!", 0.9f)
        assertNotNull(match)
        assertEquals("hey tokito", match?.phrase)
        assertTrue(match!!.confidence >= 0.82f)
        assertEquals(null, matcher.match("place the resistor", 1f))
    }

    private fun frame(amplitude: Float) = FloatArray(320) { if (it % 2 == 0) amplitude else -amplitude }
}
