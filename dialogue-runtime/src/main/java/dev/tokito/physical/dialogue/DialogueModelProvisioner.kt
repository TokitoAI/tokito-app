package dev.tokito.physical.dialogue

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DialogueModelProvisioner(private val modelDirectory: File) {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long)

    companion object {
        const val MODEL_ID = "qwen3-0.6b-q8_0@23749fef"
        const val FILE_NAME = "Qwen3-0.6B-Q8_0.gguf"
        const val SIZE_BYTES = 639_446_688L
        const val SHA256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
        const val URL = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/23749fefcc72300e3a2ad315e1317431b06b590a/Qwen3-0.6B-Q8_0.gguf"
    }

    fun installed(): DialogueModel? {
        val file = File(modelDirectory, FILE_NAME)
        if (!file.isFile || file.length() != SIZE_BYTES || sha256(file) != SHA256) return null
        return DialogueModel(file, SHA256, MODEL_ID)
    }

    fun provision(onProgress: (Progress) -> Unit = {}): DialogueModel {
        installed()?.let { return it }
        modelDirectory.mkdirs()
        val finalFile = File(modelDirectory, FILE_NAME)
        val partial = File(modelDirectory, "$FILE_NAME.part")
        if (partial.length() > SIZE_BYTES) partial.delete()
        var offset = partial.length()
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            connection.connect()
            if (offset > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                partial.delete()
                offset = 0
                return provision(onProgress)
            }
            if (connection.responseCode !in 200..299) {
                throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Model download failed with HTTP ${connection.responseCode}")
            }
            FileOutputStream(partial, offset > 0).buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = offset
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        onProgress(Progress(written, SIZE_BYTES))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (partial.length() != SIZE_BYTES || sha256(partial) != SHA256) {
            partial.delete()
            throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Downloaded model failed pinned size or SHA-256 verification")
        }
        if (finalFile.exists() && !finalFile.delete()) {
            throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Could not replace the previous model")
        }
        if (!partial.renameTo(finalFile)) {
            throw DialogueFailure(DialogueFailureCode.MODEL_LOAD, "Could not atomically activate the model")
        }
        return DialogueModel(finalFile, SHA256, MODEL_ID)
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
