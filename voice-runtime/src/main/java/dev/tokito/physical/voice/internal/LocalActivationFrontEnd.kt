package dev.tokito.physical.voice.internal

import dev.tokito.physical.voice.ActivationConfig
import dev.tokito.physical.voice.VadConfig
import kotlin.math.sqrt

internal data class VadFrame(
    val confidence: Float,
    val rms: Float,
    val noiseFloor: Float,
    val isSpeech: Boolean,
    val speechStarted: Boolean,
)

internal class AdaptiveVadDetector(
    sampleRate: Int,
    private val config: VadConfig,
) {
    private val calibrationFrames = (config.calibrationMillis * sampleRate / 1_000 / FRAME_SAMPLES)
        .toInt().coerceAtLeast(1)
    private var frames = 0
    private var noiseFloor = config.minimumRms / config.noiseMultiplier
    private var activeFrames = 0
    private var quietFrames = 0
    private var speech = false

    fun accept(samples: FloatArray): VadFrame {
        val rms = sqrt(samples.fold(0.0) { sum, value -> sum + value * value } / samples.size.coerceAtLeast(1)).toFloat()
        if (frames < calibrationFrames || !speech) {
            val alpha = if (frames < calibrationFrames) 0.12f else 0.015f
            noiseFloor = (noiseFloor * (1f - alpha) + rms * alpha).coerceAtLeast(0.0001f)
        }
        frames++
        val threshold = maxOf(config.minimumRms, noiseFloor * config.noiseMultiplier)
        val ratio = if (threshold > 0f) rms / threshold else 0f
        val confidence = ((ratio - 0.55f) / 1.45f).coerceIn(0f, 1f)
        val above = frames > calibrationFrames && rms >= threshold
        if (above) {
            activeFrames++
            quietFrames = 0
        } else {
            activeFrames = 0
            quietFrames++
        }
        val started = !speech && activeFrames >= config.attackFrames
        if (started) speech = true
        if (speech && quietFrames >= config.releaseFrames) speech = false
        return VadFrame(confidence, rms, noiseFloor, speech, started)
    }

    companion object { private const val FRAME_SAMPLES = 320 }
}

internal data class WakeMatch(val phrase: String, val confidence: Float)

internal class WakePhraseMatcher(private val config: ActivationConfig) {
    private val phrases = config.wakePhrases.map(::normalize).filter { it.isNotBlank() }.distinct()

    init {
        require(phrases.isNotEmpty()) { "at least one wake phrase is required" }
        require(config.minimumWakeConfidence in 0f..1f) { "wake confidence must be between zero and one" }
    }

    fun match(transcript: String, vadConfidence: Float): WakeMatch? {
        val words = normalize(transcript).split(' ').filter(String::isNotBlank)
        var best: WakeMatch? = null
        phrases.forEach { phrase ->
            val phraseWords = phrase.split(' ')
            val windows = if (words.size >= phraseWords.size) words.windowed(phraseWords.size) else listOf(words)
            windows.forEach { window ->
                val candidate = window.joinToString(" ")
                val distance = levenshtein(candidate, phrase)
                val textConfidence = 1f - distance.toFloat() / maxOf(candidate.length, phrase.length, 1)
                val confidence = (textConfidence * 0.9f + vadConfidence.coerceIn(0f, 1f) * 0.1f).coerceIn(0f, 1f)
                if (best == null || confidence > best!!.confidence) best = WakeMatch(phrase, confidence)
            }
        }
        return best?.takeIf { it.confidence >= config.minimumWakeConfidence }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, l ->
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            right.forEachIndexed { j, r ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (l == r) 0 else 1)
            }
            previous = current
        }
        return previous[right.length]
    }
}

internal class ActivationAudioGate(
    sampleRate: Int,
    config: VadConfig,
) {
    private val detector = AdaptiveVadDetector(sampleRate, config)
    private val preRollFrames = (config.preRollMillis * sampleRate / 1_000 / 320).toInt().coerceAtLeast(1)
    private val preRoll = ArrayDeque<FloatArray>()

    fun accept(samples: FloatArray, consumer: (FloatArray) -> Unit): VadFrame {
        val frame = detector.accept(samples)
        if (frame.speechStarted) {
            preRoll.forEach(consumer)
            preRoll.clear()
        }
        if (frame.isSpeech) {
            consumer(samples)
        } else {
            preRoll.addLast(samples.copyOf())
            while (preRoll.size > preRollFrames) preRoll.removeFirst()
        }
        return frame
    }
}
