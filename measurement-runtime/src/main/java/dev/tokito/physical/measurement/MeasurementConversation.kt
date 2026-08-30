package dev.tokito.physical.measurement

/**
 * Bounded deterministic confirmation state machine. It stores at most one
 * draft and never turns free-form affirmation into a replacement numeric value.
 */
class MeasurementConversation(
    private val parser: DeterministicMeasurementParser,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALWAYS,
    private val clockMillis: () -> Long,
) {
    private var pending: MeasurementDraft? = null

    @Synchronized
    fun submitFinalTranscript(transcript: String, transcriptConfidence: Double = 1.0): MeasurementTurn {
        val normalized = transcript.trim()
        val active = pending
        if (active != null) {
            if (AFFIRMATION.matches(normalized)) return confirm(active.primary)
            if (REJECTION.matches(normalized)) {
                pending = null
                return MeasurementTurn.Rejected("engineer rejected the pending measurement")
            }
        }
        return when (val parsed = parser.parse(normalized, transcriptConfidence)) {
            is MeasurementParseResult.Incomplete -> {
                pending = null
                MeasurementTurn.CorrectionRequired(parsed)
            }
            is MeasurementParseResult.Parsed -> {
                val draft = parsed.draft
                val mustConfirm = confirmationPolicy == ConfirmationPolicy.ALWAYS || draft.requiresConfirmation
                if (mustConfirm) {
                    pending = draft
                    MeasurementTurn.ConfirmationRequired(draft, confirmationPrompt(draft))
                } else {
                    confirm(draft.primary, draft)
                }
            }
        }
    }

    @Synchronized
    fun confirmPrimary(): MeasurementTurn {
        val draft = pending ?: return MeasurementTurn.Rejected("no measurement is awaiting confirmation")
        return confirm(draft.primary, draft)
    }

    @Synchronized
    fun confirmAlternative(index: Int): MeasurementTurn {
        val draft = pending ?: return MeasurementTurn.Rejected("no measurement is awaiting confirmation")
        val candidate = draft.alternatives.getOrNull(index)
            ?: return MeasurementTurn.Rejected("measurement alternative does not exist")
        return confirm(candidate, draft)
    }

    @Synchronized
    fun reject(): MeasurementTurn {
        if (pending == null) return MeasurementTurn.Rejected("no measurement is awaiting confirmation")
        pending = null
        return MeasurementTurn.Rejected("engineer rejected the pending measurement")
    }

    @Synchronized
    fun clear() { pending = null }

    @Synchronized
    fun pendingDraft(): MeasurementDraft? = pending

    private fun confirm(candidate: MeasurementCandidate, draft: MeasurementDraft = checkNotNull(pending)): MeasurementTurn {
        val now = clockMillis()
        if (now < 0) {
            pending = null
            return MeasurementTurn.Rejected("monotonic confirmation time is invalid")
        }
        pending = null
        return MeasurementTurn.Confirmed(
            ConfirmedMeasurement(draft, candidate, MeasurementSource.ENGINEER_VOICE, now),
        )
    }

    private fun confirmationPrompt(draft: MeasurementDraft): String = buildString {
        append("Confirm ")
        append(draft.primary.value.spoken())
        append(' ')
        append(draft.primary.unit.symbol)
        draft.primary.relationship?.let { relationship ->
            append(' ')
            append(when (relationship.kind) {
                RelationshipKind.BETWEEN -> "from ${relationship.primary} to ${relationship.reference}"
                RelationshipKind.ACROSS -> "across ${relationship.primary}"
                RelationshipKind.AT -> "at ${relationship.primary}"
            })
        }
        append('?')
    }

    private fun MeasurementValue.spoken(): String = when (this) {
        is MeasurementValue.Scalar -> value.toPlainString()
        is MeasurementValue.Range -> "${low.toPlainString()} to ${high.toPlainString()}"
    }

    companion object {
        private val AFFIRMATION = Regex("(?i)\\s*(?:yes|yeah|yep|correct|confirm|confirmed|that is correct)\\s*[.!]?\\s*")
        private val REJECTION = Regex("(?i)\\s*(?:no|nope|cancel|reject|incorrect|that is wrong)\\s*[.!]?\\s*")
    }
}
