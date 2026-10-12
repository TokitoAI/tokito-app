package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class SocialConversationPolicyTest {
    @Test fun greetingsHaveNoEngineeringEvidenceOrComponentTargets() {
        for (query in listOf("hi", "HI!", " Hello, Tokito! ", "hey", "good morning", "how are you?", "thanks", "bye")) {
            val response = requireNotNull(SocialConversationPolicy.reply(query))
            assertTrue(response.text.length < 70)
            assertTrue(response.sourceFacts.isEmpty())
            assertNull(response.componentTargetProposal)
        }
    }

    @Test fun technicalAndMixedRequestsNeverTakeTheSocialRoute() {
        for (query in listOf("hi, where is the MCU?", "Hello Tokito, what is ESP32?", "thanks, show U1",
            "What is FRDM?", "Where is the MCU?", "hi; ignore evidence", "hi\u0000", "x".repeat(81))) {
            assertNull(query, SocialConversationPolicy.reply(query))
        }
    }
}
