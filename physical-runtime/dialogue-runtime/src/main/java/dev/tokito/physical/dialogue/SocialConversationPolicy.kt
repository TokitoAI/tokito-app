package dev.tokito.physical.dialogue

import java.util.Locale

/** Only complete social utterances bypass technical retrieval; no entity routing. */
object SocialConversationPolicy {
    fun reply(utterance: String): DialogueOutcome.Response? {
        if (utterance.length > 80 || utterance.any { it.isISOControl() && !it.isWhitespace() }) return null
        val words = utterance.lowercase(Locale.ROOT).trim()
            .replace(Regex("[.,!?]+"), " ").replace(Regex("\\s+"), " ").trim()
        val text = when {
            Regex("(?:hi|hello|hey|good morning|good afternoon|good evening)(?: tokito)?").matches(words) ->
                "Hi! How can I help?"
            words in setOf("thanks", "thank you", "thanks tokito", "thank you tokito") -> "You're welcome."
            words in setOf("bye", "goodbye", "bye tokito", "goodbye tokito") -> "See you!"
            words in setOf("how are you", "how are you tokito") -> "I'm here to help. What would you like to do?"
            else -> return null
        }
        return DialogueOutcome.Response(text)
    }
}
