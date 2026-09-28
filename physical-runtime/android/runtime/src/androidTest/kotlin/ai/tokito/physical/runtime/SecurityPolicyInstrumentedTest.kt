package ai.tokito.physical.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityPolicyInstrumentedTest {
    @Test
    fun sensor_visibility_and_revocation_execute_on_device() {
        val visible = mutableListOf<Set<SensorKind>>()
        val controller = SensorAccessController(visible::add)
        val lease = (
            controller.acquire(
                SensorKind.Camera,
                SensorPreconditions(
                    permissionGranted = true,
                    appInForeground = true,
                    sessionActive = true,
                ),
            ) as SensorAdmission.Granted
        ).lease

        assertEquals(setOf(SensorKind.Camera), visible.last())
        controller.suspendOrStop()
        assertEquals(emptySet(), visible.last())
        lease.close()
        assertEquals(emptySet(), controller.visibleSensors())
    }

    @Test
    fun retention_deletion_executes_independently_on_device() {
        val persisted = RetainedEvidence(
            evidenceId = "evidence",
            capturedAtUnixMs = 1_000,
            policy = EvidenceRetentionPolicy(EvidenceRetentionScope.Persisted, 2_000),
        )
        assertFalse(persisted.shouldDelete(sessionEnded = true, nowUnixMs = 1_999))
        assertTrue(persisted.shouldDelete(sessionEnded = false, nowUnixMs = 2_000))
    }
}
