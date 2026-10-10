package dev.tokito.app

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.MainActivity
import dev.tokito.physical.dialogue.DialogueRuntimeState
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import dev.tokito.physical.session.PhysicalSessionContext
import dev.tokito.physical.session.PhysicalSessionStore
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.SpeechQueuePolicy
import dev.tokito.physical.voice.VoiceRuntimeState
import dev.tokito.physical.voice.VoiceEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Actual signed dataset -> production bench -> Qwen -> local audio, not fixture facts. */
class DatasetBenchDeviceTest {
    @Test fun repeatedReferenceTurnsFinishWithoutTokenExhaustion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = PhysicalSessionStore.open(context).use { it.startSession(PhysicalSessionContext()) }
        val bench = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withTimeout(150_000) {
                while (withContext(Dispatchers.Main) { bench.localReasoningState } !is DialogueRuntimeState.Ready) delay(200)
            }
            val questions = listOf(
                "ESP32" to "Espressif",
                "What is ESP 32?" to "Espressif",
                "What is ESP8266?" to "ESP8266",
                "What is FRDM MCXN236?" to "NXP",
                "What is Ultimainboard?" to "Ultimaker",
            )
            val failures = mutableListOf<String>()
            repeat(2) { round ->
                for ((question, expected) in questions) {
                    ForegroundDeviceQa.requireResumed(bench)
                    val answer = try {
                        withTimeout(65_000) { withContext(Dispatchers.Main) { bench.respondLocally(question) } }
                    } catch (failure: dev.tokito.physical.dialogue.DialogueFailure) {
                        failures.add("round=$round question=$question code=${failure.code}")
                        "Rejected: ${failure.code}"
                    }
                    instrumentation.sendStatus(2, Bundle().apply {
                        putInt("round", round); putString("question", question); putString("source_answer", answer)
                    })
                    if (!answer.contains(expected, ignoreCase = true)) failures.add("Missing source definition for $question: $answer")
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { instrumentation.runOnMainSync { bench.finish() } }
    }
    /** Requires real speech at the phone microphone; no transcript injection. */
    @Test fun liveMicrophoneTranscriptRetrievesSourcesAndSpeaks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = PhysicalSessionStore.open(context).use { it.startSession(PhysicalSessionContext()) }
        val bench = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val voice = TokitoVoiceRuntime(context)
        try {
            withTimeout(150_000) {
                while (withContext(Dispatchers.Main) { bench.localReasoningState } !is DialogueRuntimeState.Ready) delay(200)
            }
            voice.initialize()
            voice.startListening()
            instrumentation.sendStatus(2, Bundle().apply { putString("microphone_state", "listening_for_live_esp32_question") })
            val transcript = withTimeout(90_000) {
                (voice.events.first { it is VoiceEvent.Transcript && it.segment.isFinal && it.segment.text.isNotBlank() } as VoiceEvent.Transcript).segment.text
            }
            voice.stopListening()
            val answer = withTimeout(65_000) { withContext(Dispatchers.Main) { bench.respondLocally(transcript) } }
            instrumentation.sendStatus(2, Bundle().apply { putString("live_transcript", transcript); putString("source_answer", answer) })
            assertTrue("Live microphone question was not source-answered: $answer", answer.contains("Espressif", ignoreCase = true))
            val played = AtomicBoolean(false)
            voice.speak(answer, SpeechQueuePolicy.REPLACE) { played.set(true) }
            withTimeout(65_000) { while (!played.get()) delay(20) }
            withTimeout(190_000) { while (voice.state.value !is VoiceRuntimeState.Ready) delay(50) }
        } finally {
            voice.close()
            instrumentation.runOnMainSync { bench.finish() }
        }
    }
    @Test fun realBenchAnswersAllDatasetReferencesAndSpeaks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val archive = File(context.getExternalFilesDir(null), "tokito-knowledge.zip")
        archive.inputStream().use { ModelBundleRepository(context).install(ModelCapability.EMBEDDING, it) }
        val session = PhysicalSessionStore.open(context).use { it.startSession(PhysicalSessionContext()) }
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, session.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val bench = instrumentation.startActivitySync(intent) as MainActivity
        val voice = TokitoVoiceRuntime(context)
        try {
            withTimeout(150_000) {
                while (withContext(Dispatchers.Main) { bench.localReasoningState } !is DialogueRuntimeState.Ready) {
                    assertFalse("Bench initialization failed", withContext(Dispatchers.Main) { bench.localReasoningState } is DialogueRuntimeState.Failed)
                    delay(200)
                }
            }
            voice.initialize()
            val cases = listOf(
                "What is ESP32?" to listOf("esp32", "espressif"),
                "What is ESP8266?" to listOf("esp8266", "wi-fi"),
                "What is FRDM MCXN236?" to listOf("nxp", "development"),
                "What is Ultimainboard?" to listOf("ultimaker", "electronics"),
            )
            for ((question, terms) in cases) {
                ForegroundDeviceQa.requireResumed(bench)
                val answer = withTimeout(65_000) { withContext(Dispatchers.Main) { bench.respondLocally(question) } }
                assertTrue("Unanswered real reference question: $answer", terms.all { answer.lowercase().contains(it) })
                assertFalse(answer.contains("think>"))
                val played = AtomicBoolean(false)
                voice.speak(answer, SpeechQueuePolicy.REPLACE) { played.set(true) }
                withTimeout(65_000) { while (!played.get()) delay(20) }
                withTimeout(190_000) { while (voice.state.value !is VoiceRuntimeState.Ready) delay(50) }
                instrumentation.sendStatus(2, Bundle().apply { putString("reference_question", question); putString("source_answer", answer) })
            }
            val followUp = withContext(Dispatchers.Main) { bench.respondLocally("Which revision does that reference cover?") }
            assertTrue("Reference scope lost on follow-up: $followUp", followUp.contains("2.1.4"))
            val physical = withContext(Dispatchers.Main) { bench.respondLocally("Is this my board?") }
            assertTrue(physical.contains("verified board identification"))
            val comparison = withContext(Dispatchers.Main) { bench.respondLocally("ESP32 versus ESP8266") }
            assertTrue("Comparison must cite both retrieved references: $comparison", comparison.contains("ESP32") && comparison.contains("ESP8266"))
            val variant = withContext(Dispatchers.Main) { bench.respondLocally("What is ESP32-S3?") }
            assertTrue("An absent variant must abstain: $variant", variant.contains("does not establish"))
        } finally {
            voice.close()
            instrumentation.runOnMainSync { bench.finish() }
        }
    }
}
