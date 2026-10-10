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
    @Test fun foregroundRetirementInvalidatesEvenACompletedWarmup() = runTest {
        val harness = Harness(this)
        harness.controller.warmUp()
        val retired = harness.controller.revokeActiveTurn()
        harness.controller.invalidatePreparation()
        harness.controller.cleanupRevokedTurn(retired)
        harness.controller.start()
        assertEquals(2, harness.preparations)
        assertEquals(1, harness.starts)
    }

    @Test fun latePreparationCannotBecomePreparedAfterForegroundRetirement() = runTest {
        val release = CompletableDeferred<Unit>()
        val harness = Harness(this, prepareGate = release)
        val warming = launch { harness.controller.warmUp() }
        testScheduler.runCurrent()
        harness.controller.revokeActiveTurn()
        harness.controller.invalidatePreparation()
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(warming.isCancelled)
        harness.controller.start()
        assertEquals(2, harness.preparations)
        assertEquals(1, harness.starts)
    }
    @Test fun cancelledPreparationCannotLeaveCallActiveOrPrepared() = runTest {
        val harness = Harness(this, prepareFailure = kotlinx.coroutines.CancellationException("stopped"))
        repeat(2) {
            try { harness.controller.start(); org.junit.Assert.fail("Cancellation was swallowed") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(CallState.Idle, harness.controller.currentState())
        }
        assertEquals(2, harness.preparations)
        assertEquals(0, harness.starts)
    }

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
        assertTrue(harness.states.indexOf(CallState.Thinking) < harness.states.indexOf(CallState.Speaking))
        assertEquals(CallState.Listening, harness.states.last())
    }

    @Test
    fun remainsThinkingUntilPlaybackStarts() = runTest {
        val playback = CompletableDeferred<Unit>()
        val harness = Harness(this, playbackGate = playback)
        harness.controller.start()
        harness.controller.accept(final("locate the main chip", 10))
        testScheduler.runCurrent()

        assertEquals(CallState.Thinking, harness.states.last())
        assertTrue(CallState.Speaking !in harness.states)

        playback.complete(Unit)
        advanceUntilIdle()
        assertTrue(CallState.Speaking in harness.states)
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

    @Test fun backgroundRevokesTurnBeforeDeferredAudioCleanup() = runTest {
        val release = CompletableDeferred<Unit>()
        val harness = Harness(this, responseGate = release)
        harness.controller.start()
        harness.controller.accept(final("pending turn", 10))
        testScheduler.runCurrent()
        harness.controller.revokeActiveTurn()
        // No scheduler drain or suspend cleanup is needed to revoke ownership.
        assertEquals(CallState.Idle, harness.controller.currentState())
        harness.controller.accept(final("late transcript", 11))
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(harness.spoken.isEmpty())
        assertEquals(listOf("pending turn"), harness.requests)
        harness.controller.stop()
        assertTrue(harness.stops > 0)
    }

    @Test fun delayedBackgroundCleanupCannotStopNewForegroundCall() = runTest {
        val harness = Harness(this)
        harness.controller.start()
        val retired = harness.controller.revokeActiveTurn()
        harness.controller.start()
        harness.controller.cleanupRevokedTurn(retired)
        assertEquals(0, harness.stops)
        assertEquals(2, harness.starts)
        assertEquals(CallState.Listening, harness.controller.currentState())
    }

    @Test fun stoppedPreparationCannotPublishListeningAfterResume() = runTest {
        val prepare = CompletableDeferred<Unit>()
        val harness = Harness(this, prepareGate = prepare)
        launch { harness.controller.start() }
        testScheduler.runCurrent()
        harness.controller.revokeActiveTurn()
        prepare.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, harness.starts)
        assertEquals(CallState.Idle, harness.controller.currentState())
    }

    private class Harness(
        scope: TestScope,
        private val prepareFailure: Throwable? = null,
        private val prepareGate: CompletableDeferred<Unit>? = null,
        private val responseGate: CompletableDeferred<Unit>? = null,
        private val playbackGate: CompletableDeferred<Unit>? = null,
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
            speak = { text, onPlaybackStarted ->
                spoken += text
                playbackGate?.await()
                onPlaybackStarted()
            },
            onState = { states += it },
        )
    }
}
