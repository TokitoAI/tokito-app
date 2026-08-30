package dev.tokito.physical.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementConversationTest {
    @Test
    fun requiresConfirmationThenAcceptsSpokenAffirmation() {
        val conversation = conversation(clock = 42)
        val required = conversation.submitFinalTranscript("3.3 volts at TP1")
        assertTrue(required is MeasurementTurn.ConfirmationRequired)

        val confirmed = conversation.submitFinalTranscript("yes") as MeasurementTurn.Confirmed
        assertEquals(42, confirmed.measurement.confirmedAtMonotonicMillis)
        assertEquals(MeasurementSource.ENGINEER_VOICE, confirmed.measurement.source)
        assertNull(conversation.pendingDraft())
    }

    @Test
    fun spokenRejectionClearsBoundedDraft() {
        val conversation = conversation()
        conversation.submitFinalTranscript("3.3 volts")

        assertTrue(conversation.submitFinalTranscript("no") is MeasurementTurn.Rejected)
        assertNull(conversation.pendingDraft())
        assertTrue(conversation.confirmPrimary() is MeasurementTurn.Rejected)
    }

    @Test
    fun numericCorrectionReplacesPendingDraftInsteadOfActingAsAffirmation() {
        val conversation = conversation()
        conversation.submitFinalTranscript("5 volts")

        val replacement = conversation.submitFinalTranscript("actually 4.8 volts") as MeasurementTurn.ConfirmationRequired
        assertEquals("actually 4.8 volts", replacement.draft.originalTranscript)
        assertSame(replacement.draft, conversation.pendingDraft())
    }

    @Test
    fun explicitAlternativeSelectionConfirmsRetainedCandidate() {
        val conversation = conversation(clock = 9)
        val required = conversation.submitFinalTranscript("5 volts, sorry, 4.8 volts") as MeasurementTurn.ConfirmationRequired

        val confirmed = conversation.confirmAlternative(0) as MeasurementTurn.Confirmed
        assertEquals(required.draft.alternatives.single(), confirmed.measurement.confirmedCandidate)
        assertEquals(9, confirmed.measurement.confirmedAtMonotonicMillis)
    }

    @Test
    fun incompleteReadingCannotRemainPending() {
        val conversation = conversation()
        conversation.submitFinalTranscript("5 volts")

        val correction = conversation.submitFinalTranscript("five") as MeasurementTurn.CorrectionRequired
        assertEquals(IncompleteReason.MISSING_UNIT, correction.incomplete.reason)
        assertNull(conversation.pendingDraft())
    }

    @Test
    fun whenAmbiguousPolicyCanCommitUnambiguousReadingImmediately() {
        val conversation = conversation(policy = ConfirmationPolicy.WHEN_AMBIGUOUS, clock = 15)
        val confirmed = conversation.submitFinalTranscript("1.8 volts at TP4") as MeasurementTurn.Confirmed

        assertEquals(15, confirmed.measurement.confirmedAtMonotonicMillis)
        assertNull(conversation.pendingDraft())
    }

    @Test
    fun invalidClockDoesNotCreateConfirmedEvidence() {
        val conversation = conversation(clock = -1)
        conversation.submitFinalTranscript("1 volt")

        assertTrue(conversation.confirmPrimary() is MeasurementTurn.Rejected)
        assertNull(conversation.pendingDraft())
    }

    private fun conversation(
        policy: ConfirmationPolicy = ConfirmationPolicy.ALWAYS,
        clock: Long = 1,
    ) = MeasurementConversation(DeterministicMeasurementParser(), policy) { clock }
}
