package dev.tokito.physical

import dev.tokito.physical.voice.TranscriptSegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallControllerTest {
    @Test
    fun warmUpLoadsModelsBeforeFirstPress() = runTest {
        val harness = Harness(this)

        harness.controller.warmUp()
        harness.controller.start()

        assertEquals(1, harness.preparations)
        assertEquals(1, harness.starts)
        assertEquals(listOf(CallState.Preparing, CallState.Idle, CallState.Listening), harness.states)
    }

    @Test
    fun pressDuringWarmUpSharesOneModelLoad() = runTest {
        val release = CompletableDeferred<Unit>()
        val harness = Harness(this, prepareGate = release)
        launch { harness.controller.warmUp() }
        testScheduler.runCurrent()
        launch { harness.controller.start() }

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.preparations)
        assertEquals(1, harness.starts)
        assertEquals(CallState.Listening, harness.states.last())
    }

    @Test
    fun finalTranscriptRunsOnePrivateDialogueAndSpeechTurn() = runTest {
        val harness = Harness(this)
        harness.controller.start()
        harness.controller.accept(final("inspect the board", 10))
        advanceUntilIdle()

        assertEquals(listOf("inspect the board"), harness.requests)
        assertEquals(listOf("local: inspect the board"), harness.spoken)
        assertEquals(CallState.Listening, harness.states.last())
    }

    @Test
    fun partialAndReplayedFinalSegmentsAreIgnored() = runTest {
        val harness = Harness(this)
        harness.controller.start()
        harness.controller.accept(TranscriptSegment("partial", false, 9))
        harness.controller.accept(final("one turn", 10))
        advanceUntilIdle()
        harness.controller.accept(final("one turn", 10))
        advanceUntilIdle()

        assertEquals(listOf("one turn"), harness.requests)
    }

    @Test
    fun stopEndsCaptureAndRejectsLaterTranscripts() = runTest {
        val harness = Harness(this)
        harness.controller.start()
        harness.controller.stop()
        harness.controller.accept(final("ignored", 20))
        advanceUntilIdle()

        assertEquals(1, harness.stops)
        assertTrue(harness.requests.isEmpty())
        assertEquals(CallState.Idle, harness.states.last())
    }

    @Test
    fun missingModelFailsClosedWithoutStartingCapture() = runTest {
        val harness = Harness(this, prepareFailure = IllegalStateException("Verified models are absent"))
        harness.controller.start()

        assertEquals(0, harness.starts)
        assertEquals(CallState.Unavailable("Verified models are absent"), harness.states.last())
    }

    @Test
    fun secondFinalIsIgnoredWhileDialogueIsInFlight() = runTest {
        val release = CompletableDeferred<Unit>()
        val harness = Harness(this, responseGate = release)
        harness.controller.start()
        harness.controller.accept(final("first", 10))
        testScheduler.runCurrent()
        harness.controller.accept(final("second", 11))
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first"), harness.requests)
        assertEquals(listOf("local: first"), harness.spoken)
    }

    @Test
    fun stopDuringDialogueCancelsTheTurnWithoutSpeech() = runTest {
        val release = CompletableDeferred<Unit>()
        val harness = Harness(this, responseGate = release)
        harness.controller.start()
        harness.controller.accept(final("cancel me", 10))
        testScheduler.runCurrent()
        harness.controller.stop()
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(harness.spoken.isEmpty())
        assertEquals(CallState.Idle, harness.states.last())
    }

    private fun final(text: String, time: Long) = TranscriptSegment(text, true, time)

    private class Harness(
        scope: TestScope,
        private val prepareFailure: Throwable? = null,
        private val prepareGate: CompletableDeferred<Unit>? = null,
        private val responseGate: CompletableDeferred<Unit>? = null,
    ) {
        val states = mutableListOf<CallState>()
        val requests = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var starts = 0
        var stops = 0
        var preparations = 0

        val controller = VoiceCallController(
            scope = TestScope(StandardTestDispatcher(scope.testScheduler)),
            prepare = {
                preparations++
                prepareGate?.await()
                prepareFailure?.let { throw it }
            },
            startListening = { starts++ },
            stopListening = { stops++ },
            interruptSpeech = {},
            respond = {
                requests += it
                responseGate?.await()
                "local: $it"
            },
            speak = { spoken += it },
            onState = { states += it },
        )
    }
}
