package dev.tokito.physical.model

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceResourceGovernorTest {
    @Test
    fun rejectsBeforeAllocationWhenMemoryIsLow() {
        val governor = DeviceResourceGovernor(
            snapshot = { DeviceResourceSnapshot(900, lowMemory = false, PowerManager.THERMAL_STATUS_NONE) },
            memoryReserveBytes = 500,
        )
        val admission = governor.admit(401)
        assertFalse(admission.allowed)
        assertEquals("memory_limited", admission.reason)
    }

    @Test
    fun severeThermalStateFailsClosedWithoutChangingBackend() {
        val governor = DeviceResourceGovernor(
            snapshot = { DeviceResourceSnapshot(10_000, lowMemory = false, PowerManager.THERMAL_STATUS_SEVERE) },
            memoryReserveBytes = 100,
        )
        val admission = governor.admit(100)
        assertFalse(admission.allowed)
        assertEquals(0, admission.maxConcurrency)
        assertEquals("thermal_limited", admission.reason)
    }

    @Test
    fun moderateThermalStateDegradesConcurrencyExplicitly() {
        val governor = DeviceResourceGovernor(
            snapshot = { DeviceResourceSnapshot(10_000, lowMemory = false, PowerManager.THERMAL_STATUS_MODERATE) },
            memoryReserveBytes = 100,
        )
        val admission = governor.admit(100)
        assertTrue(admission.allowed)
        assertEquals(1, admission.maxConcurrency)
        assertEquals("thermal_constrained", admission.reason)
    }
}
