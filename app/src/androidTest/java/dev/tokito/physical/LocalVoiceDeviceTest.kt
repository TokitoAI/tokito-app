package dev.tokito.physical

import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceRuntimeState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVoiceDeviceTest {
    @Test
    fun verifiedSherpaBundleSynthesizesOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = TokitoVoiceRuntime(context)
        try {
            withTimeout(90_000) { runtime.initialize() }
            withTimeout(90_000) {
                runtime.speak("Tokito local speech is ready.")
            }
            assertTrue(runtime.state.value is VoiceRuntimeState.Ready)
        } finally {
            runtime.close()
        }
    }
}
