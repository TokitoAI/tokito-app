package dev.tokito.physical.dialogue

/** Answers identity-sensitive bench questions without promoting model prose to evidence. */
class LocalBenchDialoguePolicy {
    private val protocol = DialogueProtocol(DialogueLimits())

    data class Answer(val text: String, val boardTarget: DesignBoardTarget? = null)

    /** Null means an ordinary, ungrounded general-electronics conversation may proceed. */
    fun answer(utterance: String, index: LocalDesignContextIndex?): Answer? {
        protocol.validateRequest(DialogueRequest(utterance = utterance))
        val query = utterance.trim().replace(Regex("\\s+"), " ")
        // This policy runs inside a physical-board session. Absence of a lexical
        // match is not evidence that an ambiguous question is general knowledge.
        // Only an explicitly educational request may bypass board evidence.
        if (isExplicitGeneralQuestion(query)) return null
        val grounded = index?.groundedAnswer(query)
        val request = DialogueRequest(utterance = utterance, designContext = grounded?.designContext)
        protocol.ungroundedIdentityClarification(request)?.let { return Answer(it.question) }
        return grounded?.let { Answer(it.fallbackText, it.boardTarget) }
            ?: Answer("I need verified board context for that question. Name the component or ask explicitly about electronics in general.")
    }

    private fun isExplicitGeneralQuestion(query: String): Boolean =
        GENERAL_INTENT.containsMatchIn(query) &&
            !BOARD_CONTEXT.containsMatchIn(query) &&
            // The protocol remains authoritative for explicit reference identity.
            protocol.ungroundedIdentityClarification(DialogueRequest(utterance = query)) == null

    private companion object {
        // Language intent, not a whitelist of boards, components or devices.
        val GENERAL_INTENT = Regex(
            "(?i)^(?:in general\\b|generally\\b|general electronics\\s*:|" +
                "(?:please\\s+)?(?:explain|teach me)(?:\\s+(?:what|how))?\\s+(?:a|an)\\b|" +
                "(?:what is|what are|how does|how do)\\s+(?:a|an)\\b|" +
                "(?:please\\s+)?explain\\s+[a-z][a-z-]*[?.!]?$)",
        )
        val BOARD_CONTEXT = Regex("(?i)\\b(?:this|these|that|those|it|its|they|their|my|our|here|installed|fitted|populated)\\b")
    }
}
