package ai.tokito.physical.runtime

import android.os.Build
import android.util.Log
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
 * closest available conformance check to "runs on the target hardware and
 * produces the expected result": there is no standalone app yet (#37), so
 * there is no launchable UI to observe, but this proves the library's actual
 * bytecode executes correctly on the real iQOO device it targets.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalRuntimeInstrumentedTest {
    private val contractJson = Json { ignoreUnknownKeys = false }

    @Test
    fun runs_on_the_declared_target_device() {
        // The device identity is logged as evidence of where this ran; the
        // library itself makes no hardcoded assumption about which device
        // that is, only that it satisfies the declared minSdk.
        Log.i(
            "PhysicalRuntimeInstrumentedTest",
            "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}",
        )
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
