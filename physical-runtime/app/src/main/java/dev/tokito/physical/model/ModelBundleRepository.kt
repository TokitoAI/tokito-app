package dev.tokito.physical.model

import android.content.Context
import android.os.Build
import dev.tokito.physical.LocalPoseVisionRuntime
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import org.json.JSONObject
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class ModelCapability(val wireValue: String) {
    DIALOGUE("dialogue"),
    VISION("vision");

    companion object {
        fun parse(value: String): ModelCapability = entries.singleOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported model capability: $value")
    }
}

data class InstalledModelBundle(
    val capability: ModelCapability,
    val modelId: String,
    val modelRevision: String,
)

/** Owns the only admitted model directories used by the shipping runtime. */
class ModelBundleRepository(private val context: Context) {
    fun install(capability: ModelCapability, source: InputStream): InstalledModelBundle {
        val manifest = installer(capability).install(source)
        return manifest.toInstalledBundle(capability)
    }

    fun rollback(capability: ModelCapability): InstalledModelBundle {
        val installer = installer(capability)
        installer.rollback()
        val manifest = JSONObject(installer.activeDirectory()!!.resolve(MANIFEST).readText())
        return manifest.toInstalledBundle(capability)
    }

    fun activeDirectory(capability: ModelCapability) = installer(capability).activeDirectory()

    fun activeBundle(capability: ModelCapability): InstalledModelBundle? = runCatching {
        val directory = activeDirectory(capability) ?: return null
        JSONObject(directory.resolve(MANIFEST).readText()).toInstalledBundle(capability)
    }.getOrNull()

    private fun installer(capability: ModelCapability): ModelBundleInstaller = ModelBundleInstaller(
        root = context.filesDir.resolve("models/${capability.wireValue}"),
        policy = ModelInstallPolicy(
            capability = capability.wireValue,
            runtimeRevision = RUNTIME_REVISION,
            abi = Build.SUPPORTED_ABIS.firstOrNull()
                ?: throw IllegalStateException("device exposes no supported ABI"),
            allowedLicenses = ALLOWED_LICENSES,
            maxArchiveBytes = capability.archiveLimit,
            maxExpandedBundleBytes = capability.expandedLimit,
            maxInstalledBytes = Math.multiplyExact(capability.expandedLimit, 2),
        ),
        signatures = EcdsaModelSignatureVerifier(PUBLIC_KEY_DER_BASE64),
        conformance = ModelConformanceVerifier { bundle, _ ->
            when (capability) {
                ModelCapability.DIALOGUE -> {
                    DialogueModelProvisioner.fromAdmittedBundle(bundle).requireInstalled()
                    true
                }
                ModelCapability.VISION -> {
                    LocalPoseVisionRuntime.load(bundle).use { }
                    true
                }
            }
        },
    )

    private fun JSONObject.toInstalledBundle(expected: ModelCapability): InstalledModelBundle {
        check(getString("capability") == expected.wireValue)
        return InstalledModelBundle(expected, getString("model_id"), getString("model_revision"))
    }

    private val ModelCapability.archiveLimit: Long
        get() = when (this) {
            ModelCapability.DIALOGUE -> 3L * 1024L * 1024L * 1024L
            ModelCapability.VISION -> 256L * 1024L * 1024L
        }

    private val ModelCapability.expandedLimit: Long
        get() = when (this) {
            ModelCapability.DIALOGUE -> 3L * 1024L * 1024L * 1024L
            ModelCapability.VISION -> 256L * 1024L * 1024L
        }

    private companion object {
        const val RUNTIME_REVISION = "1"
        const val MANIFEST = "model.manifest.json"
        val ALLOWED_LICENSES = setOf("apache-2.0", "mit", "bsd-3-clause")

        // P-256 release verification key. The private half is never shipped.
        const val PUBLIC_KEY_DER_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIRdvxwpCvFNM87H6jhjUBNttN/geS5sN1Mh7V2dvwrCQvWE26xyGmc7Wdtdrkd+btA28HE08XI+w35KHu84VCg=="
    }
}

class EcdsaModelSignatureVerifier(publicKeyDerBase64: String) : ModelSignatureVerifier {
    private val publicKey = KeyFactory.getInstance("EC").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyDerBase64)),
    )

    override fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(manifestBytes)
            verify(signatureBytes)
        }
    }.getOrDefault(false)
}
