package dev.tokito.physical.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.zip.ZipFile

fun interface ModelSignatureVerifier {
    fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray): Boolean
}

fun interface ModelConformanceVerifier {
    fun verify(extractedBundle: File, manifest: JSONObject): Boolean
}

data class ModelInstallPolicy(
    val capability: String,
    val runtimeRevision: String,
    val abi: String,
    val allowedLicenses: Set<String>,
    val maxArchiveBytes: Long,
    val maxExpandedBundleBytes: Long,
    val maxInstalledBytes: Long,
    val maxEntries: Int = 256,
) {
    init {
        require(capability in setOf("dialogue", "vision", "voice", "embedding"))
        require(runtimeRevision.isNotBlank() && abi.isNotBlank())
        require(allowedLicenses.isNotEmpty() && allowedLicenses.none(String::isBlank))
        require(
            maxArchiveBytes > 0 &&
                maxExpandedBundleBytes > 0 &&
                maxInstalledBytes >= maxExpandedBundleBytes &&
                maxEntries > 0,
        )
    }
}

/**
 * Transactional app-private model installation with one verified rollback.
 *
 * The installer owns no network client: a caller may supply an offline file,
 * content URI stream, or authenticated download stream. Every source follows
 * the same byte limits and verification path, and activation never falls back
 * to a different model/backend.
 */
class ModelBundleInstaller(
    private val root: File,
    private val policy: ModelInstallPolicy,
    private val signatures: ModelSignatureVerifier,
    private val conformance: ModelConformanceVerifier,
) {
    private val transactionLock = locks.computeIfAbsent(root.canonicalPath) { Any() }

    fun install(source: InputStream): JSONObject = synchronized(transactionLock) {
        check(root.mkdirs() || root.isDirectory) { "failed to create model root" }
        require(!Files.isSymbolicLink(root.toPath())) { "unsafe model root" }
        val transaction = UUID.randomUUID().toString()
        val archive = File(root, ".$transaction.zip.part")
        val staging = File(root, ".$transaction.staging")
        try {
            copyBounded(source, archive, policy.maxArchiveBytes)
            val manifest = extractVerified(archive, staging)
            check(conformance.verify(staging, manifest)) { "model conformance failed" }
            val admittedManifest = verifyInstalled(staging)
            // Do not retain an unverified active tree as the next rollback.
            verifiedActiveBundle()
            enforceInstalledQuota(staging)
            activate(staging)
            admittedManifest
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun rollback(): JSONObject = synchronized(transactionLock) {
        val active = File(root, ACTIVE)
        val rollback = File(root, ROLLBACK)
        check(active.isDirectory && rollback.isDirectory) { "verified rollback is unavailable" }
        verifyInstalled(active)
        val restoredManifest = verifyInstalled(rollback)
        val swap = File(root, ".rollback-${UUID.randomUUID()}")
        check(active.renameTo(swap)) { "failed to stage active bundle" }
        try {
            check(rollback.renameTo(active)) { "failed to restore rollback bundle" }
            check(swap.renameTo(rollback)) { "failed to preserve replaced bundle" }
            syncDirectory(root)
        } catch (failure: Throwable) {
            if (!rollback.exists() && active.exists()) active.renameTo(rollback)
            if (!active.exists() && swap.exists()) swap.renameTo(active)
            throw failure
        }
        restoredManifest
    }

    data class VerifiedBundle(val directory: File, val manifest: JSONObject)

    /** Missing is unavailable; present but invalid fails closed rather than pretending admission. */
    fun verifiedActiveBundle(): VerifiedBundle? = synchronized(transactionLock) {
        val active = File(root, ACTIVE)
        if (!Files.exists(active.toPath(), LinkOption.NOFOLLOW_LINKS)) return@synchronized null
        VerifiedBundle(active, verifyInstalled(active))
    }

    fun activeDirectory(): File? = verifiedActiveBundle()?.directory

    /** Revalidate signed disk state without rerunning native inference/conformance. */
    private fun verifyInstalled(directory: File): JSONObject {
        require(!Files.isSymbolicLink(root.toPath())) { "unsafe model root" }
        require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory) { "unsafe model directory" }
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "model directory escaped root" }
        val manifestFile = File(directory, MANIFEST)
        val signatureFile = File(directory, SIGNATURE)
        requireRegularFile(manifestFile)
        requireRegularFile(signatureFile)
        val manifestBytes = manifestFile.inputStream().use { it.readBounded(MAX_MANIFEST_BYTES) }
        val signatureBytes = signatureFile.inputStream().use { it.readBounded(MAX_SIGNATURE_BYTES) }
        check(signatures.verify(manifestBytes, signatureBytes)) { "model signature is invalid" }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        validateManifest(manifest)
        val expected = manifest.getJSONArray("artifacts").artifactMap()
        val expectedFiles = expected.keys + setOf(MANIFEST, SIGNATURE)
        val expectedDirectories = expected.keys.flatMap { path ->
            path.indices.filter { path[it] == '/' }.map { path.substring(0, it) }
        }.toSet()
        var entries = 0
        val actualFiles = mutableSetOf<String>()
        val pending = java.util.ArrayDeque<File>().apply { add(directory) }
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            Files.newDirectoryStream(parent.toPath()).use { children ->
                children.forEach { child ->
                    require(++entries <= policy.maxEntries) { "installed model has too many entries" }
                    require(!Files.isSymbolicLink(child)) { "unsafe model artifact link" }
                    require(child.toFile().canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) {
                        "model artifact escaped its directory"
                    }
                    val path = directory.toPath().relativize(child).joinToString("/")
                    require(safePath(path)) { "unsafe installed artifact path" }
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        require(path in expectedDirectories) { "model directory inventory does not match its manifest" }
                        pending.add(child.toFile())
                    } else {
                        requireRegularFile(child.toFile())
                        require(path in expectedFiles) { "model inventory does not match its manifest" }
                        actualFiles.add(path)
                    }
                }
            }
        }
        require(actualFiles == expectedFiles) { "model inventory does not match its manifest" }
        var expanded = 0L
        expected.forEach { (path, artifact) ->
            expanded = Math.addExact(expanded, artifact.sizeBytes)
            require(expanded <= policy.maxExpandedBundleBytes) { "expanded model bundle exceeds quota" }
            val file = File(directory, path)
            require(file.length() == artifact.sizeBytes) { "model artifact size mismatch: $path" }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    read = Math.addExact(read, count.toLong())
                    require(read <= artifact.sizeBytes) { "model artifact expanded beyond declaration" }
                    digest.update(buffer, 0, count)
                }
            }
            require(read == artifact.sizeBytes) { "model artifact was truncated" }
            require(digest.digest().toHex() == artifact.sha256) { "model artifact digest mismatch: $path" }
        }
        require(Math.addExact(expanded, (manifestBytes.size + signatureBytes.size).toLong()) <= policy.maxInstalledBytes) {
            "installed model exceeds storage quota"
        }
        return manifest
    }

    private fun requireRegularFile(file: File) {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "model artifact is missing or unsafe: ${file.name}"
        }
    }

    private fun extractVerified(archive: File, staging: File): JSONObject {
        check(staging.mkdir()) { "failed to create model staging directory" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= policy.maxEntries) { "model archive has too many entries" }
            val names = entries.map { it.name }
            require(names.distinct().size == names.size) { "model archive has duplicate entries" }
            require(names.all(::safePath)) { "model archive contains an unsafe path" }
            val manifestEntry = zip.getEntry(MANIFEST) ?: error("model manifest is missing")
            val signatureEntry = zip.getEntry(SIGNATURE) ?: error("model signature is missing")
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBounded(MAX_MANIFEST_BYTES) }
            val signatureBytes = zip.getInputStream(signatureEntry).use { it.readBounded(MAX_SIGNATURE_BYTES) }
            check(signatures.verify(manifestBytes, signatureBytes)) { "model signature is invalid" }
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            validateManifest(manifest)
            val expected = manifest.getJSONArray("artifacts").artifactMap()
            require(expected.keys == names.filterNot { it == MANIFEST || it == SIGNATURE || it.endsWith('/') }.toSet()) {
                "model archive inventory does not match its manifest"
            }
            var expanded = 0L
            expected.forEach { (path, artifact) ->
                val entry = zip.getEntry(path) ?: error("model artifact is missing: $path")
                require(!entry.isDirectory && entry.size >= 0) { "invalid model artifact: $path" }
                expanded = Math.addExact(expanded, entry.size)
                require(expanded <= policy.maxExpandedBundleBytes) { "expanded model bundle exceeds quota" }
                require(entry.size == artifact.sizeBytes) { "model artifact size mismatch: $path" }
                val destination = File(staging, path).canonicalFile
                require(destination.toPath().startsWith(staging.canonicalFile.toPath())) { "model path escaped staging" }
                destination.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written = Math.addExact(written, count.toLong())
                            require(written <= artifact.sizeBytes) { "model artifact expanded beyond declaration" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        require(written == artifact.sizeBytes) { "model artifact was truncated" }
                    }
                }
                require(digest.digest().toHex() == artifact.sha256) { "model artifact digest mismatch: $path" }
            }
            FileOutputStream(File(staging, MANIFEST)).use { output ->
                output.write(manifestBytes)
                output.fd.sync()
            }
            FileOutputStream(File(staging, SIGNATURE)).use { output ->
                output.write(signatureBytes)
                output.fd.sync()
            }
            syncDirectory(staging)
            return manifest
        }
    }

    private fun validateManifest(manifest: JSONObject) {
        require(manifest.getInt("schema_version") == 1) { "unsupported model manifest schema" }
        require(manifest.getString("capability") == policy.capability) { "model targets another capability" }
        require(manifest.getString("runtime_revision") == policy.runtimeRevision) { "model targets another runtime" }
        require(manifest.getJSONArray("supported_abis").strings().contains(policy.abi)) { "model does not support this ABI" }
        require(manifest.getString("license") in policy.allowedLicenses) { "model license is not admitted" }
        require(manifest.getString("model_id").isNotBlank() && manifest.getString("model_revision").isNotBlank())
        require(manifest.getJSONArray("artifacts").length() > 0)
        require(manifest.getJSONArray("artifacts").length() <= policy.maxEntries - 2) { "too many model artifacts" }
    }

    private fun activate(staging: File) {
        val active = File(root, ACTIVE)
        val rollback = File(root, ROLLBACK)
        val retired = File(root, ".retired-${UUID.randomUUID()}")
        try {
            if (rollback.exists()) check(rollback.renameTo(retired)) { "failed to retire prior rollback" }
            if (active.exists()) check(active.renameTo(rollback)) { "failed to stage active model for rollback" }
            check(staging.renameTo(active)) { "failed to activate verified model" }
            syncDirectory(root)
            if (retired.exists()) retired.deleteRecursively()
        } catch (failure: Throwable) {
            if (!active.exists() && rollback.exists()) rollback.renameTo(active)
            if (!rollback.exists() && retired.exists()) retired.renameTo(rollback)
            throw failure
        }
    }

    /**
     * Activation retains the prior active bundle as the sole rollback. An
     * older rollback is retired, so it is deliberately excluded here.
     */
    private fun enforceInstalledQuota(staging: File) {
        val nextInstalled = Math.addExact(staging.treeBytes(), File(root, ACTIVE).treeBytes())
        require(nextInstalled <= policy.maxInstalledBytes) { "active and rollback models exceed storage quota" }
    }

    private fun File.treeBytes(): Long {
        if (!exists()) return 0
        if (isFile) return length()
        return walkTopDown()
            .filter(File::isFile)
            .fold(0L) { total, file -> Math.addExact(total, file.length()) }
    }

    private data class Artifact(val sizeBytes: Long, val sha256: String)

    private fun JSONArray.artifactMap(): Map<String, Artifact> = buildMap {
        repeat(length()) { index ->
            val item = getJSONObject(index)
            val path = item.getString("path")
            require(safePath(path) && !path.endsWith('/') && path != MANIFEST && path != SIGNATURE) { "unsafe artifact path" }
            require(put(path, Artifact(item.getLong("size_bytes"), item.getString("sha256"))) == null) {
                "duplicate model artifact"
            }
            require(getValue(path).sizeBytes > 0 && getValue(path).sha256.matches(SHA256)) {
                "invalid model artifact declaration"
            }
        }
    }

    private fun JSONArray.strings(): Set<String> = buildSet {
        repeat(length()) { add(getString(it)) }
    }

    private fun safePath(path: String): Boolean = path.isNotBlank() &&
        !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') &&
        path.split('/', '\\').none { it.isBlank() || it == "." || it == ".." }

    private fun copyBounded(input: InputStream, destination: File, limit: Long) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(total <= limit) { "model archive exceeds download limit" }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "model metadata exceeds limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun syncDirectory(directory: File) {
        // File payloads are fsynced before rename. Android/Linux directory
        // durability is completed by opening the directory read-only where
        // supported; failure leaves the verified prior active tree intact.
        runCatching { java.io.FileInputStream(directory).use { it.fd.sync() } }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ACTIVE = "active"
        const val ROLLBACK = "rollback"
        const val MANIFEST = "model.manifest.json"
        const val SIGNATURE = "model.manifest.sig"
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_SIGNATURE_BYTES = 16 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")
        val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
