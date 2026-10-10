package dev.tokito.physical.dialogue

import org.json.JSONObject
import java.io.File

/** Only call with a directory admitted by the signed embedding-bundle repository. */
class BoardKnowledgePackage private constructor(
    val store: BoardKnowledgeStore,
    val scopes: List<Scope>,
) : AutoCloseable {
    data class Scope(val boardId: String, val aliases: List<String>)

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
