package dev.tokito.physical.model

import ai.tokito.physical.ar.DigitalTwinValidation
import ai.tokito.physical.ar.DigitalTwinValidator
import org.json.JSONObject
import java.io.File

/** Read only after the containing vision bundle has passed signed inventory admission. */
object ReferenceTwinCatalog {
    data class Entry(val label: String, val manifest: File, val glb: File, val contextRevision: String,
        val tracking: File? = null)

    fun read(admittedRoot: File): List<Entry> {
        val index = File(admittedRoot, "reference-twins.json")
        if (!index.exists()) return emptyList()
        require(index.isFile && index.length() in 1..65536) { "Reference model catalog exceeds its budget" }
        val buffer = ByteArray(65537)
        val count = index.inputStream().use { input ->
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        require(count in 1..65536) { "Reference model catalog exceeds its budget" }
        val document = JSONObject(String(buffer, 0, count, Charsets.UTF_8))
        require(document.keys().asSequence().toSet() == setOf("schema_version", "entries"))
        require(document.getInt("schema_version") == 1)
        val entries = document.getJSONArray("entries")
        require(entries.length() in 1..32)
        val root = admittedRoot.canonicalFile.toPath()
        fun file(path: String): File {
            require(path.matches(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")) && !path.contains(".."))
            val result = File(admittedRoot, path).canonicalFile
            require(result.toPath().startsWith(root) && result.isFile)
            return result
        }
        val result = (0 until entries.length()).map { i ->
            val entry = entries.getJSONObject(i)
            val keys = entry.keys().asSequence().toSet()
            require(keys == setOf("label", "manifest", "glb", "context_revision") ||
                keys == setOf("label", "manifest", "glb", "context_revision", "tracking"))
            val label = entry.getString("label")
            val revision = entry.getString("context_revision")
            require(label.isNotBlank() && label.length <= 128 && label.none(Char::isISOControl))
            require(revision.isNotBlank() && revision.length <= 256 && revision.none(Char::isISOControl))
            Entry(label, file(entry.getString("manifest")), file(entry.getString("glb")), revision,
                if (entry.has("tracking")) file(entry.getString("tracking")) else null)
        }
        require(result.map { it.label }.distinct().size == result.size)
        require(result.map { it.manifest }.distinct().size == result.size)
        return result
    }

    fun verify(admittedRoot: File) {
        read(admittedRoot).forEach { entry ->
            require(DigitalTwinValidator().load(entry.manifest, entry.glb, entry.contextRevision)
                is DigitalTwinValidation.Accepted) { "Reference model failed package conformance" }
        }
    }
}
