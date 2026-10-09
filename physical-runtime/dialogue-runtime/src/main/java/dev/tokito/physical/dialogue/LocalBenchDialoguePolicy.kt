package dev.tokito.physical.dialogue

/** Answers identity-sensitive bench questions without promoting model prose to evidence. */
class LocalBenchDialoguePolicy {
    private val protocol = DialogueProtocol(DialogueLimits())

    data class Answer(val text: String, val boardTarget: DesignBoardTarget? = null)

    /** Null means an ordinary, ungrounded general-electronics conversation may proceed. */
    fun answer(utterance: String, index: LocalDesignContextIndex?): Answer? {
        protocol.validateRequest(DialogueRequest(utterance = utterance))
        val query = utterance.trim().replace(Regex("\\s+"), " ")
        val grounded = index?.groundedAnswer(query)
        val request = DialogueRequest(utterance = utterance, designContext = grounded?.designContext)
        protocol.ungroundedIdentityClarification(request)?.let { return Answer(it.question) }
        return grounded?.let { Answer(it.fallbackText, it.boardTarget) }
    }
}
