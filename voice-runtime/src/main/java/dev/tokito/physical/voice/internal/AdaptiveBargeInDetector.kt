package dev.tokito.physical.voice.internal

import dev.tokito.physical.voice.BargeInConfig
import kotlin.math.sqrt

internal class AdaptiveBargeInDetector(
    private val sampleRate: Int,
    private val config: BargeInConfig,
    private val onSpeech: (rms: Float, threshold: Float) -> Unit,
) {
    private var calibratedSamples = 0L
    private var noiseEnergySum = 0.0
    private val activeWindow = ArrayDeque<Boolean>()
    private var triggered = false

    fun reset() {
        calibratedSamples = 0
        noiseEnergySum = 0.0
        activeWindow.clear()
        triggered = false
    }

    fun accept(samples: FloatArray) {
        if (samples.isEmpty() || triggered) return
        val energy = samples.fold(0.0) { total, value -> total + value * value } / samples.size
        val rms = sqrt(energy).toFloat()
        val calibrationTarget = sampleRate * config.calibrationMillis / 1_000L
        if (calibratedSamples < calibrationTarget) {
            noiseEnergySum += energy * samples.size
            calibratedSamples += samples.size
            return
        }
        val noiseRms = sqrt(noiseEnergySum / calibratedSamples.coerceAtLeast(1)).toFloat()
        val threshold = maxOf(config.minimumRms, noiseRms * config.noiseMultiplier)
        activeWindow.addLast(rms >= threshold)
        while (activeWindow.size > config.rollingWindowFrames) activeWindow.removeFirst()
        if (activeWindow.count { it } >= config.requiredActiveFrames) {
            triggered = true
            onSpeech(rms, threshold)
        }
    }
}
