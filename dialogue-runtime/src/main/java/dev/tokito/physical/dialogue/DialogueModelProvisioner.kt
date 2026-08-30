package dev.tokito.physical.dialogue

import java.io.File
import java.security.MessageDigest

import org.json.JSONArray
import org.json.JSONObject

data class DialogueModelArtifactManifest(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class DialogueModelManifest(
    val modelId: String,
    val runtime: DialogueRuntime,
    val bundlePath: String,
    val modelPath: String,
    val artifacts: List<DialogueModelArtifactManifest>,
)

class DialogueModelProvisioner private constructor(
    private val modelDirectory: File,
    private val manifest: DialogueModelManifest,
) {

    fun installed(): DialogueModel? {
        val bundlePath = resolveInsideModelDirectory(manifest.bundlePath) ?: return null
        val modelPath = resolveInsideModelDirectory(manifest.modelPath) ?: return null
        if (!bundlePath.isDirectory || !modelPath.isFile || !modelPath.toPath().startsWith(bundlePath.toPath())) return null
        val artifacts = manifest.artifacts.map { artifact ->
            val file = resolveInsideModelDirectory(artifact.relativePath) ?: return null
            if (!file.isFile || file.length() != artifact.sizeBytes || sha256(file) != artifact.sha256) return null
            DialogueModelArtifact(file, artifact.relativePath, artifact.sizeBytes, artifact.sha256)
        }
        val admittedPaths = artifacts.mapTo(mutableSetOf()) { it.file.canonicalFile }
        val actualPaths = bundlePath.walkTopDown().filter(File::isFile).map(File::getCanonicalFile).toSet()
        if (actualPaths != admittedPaths) return null
        return DialogueModel(modelPath, artifacts, manifest.modelId, manifest.runtime)
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

    private fun resolveInsideModelDirectory(relativePath: String): File? {
        val root = modelDirectory.canonicalFile
        val resolved = File(root, relativePath).canonicalFile
        return resolved.takeIf { it != root && it.toPath().startsWith(root.toPath()) }
    }

    companion object {
        private const val MANIFEST_FILE_NAME = "dialogue-model.manifest.json"
        private val SAFE_RELATIVE_PATH = Regex("[A-Za-z0-9._/-]+")

        fun fromAdmittedBundle(modelDirectory: File): DialogueModelProvisioner {
            val manifestFile = File(modelDirectory, MANIFEST_FILE_NAME)
            if (!manifestFile.isFile) {
                throw DialogueFailure(DialogueFailureCode.MODEL_MISSING, "Dialogue model manifest is unavailable")
            }
            val document = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }
                .getOrElse { throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Dialogue model manifest is invalid", it) }
            val manifest = runCatching {
                require(document.getInt("schema_version") == 2)
                val artifacts = document.getJSONArray("artifacts").toArtifactManifests()
                DialogueModelManifest(
                    modelId = document.getString("model_id"),
                    runtime = DialogueRuntime.entries.single { it.wireValue == document.getString("runtime_id") },
                    bundlePath = document.getString("bundle_path"),
                    modelPath = document.getString("model_path"),
                    artifacts = artifacts,
                ).also {
                    require(it.modelId.isNotBlank())
                    require(isSafeRelativePath(it.bundlePath))
                    require(isSafeRelativePath(it.modelPath))
                    require(it.artifacts.isNotEmpty())
                    require(it.artifacts.map { artifact -> artifact.relativePath }.distinct().size == it.artifacts.size)
                    require(it.artifacts.all { artifact ->
                        isSafeRelativePath(artifact.relativePath) &&
                            artifact.sizeBytes > 0 &&
                            artifact.sha256.matches(Regex("[a-f0-9]{64}"))
                    })
                }
            }.getOrElse { throw DialogueFailure(DialogueFailureCode.MODEL_INTEGRITY, "Dialogue model manifest is invalid", it) }
            return DialogueModelProvisioner(modelDirectory, manifest)
        }

        private fun JSONArray.toArtifactManifests(): List<DialogueModelArtifactManifest> =
            List(length()) { index ->
                getJSONObject(index).let { artifact ->
                    DialogueModelArtifactManifest(
                        relativePath = artifact.getString("path"),
                        sizeBytes = artifact.getLong("size_bytes"),
                        sha256 = artifact.getString("sha256"),
                    )
                }
            }

        private fun isSafeRelativePath(path: String): Boolean =
            path.matches(SAFE_RELATIVE_PATH) &&
                !path.startsWith('/') &&
                path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }
}
