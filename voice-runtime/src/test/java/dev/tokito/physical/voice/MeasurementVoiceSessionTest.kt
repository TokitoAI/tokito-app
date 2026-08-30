package dev.tokito.physical.voice

import dev.tokito.physical.measurement.MeasurementTurn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementVoiceSessionTest {
    @Test
    fun ignoresPartialHypothesesWithoutMutatingConfirmationState() = runTest {
        val voice = FakeVoiceRuntime()
        val session = MeasurementVoiceSession(voice, clockMillis = { 7 })
        session.begin()

        assertNull(session.accept(TranscriptSegment("3.3 volts at TP1", false, 1)))
        assertNull(session.pendingDraft)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun finalReadingIsPromptedByLocalTtsAndSpokenYesConfirmsIt() = runTest {
        val voice = FakeVoiceRuntime()
        val session = MeasurementVoiceSession(voice, clockMillis = { 77 })
        session.begin()

        val required = session.accept(
            TranscriptSegment("3.3 volts from TP1 to GND", true, 1),
        ) as MeasurementTurn.ConfirmationRequired
        assertEquals(required.prompt, voice.spoken.single())

        val confirmed = session.accept(TranscriptSegment("yes", true, 2)) as MeasurementTurn.Confirmed
        assertEquals(77, confirmed.measurement.confirmedAtMonotonicMillis)
        assertNull(session.pendingDraft)
        assertTrue(!session.isActive)
    }

    @Test
    fun incompleteReadingPromptsCorrectionButCannotCreateEvidence() = runTest {
        val voice = FakeVoiceRuntime()
        val session = MeasurementVoiceSession(voice)
        session.begin()

        val correction = session.accept(TranscriptSegment("five", true, 1)) as MeasurementTurn.CorrectionRequired
        assertEquals(correction.incomplete.recoverablePrompt, voice.spoken.single())
        assertNull(session.pendingDraft)
    }

    @Test
    fun explicitTouchConfirmationUsesSameBoundedStateMachine() = runTest {
        val voice = FakeVoiceRuntime()
        val session = MeasurementVoiceSession(voice, clockMillis = { 91 })
        session.begin()
        session.accept(TranscriptSegment("1.8 volts at TP2", true, 1))

        val confirmed = session.confirmPrimary() as MeasurementTurn.Confirmed
        assertEquals(91, confirmed.measurement.confirmedAtMonotonicMillis)
        assertNull(session.pendingDraft)
    }

    @Test
    fun ignoresFinalSpeechUntilARequestMeasurementInteractionIsArmed() = runTest {
        val voice = FakeVoiceRuntime()
        val session = MeasurementVoiceSession(voice)

        assertNull(session.accept(TranscriptSegment("where is D1", true, 1)))
        assertTrue(voice.spoken.isEmpty())
    }

    private class FakeVoiceRuntime : VoiceRuntime {
        override val state: StateFlow<VoiceRuntimeState> = MutableStateFlow(VoiceRuntimeState.Uninitialized)
        override val events: SharedFlow<VoiceEvent> = MutableSharedFlow()
        val spoken = mutableListOf<String>()

        override suspend fun initialize() = Unit
        override suspend fun startListening() = Unit
        override suspend fun stopListening() = Unit
        override suspend fun speak(text: String, policy: SpeechQueuePolicy): String {
            spoken += text
            return "test-${spoken.size}"
        }
        override suspend fun interruptSpeech() = Unit
        override fun close() = Unit
    }
}
