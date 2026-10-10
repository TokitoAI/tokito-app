package dev.tokito.physical

import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceRuntimeState
import dev.tokito.physical.voice.VoiceModelBundle
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LocalVoiceDeviceTest {
    @Test
    fun verifiedSherpaBundleSynthesizesOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val admitted = requireNotNull(ModelBundleRepository(context).activeDirectory(ModelCapability.VOICE)) {
            "Signed speech bundle is unavailable under this APK's license policy"
        }
        check(admitted.canonicalFile == java.io.File(context.filesDir, "models/voice/active").canonicalFile) {
            "Voice admission does not match its native runtime slot"
        }
        VoiceModelBundle.verify(admitted)
        val runtime = TokitoVoiceRuntime(context)
        val stateAtPlayback = AtomicReference<VoiceRuntimeState>()
        try {
            withTimeout(90_000) { runtime.initialize() }
            withTimeout(90_000) {
                runtime.speak("Tokito local speech is ready.") {
                    stateAtPlayback.set(runtime.state.value)
                }
            }
            assertTrue(stateAtPlayback.get() is VoiceRuntimeState.Speaking)
            assertTrue(runtime.state.value is VoiceRuntimeState.Ready)
        } finally {
            runtime.close()
        }
    }
}
