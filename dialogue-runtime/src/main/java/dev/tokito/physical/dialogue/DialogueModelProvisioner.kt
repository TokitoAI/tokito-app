package dev.tokito.physical.dialogue

import java.io.File
import java.security.MessageDigest

import org.json.JSONObject

data class DialogueModelManifest(
    val modelId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

class DialogueModelProvisioner private constructor(
    private val modelDirectory: File,
    private val manifest: DialogueModelManifest,
) {

    fun installed(): DialogueModel? {
        val file = File(modelDirectory, manifest.fileName)
        if (!file.isFile || file.length() != manifest.sizeBytes || sha256(file) != manifest.sha256) return null
        return DialogueModel(file, manifest.sha256, manifest.modelId)
    }

    fun requireInstalled(): DialogueModel = installed()
        ?: throw DialogueFailure(DialogueFailureCode.MODEL_MISSING, "Verified dialogue model bundle is unavailable or invalid")

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

    companion object {
        private const val MANIFEST_FILE_NAME = "dialogue-model.manifest.json"

        fun fromAdmittedBundle(modelDirectory: File): DialogueModelProvisioner {
            val manifestFile = File(modelDirectory, MANIFEST_FILE_NAME)
            if (!manifestFile.isFile) {
                throw DialogueFailure(DialogueFailureCode.MODEL_MISSING, "Dialogue model manifest is unavailable")
            }
            val document = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }
                .getOrElse { throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Dialogue model manifest is invalid", it) }
            val manifest = runCatching {
                DialogueModelManifest(
                    modelId = document.getString("model_id"),
                    fileName = document.getString("file_name"),
                    sizeBytes = document.getLong("size_bytes"),
                    sha256 = document.getString("sha256"),
                ).also {
                    require(it.modelId.isNotBlank())
                    require(it.fileName.matches(Regex("[A-Za-z0-9._-]+")))
                    require(it.sizeBytes > 0)
                    require(it.sha256.matches(Regex("[a-f0-9]{64}")))
                }
            }.getOrElse { throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Dialogue model manifest is invalid", it) }
            return DialogueModelProvisioner(modelDirectory, manifest)
        }
    }
}
