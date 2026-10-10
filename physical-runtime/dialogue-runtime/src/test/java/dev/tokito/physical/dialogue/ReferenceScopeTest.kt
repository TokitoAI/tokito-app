package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class ReferenceScopeTest {
    private val scopes = listOf(
        BoardKnowledgePackage.Scope("one", listOf("Alpha32", "Alpha32 Dev Kit")),
        BoardKnowledgePackage.Scope("two", listOf("Alpha8266")),
        BoardKnowledgePackage.Scope("three", listOf("FRDM ABC123")),
    )
    @Test fun namesAreDataDrivenAndTokenBounded() {
        assertEquals("one", matchingReferenceScopes(scopes, "what is ALPHA32?").single().boardId)
        assertEquals("three", matchingReferenceScopes(scopes, "explain FRDM-ABC123").single().boardId)
        assertTrue(matchingReferenceScopes(scopes, "Alpha32-S3").isEmpty())
        assertTrue(matchingReferenceScopes(scopes, "notAlpha32").isEmpty())
        assertTrue(matchingReferenceScopes(scopes, "unlisted board").isEmpty())
        assertEquals(2, matchingReferenceScopes(scopes, "Alpha32 versus Alpha8266").size)
    }
}
