package dev.tokito.physical

import kotlin.math.roundToInt

/** Presentation of a model score, not calibrated factual/identity confidence. */
internal fun segmentationConfidenceLabel(score: Float): String =
    if (score.isFinite()) "${(score.coerceIn(0f, 1f) * 100).roundToInt()}%" else "—"
