package dev.tokito.physical.model

import android.content.Context
import android.os.Build
import dev.tokito.physical.LocalPoseVisionRuntime
import dev.tokito.physical.BuildConfig
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.dialogue.LocalTextEmbeddingRuntime
import dev.tokito.physical.voice.VoiceModelBundle
import org.json.JSONObject
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class ModelCapability(val wireValue: String) {
    DIALOGUE("dialogue"),
    VISION("vision"),
    EMBEDDING("embedding"),
    VOICE("voice");

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

data class ModelBundleAvailability(
    val state: String,
    val bundle: InstalledModelBundle? = null,
    val rejection: String? = null,
)

/** Owns the only admitted model directories used by the shipping runtime. */
class ModelBundleRepository(private val context: Context) {
    fun install(capability: ModelCapability, source: InputStream): InstalledModelBundle {
        val manifest = installer(capability).install(source)
        return manifest.toInstalledBundle(capability)
    }

    fun rollback(capability: ModelCapability): InstalledModelBundle {
        val installer = installer(capability)
        val manifest = installer.rollback()
        return manifest.toInstalledBundle(capability)
    }

    fun activeDirectory(capability: ModelCapability) = installer(capability).activeDirectory()

    fun activeBundle(capability: ModelCapability): InstalledModelBundle? = runCatching {
        val bundle = installer(capability).verifiedActiveBundle() ?: return null
        bundle.manifest.toInstalledBundle(capability)
    }.getOrNull()

    fun availability(capability: ModelCapability): ModelBundleAvailability =
        classifyModelAvailability(capability) { installer(capability).verifiedActiveBundle() }

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
            maxEntries = if (capability == ModelCapability.VOICE) 1024 else 256,
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
                ModelCapability.VOICE -> {
                    VoiceModelBundle.verify(bundle)
                    true
                }
                ModelCapability.EMBEDDING -> {
                    LocalTextEmbeddingRuntime.verifyConformance(bundle)
                    dev.tokito.physical.dialogue.BoardKnowledgePackage.openIfPresent(bundle)?.use { }
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
            ModelCapability.VOICE -> 512L * 1024L * 1024L
            ModelCapability.EMBEDDING -> 128L * 1024L * 1024L
        }

    private val ModelCapability.expandedLimit: Long
        get() = when (this) {
            ModelCapability.DIALOGUE -> 3L * 1024L * 1024L * 1024L
            ModelCapability.VISION -> 256L * 1024L * 1024L
            ModelCapability.VOICE -> 512L * 1024L * 1024L
            ModelCapability.EMBEDDING -> 128L * 1024L * 1024L
        }

    private companion object {
        const val RUNTIME_REVISION = "1"
        val ALLOWED_LICENSES = setOf("apache-2.0", "mit", "bsd-3-clause") +
            if (BuildConfig.ALLOW_EVALUATION_MODELS) setOf(
                "agpl-3.0-only", "licenseref-kroko-piper-espeak-local-evaluation",
                "licenseref-tokito-knowledge-private-evaluation",
            ) else emptySet()

        // P-256 release verification key. The private half is never shipped.
        const val PUBLIC_KEY_DER_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIRdvxwpCvFNM87H6jhjUBNttN/geS5sN1Mh7V2dvwrCQvWE26xyGmc7Wdtdrkd+btA28HE08XI+w35KHu84VCg=="
    }
}

/** Classifies a verified read without exposing paths, native errors or a trust bypass. */
internal fun classifyModelAvailability(
    capability: ModelCapability,
    readVerified: () -> ModelBundleInstaller.VerifiedBundle?,
): ModelBundleAvailability = try {
    val active = readVerified()
    if (active == null) ModelBundleAvailability("missing")
    else {
        check(active.manifest.getString("capability") == capability.wireValue)
        ModelBundleAvailability("ready", InstalledModelBundle(
            capability, active.manifest.getString("model_id"), active.manifest.getString("model_revision"),
        ))
    }
} catch (_: ModelLicenseRejectedException) {
    ModelBundleAvailability("rejected", rejection = "license")
} catch (_: Exception) {
    ModelBundleAvailability("rejected", rejection = "verification")
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
