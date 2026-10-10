package dev.tokito.physical.dialogue

/** Production advice requires admitted evidence, regardless of the question's wording. */
class LocalBenchDialoguePolicy {
    private val protocol = DialogueProtocol(DialogueLimits())

    enum class Authority { ADMITTED_EVIDENCE, ABSTENTION }
    data class Answer(
        val text: String,
        val boardTarget: DesignBoardTarget? = null,
        val authority: Authority = Authority.ABSTENTION,
    )

    fun answer(utterance: String, index: LocalDesignContextIndex?): Answer {
        protocol.validateRequest(DialogueRequest(utterance = utterance))
        val query = utterance.trim().replace(Regex("\\s+"), " ")
        val grounded = index?.groundedAnswer(query)
        val request = DialogueRequest(utterance = utterance, designContext = grounded?.designContext)
        protocol.ungroundedIdentityClarification(request)?.let { return Answer(it.question) }
        return grounded?.let { Answer(it.fallbackText, it.boardTarget, Authority.ADMITTED_EVIDENCE) }
            ?: Answer("I don't have verified source evidence for that question, so I can't safely give technical advice. Provide reviewed documentation or a verified project reference.")
    }
}
