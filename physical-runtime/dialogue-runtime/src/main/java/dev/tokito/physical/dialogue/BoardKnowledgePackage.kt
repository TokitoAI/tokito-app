package dev.tokito.physical.dialogue

import org.json.JSONObject
import java.io.File

/** Only call with a directory admitted by the signed embedding-bundle repository. */
class BoardKnowledgePackage private constructor(
    val store: BoardKnowledgeStore,
    val scopes: List<Scope>,
) : AutoCloseable {
    data class Scope(val boardId: String, val aliases: List<String>)

    fun resolve(question: String): Scope? {
        return matchingScopes(question).singleOrNull()
    }

    fun matchingScopes(question: String): List<Scope> {
        return matchingReferenceScopes(scopes, question)
    }

    fun mentionsUnsupportedVariant(question: String): Boolean = matchingScopes(question).isEmpty() && scopes.any { scope ->
        scope.aliases.any { alias ->
            val words = alias.lowercase(java.util.Locale.ROOT).split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty)
            val pattern = words.joinToString("[-_\\s]+") { Regex.escape(it) }
            Regex("(?<![a-z0-9-])$pattern[-_][a-z0-9]+", RegexOption.IGNORE_CASE).containsMatchIn(question)
        }
    }

    override fun close() = store.close()

    companion object {
        private fun normalize(text: String) = text.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()

        fun openIfPresent(admittedDirectory: File): BoardKnowledgePackage? {
            val manifest = File(admittedDirectory, "knowledge.manifest.json")
            if (!manifest.exists()) return null
            require(manifest.length() in 1..64 * 1024)
            val value = JSONObject(manifest.readText())
            require(value.getInt("schema_version") == 1)
            val boards = value.getJSONArray("boards")
            require(boards.length() in 1..64)
            val scopes = (0 until boards.length()).map { i ->
                val board = boards.getJSONObject(i)
                val id = board.getString("board_id")
                require(id.matches(Regex("[a-z0-9_-]{1,128}")))
                val aliases = board.getJSONArray("aliases")
                require(aliases.length() in 1..16)
                Scope(id, (0 until aliases.length()).map { j -> aliases.getString(j).also {
                    require(it.length in 2..128 && it.none(Char::isISOControl) && normalize(it).isNotBlank())
                } })
            }
            require(scopes.map { it.boardId }.distinct().size == scopes.size)
            val store = BoardKnowledgeStore(File(admittedDirectory, "knowledge.sqlite"),
                value.getString("database_sha256"), value.getString("corpus_sha256"), value.getString("encoder_model_sha256"))
            return BoardKnowledgePackage(store, scopes)
        }
    }
}

internal fun matchingReferenceScopes(scopes: List<BoardKnowledgePackage.Scope>, question: String): List<BoardKnowledgePackage.Scope> {
    val query = question.lowercase(java.util.Locale.ROOT)
    return scopes.filter { scope -> scope.aliases.any { alias ->
        val words = alias.lowercase(java.util.Locale.ROOT).split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty)
        val pattern = words.joinToString("[-_\\s]+") { Regex.escape(it) }
        Regex("(?<![a-z0-9-])$pattern(?![a-z0-9-])").containsMatchIn(query)
    } }
}
