package dev.tokito.physical

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentationConfidenceLabelTest {
    @Test fun showsOnlyRoundedPercentage() {
        assertEquals("64%", segmentationConfidenceLabel(0.641f))
        assertEquals("100%", segmentationConfidenceLabel(1f))
        assertEquals("0%", segmentationConfidenceLabel(0f))
    }
    @Test fun boundsScoresAndNeverPresentsNonfiniteCertainty() {
        assertEquals("0%", segmentationConfidenceLabel(-1f))
        assertEquals("100%", segmentationConfidenceLabel(2f))
        assertEquals("—", segmentationConfidenceLabel(Float.NaN))
        assertEquals("—", segmentationConfidenceLabel(Float.POSITIVE_INFINITY))
    }
}
