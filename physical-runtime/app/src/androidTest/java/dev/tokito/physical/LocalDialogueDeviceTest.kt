package dev.tokito.physical

import androidx.test.platform.app.InstrumentationRegistry
import ai.tokito.physical.ar.DigitalTwinValidation
import ai.tokito.physical.ar.DigitalTwinValidator
import dev.tokito.physical.dialogue.BoardReferenceContext
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.dialogue.DialogueOutcome
import dev.tokito.physical.dialogue.DialogueRequest
import dev.tokito.physical.dialogue.DialogueLimits
import dev.tokito.physical.dialogue.TokitoDialogueRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDialogueDeviceTest {
    @Test
    fun admittedBoardReferenceProvidesReviewedFactsAndAbstainsForUnknownParts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val board = requireNotNull(BoardReferenceContext.loadIfPresent(context.filesDir)) {
            "physical-device test requires an admitted active board reference"
        }

        assertTrue(board.index.retrieve("Where is the main chip?")!!.contains("MCXN236VDFT"))
        assertTrue(board.index.answer("Where is the main trick?")!!.contains("MCXN236VDFT"))
        assertTrue(board.index.retrieve("What is J9 pin 32?")!!.contains("32=P0_19"))
        assertTrue(board.index.answer("What is J9 pin 32?")!!.contains("P0_19"))
        assertTrue(board.index.answer("Where is the main chip?")!!.contains("MCXN236VDFT"))
        assertEquals("U1", board.index.groundedAnswer("Where is the main chip?")!!.boardTarget!!.label)
        val knowledgeQueries = listOf(
            "Does this have a timer?" to "CTIMER0",
            "Does this have a cunter?" to "CTIMER4",
            "What CPU and memory does the main chip have?" to "352 kilobytes",
            "How many GPIOs does the main chip support?" to "106 GPIOs",
            "Which communication interfaces does it have?" to "Flexcomm",
            "What types of timers are available?" to "real-time-clock",
            "Does the main chip support secure boot?" to "EdgeLock Secure Enclave",
            "How much external flash is installed?" to "8-megabyte",
            "Can the USB port act as a host?" to "J11",
            "How many I/O ports does this have?" to "J10",
            "Does it support CAN FD?" to "CAN1",
            "What accelerometer is fitted?" to "FXLS8974CFR3",
            "Does it have an ambient light sensor?" to "Q2",
            "What microphone is populated?" to "U7",
            "Can I connect an OV7670 camera?" to "J9",
            "What does the MCU-Link debug probe support?" to "SWD",
            "What do the push buttons do?" to "SW1",
            "Which pins drive the RGB LED?" to "P4_18",
            "How can I power the board?" to "J11",
            "What is the operating temperature range?" to "minus 40",
            "Are there any EdgeLock 2GO board errata?" to "Revision C",
            "Where is pin32?" to "P0_19",
        )
        knowledgeQueries.forEach { (query, expected) ->
            assertTrue("Failed query: $query", board.index.answer(query)!!.contains(expected, ignoreCase = true))
        }
        assertEquals(null, board.index.answer("Does this board have Bluetooth?"))
        assertEquals(null, board.index.answer("Where is the Ethernet jack?"))
        assertEquals(null, board.index.retrieve("Where is R3?"))

        val twin = DigitalTwinValidator().load(
            File(context.filesDir, "contexts/active.digital-twin.json"),
            File(context.filesDir, "contexts/active.digital-twin.glb"),
            board.identity.contextRevision,
        )
        assertTrue(twin is DigitalTwinValidation.Accepted)
    }

    @Test
    fun verifiedNpuBundleReturnsBoundedConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = withContext(Dispatchers.IO) {
            DialogueModelProvisioner.fromAdmittedBundle(File(context.filesDir, "models/dialogue"))
                .requireInstalled()
        }
        val runtime = TokitoDialogueRuntime(
            context,
            DialogueLimits(maxPredictTokens = BuildConfig.DIALOGUE_MAX_PREDICT_TOKENS),
        )
        try {
            withTimeout(90_000) { runtime.initialize(model) }
            val ungrounded = runtime.respond(
                DialogueRequest(
                    utterance = "Where is r3?",
                    deadlineMs = 60_000,
                ),
            )
            assertTrue(ungrounded is DialogueOutcome.Clarification)
            assertTrue((ungrounded as DialogueOutcome.Clarification).question.contains("R3"))

            val board = requireNotNull(BoardReferenceContext.loadIfPresent(context.filesDir))
            val chipQuestion = "Where is the main chip?"
            val chipEvidence = requireNotNull(board.index.groundedAnswer(chipQuestion))
            val chipAnswer = withTimeout(90_000) {
                runtime.respond(
                    DialogueRequest(
                        utterance = chipQuestion,
                        designContext = chipEvidence.designContext,
                        deadlineMs = 60_000,
                    ),
                )
            } as DialogueOutcome.Response
            android.util.Log.i("TokitoDialogueDeviceTest", "Qwen3-1.7B chip answer: ${chipAnswer.text}")
            assertTrue(chipAnswer.text.contains("MCXN236VDFT", ignoreCase = true) || chipAnswer.text.contains("U1", ignoreCase = true))
            assertTrue(!chipAnswer.text.contains("printed-guide", ignoreCase = true))
            assertTrue(!chipAnswer.text.contains("guide target", ignoreCase = true))

            val ioEvidence = requireNotNull(board.index.groundedAnswer("How many I/O ports does this have?"))
            assertTrue(ioEvidence.designContext.contains("J10", ignoreCase = true))
            assertTrue(ioEvidence.designContext.contains("J11", ignoreCase = true))
            val ioAnswer = withTimeout(90_000) {
                runtime.respond(
                    DialogueRequest(
                        utterance = "How many I/O ports does this have?",
                        designContext = ioEvidence.designContext,
                        deadlineMs = 60_000,
                    ),
                )
            } as DialogueOutcome.Response
            android.util.Log.i("TokitoDialogueDeviceTest", "Qwen3-1.7B I/O answer: ${ioAnswer.text}")
            assertFalse(ioAnswer.text.equals("How many I/O ports does this have?", ignoreCase = true))
            assertTrue(
                (ioAnswer.text.contains("J10", ignoreCase = true) &&
                    ioAnswer.text.contains("J11", ignoreCase = true)) ||
                    (ioAnswer.text.contains("two", ignoreCase = true) &&
                        ioAnswer.text.contains("USB Type-C", ignoreCase = true)),
            )

            val pinQuestion = "What is J9 pin 32?"
            val pinEvidence = requireNotNull(board.index.groundedAnswer(pinQuestion))
            val pinAnswer = withTimeout(90_000) {
                runtime.respond(
                    DialogueRequest(
                        utterance = pinQuestion,
                        designContext = pinEvidence.designContext,
                        deadlineMs = 60_000,
                    ),
                )
            } as DialogueOutcome.Response
            android.util.Log.i("TokitoDialogueDeviceTest", "Qwen3-1.7B pin answer: ${pinAnswer.text}")
            assertTrue(pinAnswer.text.contains("P0_19", ignoreCase = true))

            val timerQuestion = "Does this have a timer or counter?"
            val timerEvidence = requireNotNull(board.index.groundedAnswer(timerQuestion))
            val timerAnswer = withTimeout(90_000) {
                runtime.respond(
                    DialogueRequest(
                        utterance = timerQuestion,
                        designContext = timerEvidence.designContext,
                        deadlineMs = 60_000,
                    ),
                )
            } as DialogueOutcome.Response
            android.util.Log.i("TokitoDialogueDeviceTest", "Qwen3-1.7B timer answer: ${timerAnswer.text}")
            assertTrue(timerAnswer.text.contains("CTIMER", ignoreCase = true))
            assertFalse(timerAnswer.text.equals(timerQuestion, ignoreCase = true))

            val flashQuestion = "How much external flash is installed?"
            val flashEvidence = requireNotNull(board.index.groundedAnswer(flashQuestion))
            val flashAnswer = withTimeout(90_000) {
                runtime.respond(
                    DialogueRequest(
                        utterance = flashQuestion,
                        designContext = flashEvidence.designContext,
                        deadlineMs = 60_000,
                    ),
                )
            } as DialogueOutcome.Response
            android.util.Log.i("TokitoDialogueDeviceTest", "Qwen3-1.7B flash answer: ${flashAnswer.text}")
            assertTrue(
                flashAnswer.text.contains("W25Q64", ignoreCase = true) ||
                    flashAnswer.text.contains("8 MB", ignoreCase = true) ||
                    flashAnswer.text.contains("8 megabyte", ignoreCase = true),
            )
            assertFalse(flashAnswer.text.contains("CTIMER", ignoreCase = true))
        } finally {
            runtime.close()
        }
    }
}
