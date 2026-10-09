package dev.tokito.physical.symbols

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSymbolDraftsTest {
    private val raw = """{"schema_version":"dsvire.board-symbols.v1","context_revision":"board@1","publication_allowed":false,"components":[{"reference":"U1","status":"abstained"}]}"""

    @Test fun staleContextAndPublicationAuthorityAreRejected() {
        val document = BoardSymbolDrafts.validate(raw, "board@1")
        assertTrue(BoardSymbolDrafts.summary(document).contains("needs verified"))
        assertThrows(IllegalArgumentException::class.java) { BoardSymbolDrafts.validate(raw, "board@2") }
        assertThrows(IllegalArgumentException::class.java) {
            BoardSymbolDrafts.validate(JSONObject(raw).put("publication_allowed", true).toString(), "board@1")
        }
    }

    @Test fun duplicateReferencesAreRejected() {
        val document = JSONObject(raw)
        document.getJSONArray("components").put(JSONObject("""{"reference":"U1","status":"existing"}"""))
        assertThrows(IllegalArgumentException::class.java) { BoardSymbolDrafts.validate(document.toString(), "board@1") }
    }
}
