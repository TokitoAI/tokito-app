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
import org.json.JSONObject
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
            assertTrue(JSONObject(ioEvidence.designContext).getJSONArray("records").length() >= 2)
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
                ioAnswer.text.contains("MCU USB", ignoreCase = true) ||
                    ioAnswer.text.contains("MCU-Link USB", ignoreCase = true),
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
        } finally {
            runtime.close()
        }
    }
}
