package dev.tokito.physical.symbols

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Injected by the product shell. The camera/AR runtime has no network client. */
fun interface BoardSymbolService {
    suspend fun generate(contextRevision: String): String
}

object BoardSymbolServices {
    @Volatile var active: BoardSymbolService? = null
}

/** Drafts are private review artifacts and never replace board placement truth. */
object BoardSymbolDrafts {
    fun validate(raw: String, contextRevision: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..4_194_304) { "Symbol response exceeds its size limit" }
        val document = JSONObject(raw)
        require(document.getString("schema_version") == "dsvire.board-symbols.v1") { "Unsupported symbol response" }
        require(document.getString("context_revision") == contextRevision) { "Board context changed" }
        require(document.get("publication_allowed") == false) { "Draft response cannot authorize publication" }
        val parts = document.getJSONArray("components")
        require(parts.length() in 1..8) { "Invalid symbol count" }
        val references = mutableSetOf<String>()
        for (index in 0 until parts.length()) {
            val part = parts.getJSONObject(index)
            require(part.getString("reference").isNotBlank() && references.add(part.getString("reference"))) { "Invalid component reference" }
            when (part.getString("status")) {
                "draft" -> {
                    val symbol = part.getJSONObject("symbol")
                    require(symbol.getString("schema_version") == "dsvire.symbol-result.v1") { "Unsupported symbol draft" }
                    for (key in listOf("manufacturer", "mpn", "package")) {
                        require(symbol.getJSONObject("identity").getString(key) == part.getJSONObject("identity").getString(key)) { "Draft identity mismatch" }
                    }
                    val pins = symbol.getJSONArray("pins")
                    require(pins.length() in 4..2048 && symbol.getJSONArray("citations").length() > 0) { "Incomplete draft evidence" }
                    val numbers = mutableSetOf<String>()
                    for (pinIndex in 0 until pins.length()) {
                        val pin = pins.getJSONObject(pinIndex)
                        require(pin.getString("number").isNotBlank() && numbers.add(pin.getString("number"))) { "Invalid pin number" }
                        require(pin.getString("name").length in 1..160 && pin.getString("electrical").length in 1..40) { "Invalid pin label" }
                    }
                }
                "existing", "abstained", "unavailable" -> Unit
                else -> error("Unsupported symbol status")
            }
        }
        return document
    }

    fun save(filesDir: File, raw: String, contextRevision: String) {
        validate(raw, contextRevision)
        val key = MessageDigest.getInstance("SHA-256").digest(contextRevision.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(filesDir, "symbol-drafts").also { check(it.mkdirs() || it.isDirectory) }
        val temporary = File(directory, "$key.tmp")
        temporary.writeText(raw, Charsets.UTF_8)
        check(temporary.renameTo(File(directory, "$key.json"))) { "Could not save symbol drafts" }
    }

    fun summary(document: JSONObject): String = buildString {
        append("Private drafts for review. No symbols were published.\n\n")
        val components = document.getJSONArray("components")
        for (index in 0 until components.length()) {
            val part = components.getJSONObject(index)
            append(part.getString("reference"))
            when (part.getString("status")) {
                "existing" -> append(": already in the reviewed catalog\n\n")
                "draft" -> {
                    val symbol = part.getJSONObject("symbol")
                    val pins = symbol.getJSONArray("pins")
                    append(": ").append(symbol.getJSONObject("identity").getString("mpn")).append(" — draft\n")
                    for (pinIndex in 0 until minOf(32, pins.length())) {
                        val pin = pins.getJSONObject(pinIndex)
                        append(pin.getString("number")).append("  ").append(pin.getString("name")).append("  ").append(pin.getString("electrical")).append('\n')
                    }
                    if (pins.length() > 32) append("Showing 32 of ${pins.length()} pins.\n")
                    append("Datasheet citations: ${symbol.getJSONArray("citations").length()}\n\n")
                }
                "abstained" -> append(": needs verified part and datasheet evidence\n\n")
                else -> append(if (part.optString("code") == "result_size_limit") {
                    ": draft is too large for mobile review\n\n"
                } else {
                    ": service unavailable; try again later\n\n"
                })
            }
        }
    }
}
