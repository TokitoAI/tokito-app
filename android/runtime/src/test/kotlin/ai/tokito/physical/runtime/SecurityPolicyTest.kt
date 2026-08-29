package ai.tokito.physical.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityPolicyTest {
    @Test
    fun sensor_admission_requires_permission_foreground_and_active_session() {
        val controller = SensorAccessController {}
        assertEquals(
            SensorDenial.PermissionDenied,
            (controller.acquire(
                SensorKind.Camera,
                SensorPreconditions(false, true, true),
            ) as SensorAdmission.Denied).reason,
        )
        assertEquals(
            SensorDenial.BackgroundUnavailable,
            (controller.acquire(
                SensorKind.Camera,
                SensorPreconditions(true, false, true),
            ) as SensorAdmission.Denied).reason,
        )
        assertEquals(
            SensorDenial.SessionInactive,
            (controller.acquire(
                SensorKind.Camera,
                SensorPreconditions(true, true, false),
            ) as SensorAdmission.Denied).reason,
        )
        assertEquals(emptySet(), controller.visibleSensors())
    }

    @Test
    fun visibility_revocation_and_stale_leases_fail_closed() {
        val visibility = mutableListOf<Set<SensorKind>>()
        val controller = SensorAccessController(visibility::add)
        val granted = SensorPreconditions(true, true, true)
        val first = (controller.acquire(SensorKind.Camera, granted) as SensorAdmission.Granted).lease
        val second = (controller.acquire(SensorKind.Camera, granted) as SensorAdmission.Granted).lease

        first.close()
        assertEquals(setOf(SensorKind.Camera), controller.visibleSensors())
        assertEquals(
            SensorDenial.PermissionDenied,
            controller.revalidate(SensorKind.Camera, SensorPreconditions(false, true, true)),
        )
        assertEquals(emptySet(), controller.visibleSensors())
        second.close()

        val microphone =
            (controller.acquire(SensorKind.Microphone, granted) as SensorAdmission.Granted).lease
        controller.suspendOrStop()
        assertEquals(emptySet(), controller.visibleSensors())
        microphone.close()
        assertEquals(emptySet(), controller.visibleSensors())
        assertEquals(emptySet(), visibility.last())
    }

    @Test
    fun diagnostics_accept_only_typed_codes_and_nonnegative_counters() {
        val diagnostic = SafeDiagnostic(
            code = DiagnosticCode.ResourceExhausted,
            counters = mapOf("dropped_frames" to 4),
        )
        assertEquals(4, diagnostic.counters.getValue("dropped_frames"))
        assertFailsWith<IllegalArgumentException> {
            SafeDiagnostic(DiagnosticCode.InternalFailure, mapOf("" to 1))
        }
        assertFailsWith<IllegalArgumentException> {
            SafeDiagnostic(DiagnosticCode.InternalFailure, mapOf("failures" to -1))
        }
    }

    @Test
    fun retention_is_independent_from_inference_success() {
        val ephemeral = EvidenceRetentionPolicy(EvidenceRetentionScope.Ephemeral, null)
        val session = EvidenceRetentionPolicy(EvidenceRetentionScope.Session, null)
        val persisted = EvidenceRetentionPolicy(EvidenceRetentionScope.Persisted, 2_000)
        assertTrue(ephemeral.deleteAtSessionEnd())
        assertTrue(session.deleteAtSessionEnd())
        assertFalse(persisted.deleteAtSessionEnd())
        assertFalse(persisted.expired(1_999))
        assertTrue(persisted.expired(2_000))
        assertFailsWith<IllegalArgumentException> {
            EvidenceRetentionPolicy(EvidenceRetentionScope.Persisted, null)
        }
        assertFailsWith<IllegalArgumentException> {
            EvidenceRetentionPolicy(EvidenceRetentionScope.Session, 2_000)
        }

        val retained = RetainedEvidence("evidence", 1_000, persisted)
        assertFalse(retained.shouldDelete(sessionEnded = true, nowUnixMs = 1_999))
        assertTrue(retained.shouldDelete(sessionEnded = false, nowUnixMs = 2_000))
        val sessionEvidence = RetainedEvidence("session-evidence", 1_000, session)
        assertTrue(sessionEvidence.shouldDelete(sessionEnded = true, nowUnixMs = 1_001))
        assertFailsWith<IllegalArgumentException> {
            RetainedEvidence("expired-at-capture", 2_000, persisted)
        }
    }
}
