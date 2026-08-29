package dev.tokito.physical.voice.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal class ModelInventory(
    private val root: File,
    private val runtimeVersion: String,
) {
    private val manifestFile = File(root, "voice-models.manifest.json")

    data class Verification(val valid: Boolean, val digest: String = "", val reason: String = "")

    fun verify(): Verification {
        if (!manifestFile.isFile) return Verification(false, reason = "model manifest is absent")
        return runCatching {
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.getInt("schema_version") == 1) { "unsupported manifest schema" }
            require(manifest.getString("runtime_version") == runtimeVersion) {
                "model manifest targets another runtime version"
            }
            val expected = manifest.getJSONArray("files")
            val expectedPaths = mutableSetOf<String>()
            repeat(expected.length()) { index ->
                val item = expected.getJSONObject(index)
                val relative = item.getString("path")
                require(isSafeRelativePath(relative)) { "unsafe model path: $relative" }
                require(expectedPaths.add(relative)) { "duplicate model path: $relative" }
                val file = File(root, relative)
                require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    "model path escaped the model root"
                }
                require(file.isFile) { "missing model file: $relative" }
                require(file.length() == item.getLong("size")) { "model size mismatch: $relative" }
                require(sha256(file) == item.getString("sha256")) { "model digest mismatch: $relative" }
            }
            val actualPaths = modelFiles().map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
            require(actualPaths == expectedPaths) { "model inventory contains missing or unexpected files" }
            val digest = canonicalDigest(expected)
            require(digest == manifest.getString("inventory_sha256")) { "inventory digest mismatch" }
            Verification(true, digest)
        }.getOrElse { Verification(false, reason = it.message ?: "model verification failed") }
    }

    fun record(): Verification {
        root.mkdirs()
        val files = modelFiles()
        require(files.isNotEmpty()) { "no model files were provisioned" }
        val entries = JSONArray()
        files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
            entries.put(
                JSONObject()
                    .put("path", file.relativeTo(root).invariantSeparatorsPath)
                    .put("size", file.length())
                    .put("sha256", sha256(file)),
            )
        }
        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("runtime_version", runtimeVersion)
            .put("inventory_sha256", canonicalDigest(entries))
            .put("files", entries)
        val temporary = File(root, "${manifestFile.name}.part")
        FileOutputStream(temporary).use { output ->
            output.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(manifestFile)) { "failed to activate model manifest" }
        return verify()
    }

    fun clear() {
        if (!root.exists()) return
        root.walkBottomUp().forEach { file ->
            if (file != root && !file.delete()) {
                throw IllegalStateException("failed to remove invalid model content: ${file.name}")
            }
        }
    }

    private fun modelFiles(): List<File> = root.walkTopDown()
        .filter { it.isFile && it != manifestFile && !it.name.endsWith(".part") }
        .toList()

    private fun canonicalDigest(entries: JSONArray): String {
        val lines = buildList {
            repeat(entries.length()) { index ->
                val item = entries.getJSONObject(index)
                add("${item.getString("path")}:${item.getLong("size")}:${item.getString("sha256")}")
            }
        }.sorted().joinToString("\n")
        return sha256(lines.toByteArray(Charsets.UTF_8))
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.startsWith('\\') &&
            path.split('/', '\\').none { it == ".." || it.isBlank() }

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
