package ai.tokito.physical.runtime

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the same lifecycle/capability behavior as [PhysicalRuntimeTest], but
 * on-device via ART/instrumentation instead of the host JVM. This is the
 * device-level conformance check for the reusable library. Standalone UI,
 * camera, model, audio, and end-to-end product evidence are separate gates;
 * this proves only that the library's packaged bytecode executes on Android.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalRuntimeInstrumentedTest {
    private val contractJson = Json { ignoreUnknownKeys = false }

    @Test
    fun runs_on_the_declared_target_device() {
        // Device identity belongs in the sanitized external evidence profile,
        // not Logcat. This test verifies only the packaged API requirement.
        assertTrue(Build.VERSION.SDK_INT >= 26, "declared minSdk 26 must hold on the connected device")
    }

    @Test
    fun full_session_lifecycle_reaches_closed_on_device() {
        val runtime = PhysicalRuntime(
            RuntimeConfiguration(
                contractVersion = "v1",
                deviceProfileId = Build.MODEL.ifBlank { "unknown-device" },
            ),
        )
        val session = runtime.openSession()

        assertEquals(RuntimeSessionState.Calibrating, session.transition(LifecycleEvent.StartCalibration))
        assertEquals(
            RuntimeSessionState.Ready,
            session.transition(LifecycleEvent.CalibrationAccepted(degraded = false)),
        )
        assertEquals(RuntimeSessionState.Suspended, session.transition(LifecycleEvent.CameraLost))
        assertEquals(RuntimeSessionState.Calibrating, session.transition(LifecycleEvent.Resume))
        assertEquals(
            RuntimeSessionState.Ready,
            session.transition(LifecycleEvent.CalibrationAccepted(degraded = false)),
        )
        assertEquals(RuntimeSessionState.Stopping, session.transition(LifecycleEvent.Stop))
        assertEquals(RuntimeSessionState.Closed, session.transition(LifecycleEvent.Close))
        assertFailsWith<IllegalStateException> { session.transition(LifecycleEvent.Resume) }
    }

    @Test
    fun capability_handshake_round_trips_on_device() {
        val runtime = PhysicalRuntime(
            RuntimeConfiguration(
                contractVersion = "v1",
                deviceProfileId = Build.MODEL.ifBlank { "unknown-device" },
                capabilities = listOf(
                    Capability(
                        operation = "discover_board",
                        version = "1.0",
                        modelAvailable = false,
                        resourceLimits = mapOf("max_concurrent_calls" to ResourceLimitValue.IntegerLimit(1)),
                    ),
                ),
            ),
        )

        val encoded = contractJson.encodeToString(runtime.capabilities())
        val decoded = contractJson.decodeFromString<CapabilityHandshake>(encoded)

        assertEquals(runtime.capabilities(), decoded)
    }
}
