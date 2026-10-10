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
import java.text.BreakIterator
import java.util.Locale

/** Runs inside the installed unified app and requires UI-imported signed models. */
class LocalModelsDeviceTest {
    private data class QualityCase(val id: String, val prompt: String, val rubric: String)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var foreground: android.app.Activity

    @Before fun keepTargetForeground() {
        foreground = ForegroundDeviceQa.launch()
    }

    @Test fun vendorJniBeanAbiSurvivesReleaseOptimization() {
        requireGenieXJniAbi()
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
        val runtime = ForegroundDeviceQa.runtime(foreground, DialogueLimits(maxPredictTokens = 128))
        try {
            val loadStart = android.os.SystemClock.elapsedRealtime()
            withTimeout(120_000) { runtime.initialize(model) }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putLong("tokito_qa_npu_load_ms", android.os.SystemClock.elapsedRealtime() - loadStart)
            })
            assertTrue(runtime.state.value is DialogueRuntimeState.Ready)
            for ((index, prompt) in listOf(
                "In one sentence, explain what a resistor does.",
                "In one sentence, explain how a capacitor stores energy.",
                "In one sentence, explain the difference between voltage and current.",
            ).withIndex()) {
                ForegroundDeviceQa.requireResumed(foreground)
                android.util.Log.i("TokitoDeviceQA", "NPU repeat phase=case_start index=$index")
                val generationStart = android.os.SystemClock.elapsedRealtime()
                val response = withTimeout(65_000) {
                    runtime.respond(DialogueRequest(utterance = prompt, deadlineMs = 60_000))
                }
                ForegroundDeviceQa.requireResumed(foreground)
                android.util.Log.i("TokitoDeviceQA", "NPU repeat phase=receipt_returned index=$index")
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

    /** Raw answers and predeclared rubrics need human semantic review, not keyword scoring. */
    @Test fun localElectronicsQualityEvidence() = runBlocking {
        val repository = ModelBundleRepository(context)
        val model = DialogueModelProvisioner.fromAdmittedBundle(
            repository.activeDirectory(ModelCapability.DIALOGUE)!!,
        ).requireInstalled()
        val runtime = ForegroundDeviceQa.runtime(foreground, DialogueLimits(maxPredictTokens = 128))
        val cases = listOf(
            QualityCase("inductance", "In general, how does an inductor store energy?", "Magnetic field; not electric charge or energy creation."),
            QualityCase("diode", "In general, what does a diode do?", "Direction-dependent conduction; not an unconditional perfect one-way switch."),
            QualityCase("pullup", "In general, why use a pull-up resistor on a digital input?", "Defined default high when undriven; not current amplification."),
            QualityCase("decoupling", "In general, why put a decoupling capacitor near an IC power pin?", "Local transient charge and reduced supply noise; not DC blocking in series."),
            QualityCase("short", "In general, how is a short circuit different from an open circuit?", "Short is low resistance; open interrupts conduction; not reversed."),
            QualityCase("battery", "In general, how does a battery store energy differently from a capacitor?", "Battery chemical energy versus capacitor electric field; not magnetic energy."),
            QualityCase("led", "In general, why does an LED need current limiting?", "Avoids excessive current/damage; LED is not inherently a safe current regulator."),
            QualityCase("series", "In general, what happens to total resistance when two resistors are connected in series?", "Resistances add; not below either individual positive resistance."),
            QualityCase("ground", "In general, what does circuit ground mean?", "Reference potential/return; not necessarily earth."),
            QualityCase("frequency", "In general, how does capacitor impedance change as AC frequency increases?", "Ideal capacitive impedance decreases; real parasitics may differ."),
            QualityCase("meter", "In general, why should power be disconnected before measuring resistance with a multimeter?", "Meter supplies test signal; live voltage invalidates reading and can damage equipment."),
            QualityCase("mosfet", "In general, what controls conduction in a MOSFET?", "Gate-to-source voltage; not continuous large gate current."),
        )
        val failures = mutableListOf<String>()
        try {
            withTimeout(120_000) { runtime.initialize(model) }
            repeat(2) { round ->
                cases.forEach { (id, prompt, rubric) ->
                    ForegroundDeviceQa.requireResumed(foreground)
                    val start = android.os.SystemClock.elapsedRealtime()
                    val outcome = withTimeout(65_000) { runtime.respond(DialogueRequest(prompt, deadlineMs = 60_000)) }
                    ForegroundDeviceQa.requireResumed(foreground)
                    val answer = (outcome as? DialogueOutcome.Response)?.text.orEmpty()
                    val words = java.util.StringTokenizer(answer).countTokens()
                    val sentences = BreakIterator.getSentenceInstance(Locale.US).let { iterator ->
                        iterator.setText(answer)
                        var count = 0
                        var begin = iterator.first()
                        var end = iterator.next()
                        while (end != BreakIterator.DONE) {
                            if (answer.substring(begin, end).isNotBlank()) count += 1
                            begin = end
                            end = iterator.next()
                        }
                        count
                    }
                    val adheres = answer.isNotBlank() && words <= 35 && sentences == 1 &&
                        !answer.contains('\n') && !answer.contains("<think>") && !answer.contains("```")
                    if (!adheres) failures += "$id/$round: words=$words sentences=$sentences"
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                        putString("tokito_qa_corpus", "electronics-spoken-v1")
                        putString("tokito_qa_case", "$id/$round")
                        putString("tokito_qa_prompt", prompt)
                        putString("tokito_qa_review_rubric", rubric)
                        putString("tokito_qa_response", answer)
                        putBoolean("tokito_qa_spoken_format_pass", adheres)
                        putLong("tokito_qa_generation_ms", android.os.SystemClock.elapsedRealtime() - start)
                    })
                }
            }
            assertTrue("Spoken format failures: $failures", failures.isEmpty())
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
