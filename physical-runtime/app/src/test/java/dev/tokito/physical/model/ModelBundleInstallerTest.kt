package dev.tokito.physical.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelBundleInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiesReleaseSignatureAndRejectsChangedManifest() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val manifest = "{\"model_id\":\"signed\"}".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(manifest)
            sign()
        }
        val verifier = EcdsaModelSignatureVerifier(
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )

        assertTrue(verifier.verify(manifest, signature))
        assertFalse(verifier.verify("changed".toByteArray(), signature))
    }

    @Test
    fun installsVerifiedBundleAndKeepsExactlyOneRollback() {
        val installer = installer()
        installer.install(bundle("one", byteArrayOf(1, 2, 3)))
        installer.install(bundle("two", byteArrayOf(4, 5, 6)))
        installer.install(bundle("three", byteArrayOf(7, 8, 9)))

        assertArrayEquals(byteArrayOf(7, 8, 9), activeBytes(installer))
        assertArrayEquals(
            byteArrayOf(4, 5, 6),
            temporaryFolder.root.resolve("models/rollback/weights.bin").readBytes(),
        )

        installer.rollback()
        assertArrayEquals(byteArrayOf(4, 5, 6), activeBytes(installer))
        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            temporaryFolder.root.resolve("models/rollback/weights.bin").readBytes(),
        )
    }

    @Test
    fun invalidSignatureFailsClosedWithoutReplacingActiveBundle() {
        val installer = installer()
        installer.install(bundle("trusted", byteArrayOf(1)))

        assertFails("signature") {
            installer.install(bundle("tampered", byteArrayOf(2), signature = "wrong".toByteArray()))
        }

        assertArrayEquals(byteArrayOf(1), activeBytes(installer))
        assertFalse(temporaryFolder.root.resolve("models/rollback").exists())
    }

    @Test
    fun conformanceFailureLeavesInstalledTreesUntouched() {
        val installer = installer(conformance = { _, manifest -> manifest.getString("model_revision") != "bad" })
        installer.install(bundle("good", byteArrayOf(1)))

        assertFails("conformance") { installer.install(bundle("bad", byteArrayOf(2))) }

        assertArrayEquals(byteArrayOf(1), activeBytes(installer))
        assertFalse(temporaryFolder.root.resolve("models/rollback").exists())
    }

    @Test
    fun rejectsUnsafePathsAndManifestInventoryDrift() {
        val installer = installer()
        assertFails("unsafe path") {
            installer.install(rawBundle(mapOf("../weights.bin" to byteArrayOf(1))))
        }
        assertFails("inventory") {
            installer.install(bundle("extra", byteArrayOf(1), extra = mapOf("surprise.bin" to byteArrayOf(2))))
        }
        assertFalse(temporaryFolder.root.resolve("models/active").exists())
    }

    @Test
    fun rejectsRuntimeAbiLicenseAndDigestMismatch() {
        val installer = installer()
        assertFails("another runtime") { installer.install(bundle("runtime", byteArrayOf(1), runtime = "other")) }
        assertFails("ABI") { installer.install(bundle("abi", byteArrayOf(1), abi = "x86_64")) }
        assertFails("license") { installer.install(bundle("license", byteArrayOf(1), license = "unknown")) }
        assertFails("digest") { installer.install(bundle("digest", byteArrayOf(1), declaredDigest = "0".repeat(64))) }
    }

    @Test
    fun enforcesArchiveBundleEntryAndCombinedInstalledQuotas() {
        assertFails("download limit") {
            installer(maxArchiveBytes = 32).install(ByteArrayInputStream(ByteArray(33)))
        }
        assertFails("expanded model bundle") {
            installer(maxExpandedBundleBytes = 2).install(bundle("expanded", byteArrayOf(1, 2, 3)))
        }
        assertFails("too many entries") {
            installer(maxEntries = 2).install(bundle("entries", byteArrayOf(1)))
        }

        val quotaInstaller = installer(maxExpandedBundleBytes = 700, maxInstalledBytes = 900)
        quotaInstaller.install(bundle("first", ByteArray(300)))
        assertFails("active and rollback") {
            quotaInstaller.install(bundle("second", ByteArray(300)))
        }
        assertArrayEquals(ByteArray(300), activeBytes(quotaInstaller))
    }

    private fun installer(
        maxArchiveBytes: Long = 1024 * 1024,
        maxExpandedBundleBytes: Long = 1024 * 1024,
        maxInstalledBytes: Long = 2 * 1024 * 1024,
        maxEntries: Int = 32,
        conformance: ModelConformanceVerifier = ModelConformanceVerifier { _, _ -> true },
    ) = ModelBundleInstaller(
        root = temporaryFolder.root.resolve("models"),
        policy = ModelInstallPolicy(
            capability = "dialogue",
            runtimeRevision = "runtime-1",
            abi = "arm64-v8a",
            allowedLicenses = setOf("apache-2.0"),
            maxArchiveBytes = maxArchiveBytes,
            maxExpandedBundleBytes = maxExpandedBundleBytes,
            maxInstalledBytes = maxInstalledBytes,
            maxEntries = maxEntries,
        ),
        signatures = ModelSignatureVerifier { manifest, signature ->
            MessageDigest.isEqual(sha256(manifest).toByteArray(), signature)
        },
        conformance = conformance,
    )

    private fun activeBytes(installer: ModelBundleInstaller) =
        requireNotNull(installer.activeDirectory()).resolve("weights.bin").readBytes()

    private fun ModelBundleInstaller.install(bundle: ByteArray): JSONObject =
        install(ByteArrayInputStream(bundle))

    private fun bundle(
        revision: String,
        weights: ByteArray,
        runtime: String = "runtime-1",
        abi: String = "arm64-v8a",
        license: String = "apache-2.0",
        signature: ByteArray? = null,
        declaredDigest: String = sha256(weights),
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("capability", "dialogue")
            .put("runtime_revision", runtime)
            .put("supported_abis", JSONArray().put(abi))
            .put("license", license)
            .put("model_id", "dialogue")
            .put("model_revision", revision)
            .put(
                "artifacts",
                JSONArray().put(
                    JSONObject()
                        .put("path", "weights.bin")
                        .put("size_bytes", weights.size)
                        .put("sha256", declaredDigest),
                ),
            )
        val manifestBytes = manifest.toString().toByteArray()
        val files = linkedMapOf(
            "model.manifest.json" to manifestBytes,
            "model.manifest.sig" to (signature ?: sha256(manifestBytes).toByteArray()),
            "weights.bin" to weights,
        )
        files.putAll(extra)
        return rawBundle(files)
    }

    private fun rawBundle(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (path, contents) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun assertFails(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected failure containing '$message'", failure != null)
        assertTrue("actual message: ${failure?.message}", failure?.message.orEmpty().contains(message, ignoreCase = true))
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
