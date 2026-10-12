package dev.tokito.physical

import org.junit.Assert.*
import org.junit.Test

class TrackingAcquisitionTest {
    @Test fun requiresFreshConsecutiveEvidence() {
        val gate=TrackingAcquisition()
        assertFalse(gate.accept("a",1))
        assertFalse(gate.accept("a",1))
        assertFalse(gate.accept("a",2))
        assertTrue(gate.accept("a",3))
    }
    @Test fun replacementAndLossRequireReacquisition() {
        val gate=TrackingAcquisition()
        gate.accept("a",1); gate.accept("a",2)
        assertFalse(gate.accept("b",3))
        assertFalse(gate.accept("a",4))
        assertFalse(gate.accept("a",900_000_005))
        gate.reset()
        assertFalse(gate.accept("a",900_000_006))
    }
}
