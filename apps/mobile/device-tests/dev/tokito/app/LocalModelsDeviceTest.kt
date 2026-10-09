package dev.tokito.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.LocalPoseVisionRuntime
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import dev.tokito.physical.dialogue.*
import dev.tokito.physical.voice.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.util.concurrent.atomic.AtomicBoolean

/** Runs inside the installed unified app and requires UI-imported signed models. */
class LocalModelsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun keepTargetForeground() {
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        launcher.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launcher)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun installedModelsResolveInUnifiedRelease() {
        val repository = ModelBundleRepository(context)
        ModelCapability.entries.forEach { capability ->
            assertNotNull("Missing ${capability.wireValue}", repository.activeBundle(capability))
        }
        LocalPoseVisionRuntime.load(repository.activeDirectory(ModelCapability.VISION)!!).use { }
        VoiceModelBundle.verify(repository.activeDirectory(ModelCapability.VOICE)!!)
    }

    @Test fun npuGeneratesMultipleFreshResponses() = runBlocking {
        val repository = ModelBundleRepository(context)
        val model = DialogueModelProvisioner.fromAdmittedBundle(
            repository.activeDirectory(ModelCapability.DIALOGUE)!!,
        ).requireInstalled()
        val runtime = TokitoDialogueRuntime(context, DialogueLimits(maxPredictTokens = 128))
        try {
            val loadStart = android.os.SystemClock.elapsedRealtime()
            withTimeout(120_000) { runtime.initialize(model) }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putLong("tokito_qa_npu_load_ms", android.os.SystemClock.elapsedRealtime() - loadStart)
            })
            assertTrue(runtime.state.value is DialogueRuntimeState.Ready)
            for (prompt in listOf(
                "In one sentence, explain what a resistor does.",
                "In one sentence, explain how a capacitor stores energy.",
                "In one sentence, explain the difference between voltage and current.",
            )) {
                val generationStart = android.os.SystemClock.elapsedRealtime()
                val response = withTimeout(65_000) {
                    runtime.respond(DialogueRequest(utterance = prompt, deadlineMs = 60_000))
                }
                assertTrue("Expected local generated response, got $response", response is DialogueOutcome.Response)
                val answer = (response as DialogueOutcome.Response).text
                assertTrue("Empty local output", answer.length > 12)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                    putString("tokito_qa_prompt", prompt)
                    putString("tokito_qa_response", answer)
                    putLong("tokito_qa_generation_ms", android.os.SystemClock.elapsedRealtime() - generationStart)
                })
                android.util.Log.i("TokitoDeviceQA", "Local response completed: chars=${answer.length}")
            }
        } finally { runtime.close() }
    }

    @Test fun localSpeechLoadsAndPlaysAudio() = runBlocking {
        val voice = TokitoVoiceRuntime(context)
        val played = AtomicBoolean(false)
        try {
            withTimeout(90_000) { voice.initialize() }
            assertTrue(voice.state.value is VoiceRuntimeState.Ready)
            voice.startListening()
            assertEquals(VoiceRuntimeState.Listening, voice.state.value)
            repeat(3) {
                played.set(false)
                withTimeout(30_000) {
                    voice.speak("Tokito local speech is ready.", SpeechQueuePolicy.REPLACE) { played.set(true) }
                }
                assertTrue("Audio playback callback was not reached", played.get())
                assertEquals("Capture must resume after every spoken turn", VoiceRuntimeState.Listening, voice.state.value)
            }
            voice.stopListening()
            assertTrue(voice.state.value is VoiceRuntimeState.Ready)
        } finally { voice.close() }
    }
}
