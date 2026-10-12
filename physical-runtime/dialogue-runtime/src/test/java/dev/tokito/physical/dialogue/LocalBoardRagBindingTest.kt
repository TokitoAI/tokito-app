package dev.tokito.physical.dialogue

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalBoardRagBindingTest {
    @Test(expected = IllegalArgumentException::class)
    fun physicalScopeCannotSearchUnboundWholeCorpus() {
        LocalBoardRag.Binding(null, "a".repeat(64), "registered:1", 10_000, physicalIdentityVerified = true)
    }

    @Test fun namedReferenceIsNotPhysicalIdentity() {
        val reference = LocalBoardRag.Binding("esp32_board", "a".repeat(64), "reference:1", 10_000)
        assertEquals(false, reference.physicalIdentityVerified)
    }
    @Test fun wholeCorpusReferenceSearchNeedsNoNamedBoard() {
        val binding = LocalBoardRag.Binding(null, "a".repeat(64), "library:1", 10_000)
        assertEquals(null, binding.boardId)
    }
    @Test fun explicitReferenceScopeRequiresNoArRegistration() {
        val binding = LocalBoardRag.Binding("esp32_board", "a".repeat(64), "reference-selection:1", 10_000)
        assertEquals("reference-selection:1", binding.scopeRevision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsControlCharactersInScopeRevision() {
        LocalBoardRag.Binding("esp32_board", "a".repeat(64), "selection\n1", 10_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingScopeRevision() {
        LocalBoardRag.Binding("esp32_board", "a".repeat(64), "", 10_000)
    }
}
