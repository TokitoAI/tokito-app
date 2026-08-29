package ai.tokito.physical.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val contractJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

class PhysicalRuntimeTest {
    @Test
    fun capabilities_are_empty_until_evidence_enables_an_operation() {
        val runtime = PhysicalRuntime(RuntimeConfiguration("v1", "profile"))
        assertEquals(emptyList(), runtime.capabilities().capabilities)
    }

    @Test
    fun unsupported_operations_are_absent_not_flagged_unavailable() {
        val runtime = PhysicalRuntime(
            RuntimeConfiguration(
                contractVersion = "v1",
                deviceProfileId = "profile",
                capabilities = listOf(Capability("discover_board", "1.0", modelAvailable = true)),
            ),
        )
        val advertised = runtime.capabilities().capabilities.map { it.operation }
        assertEquals(listOf("discover_board"), advertised)
        assertTrue("inspect_component" !in advertised)
    }

    @Test
    fun repeated_capability_queries_are_idempotent() {
        val runtime = PhysicalRuntime(
            RuntimeConfiguration(
                contractVersion = "v1",
                deviceProfileId = "profile",
                capabilities = listOf(Capability("discover_board", "1.0", modelAvailable = false)),
            ),
        )
        assertEquals(runtime.capabilities(), runtime.capabilities())
    }

    @Test
    fun duplicate_operations_in_configuration_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfiguration(
                contractVersion = "v1",
                deviceProfileId = "profile",
                capabilities = listOf(
                    Capability("discover_board", "1.0", modelAvailable = true),
                    Capability("discover_board", "2.0", modelAvailable = true),
                ),
            )
        }
    }

    @Test
    fun camera_loss_requires_recalibration_after_resume() {
        val session = PhysicalRuntime(RuntimeConfiguration("v1", "profile")).openSession()
        session.transition(LifecycleEvent.StartCalibration)
        session.transition(LifecycleEvent.CalibrationAccepted(degraded = false))
        assertEquals(RuntimeSessionState.Suspended, session.transition(LifecycleEvent.CameraLost))
        assertEquals(RuntimeSessionState.Calibrating, session.transition(LifecycleEvent.Resume))
        assertEquals(2, session.generation())
    }

    @Test
    fun closed_sessions_reject_late_lifecycle_callbacks() {
        val session = PhysicalRuntime(RuntimeConfiguration("v1", "profile")).openSession()
        session.transition(LifecycleEvent.StartCalibration)
        session.transition(LifecycleEvent.Stop)
        session.transition(LifecycleEvent.Close)
        assertFailsWith<IllegalStateException> { session.transition(LifecycleEvent.Resume) }
    }

    @Test
    fun duplicate_close_calls_are_rejected_deterministically_every_time() {
        val session = PhysicalRuntime(RuntimeConfiguration("v1", "profile")).openSession()
        session.transition(LifecycleEvent.StartCalibration)
        session.transition(LifecycleEvent.Stop)
        session.transition(LifecycleEvent.Close)
        // A retried/duplicate teardown callback must fail the same way on every
        // attempt, not succeed on a retry or leave the session in a new state.
        repeat(3) {
            assertFailsWith<IllegalStateException> { session.transition(LifecycleEvent.Close) }
        }
        assertEquals(RuntimeSessionState.Closed, session.state())
    }

    @Test
    fun capability_handshake_conforms_to_the_canonical_contract_schema_fixture() {
        val fixture = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/physical-runtime.v1.handshake.json"),
        ) { "canonical contract fixture must be on the test classpath" }.readBytes().decodeToString()

        // Strict decoding against our own wire type: if CapabilityHandshake ever
        // drifts from contracts/physical-runtime.v1.schema.json's
        // capabilityHandshake shape, this fails instead of silently accepting a
        // second, hand-maintained copy of the contract.
        val decoded = contractJson.decodeFromString<CapabilityHandshake>(fixture)
        assertEquals("v1", decoded.contractVersion)
        assertEquals("profile-revision", decoded.deviceProfile)
        assertEquals(emptyList(), decoded.capabilities)
    }

    @Test
    fun capability_round_trips_through_the_contract_wire_shape() {
        val runtime = PhysicalRuntime(
            RuntimeConfiguration(
                contractVersion = "v1",
                deviceProfileId = "profile-revision",
                capabilities = listOf(
                    Capability(
                        operation = "inspect_region",
                        version = "1.0",
                        modelAvailable = true,
                        resourceLimits = mapOf(
                            "max_concurrent_calls" to ResourceLimitValue.IntegerLimit(1),
                            "max_roi_fraction" to ResourceLimitValue.NumberLimit(0.25),
                            "backend" to ResourceLimitValue.TextLimit("declared-runtime-backend"),
                            "requires_permission" to ResourceLimitValue.FlagLimit(true),
                        ),
                    ),
                ),
            ),
        )

        val encoded = contractJson.encodeToString(runtime.capabilities())
        val decoded = contractJson.decodeFromString<CapabilityHandshake>(encoded)

        assertEquals(runtime.capabilities(), decoded)
    }
}
