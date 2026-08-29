package ai.tokito.physical.ar

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Vector3Contract(val x: Double, val y: Double, val z: Double) {
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}

@Serializable
data class Bounds3Contract(val minimum: Vector3Contract, val maximum: Vector3Contract) {
    fun isValid(): Boolean = minimum.isFinite() && maximum.isFinite() &&
        maximum.x > minimum.x && maximum.y > minimum.y && maximum.z > minimum.z
}

@Serializable
enum class AssetUnits { METRE }

@Serializable
enum class AssetAxes { RIGHT_HANDED_Y_UP }

@Serializable
data class DigitalTwinManifest(
    val schemaVersion: String,
    val assetId: String,
    val boardIdentity: String,
    val boardRevision: String,
    val assemblyContextRevision: String,
    val glbSha256: String,
    val glbBytes: Long,
    val units: AssetUnits,
    val axes: AssetAxes,
    val boardOriginMetres: Vector3Contract,
    val boundsMetres: Bounds3Contract,
    val defaultOpacity: Double = 0.42,
    val componentNodePrefix: String = "ref:",
) {
    /** Metric board-plane landmarks in the same clockwise order required by registration. */
    fun boardPlaneCorners(): List<Point2> = listOf(
        Point2(boundsMetres.minimum.x, boundsMetres.minimum.z),
        Point2(boundsMetres.maximum.x, boundsMetres.minimum.z),
        Point2(boundsMetres.maximum.x, boundsMetres.maximum.z),
        Point2(boundsMetres.minimum.x, boundsMetres.maximum.z),
    )
}

data class DigitalTwinPackage(val manifest: DigitalTwinManifest, val glb: ByteArray)

sealed interface DigitalTwinValidation {
    data class Accepted(val packageData: DigitalTwinPackage) : DigitalTwinValidation
    data class Rejected(val code: Code, val detail: String) : DigitalTwinValidation

    enum class Code {
        MANIFEST_TOO_LARGE,
        ASSET_TOO_LARGE,
        INVALID_MANIFEST,
        INCOMPATIBLE_SCHEMA,
        CONTEXT_MISMATCH,
        INVALID_GEOMETRY,
        INVALID_GLB,
        DIGEST_MISMATCH,
        IO_FAILURE,
    }
}

class DigitalTwinValidator(
    private val maximumGlbBytes: Long = 64L * 1024L * 1024L,
    private val maximumManifestBytes: Long = 64L * 1024L,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val schemaVersion = Regex("^1\\.[0-9]+$")
    private val digest = Regex("^(sha256:)?[a-fA-F0-9]{64}$")

    fun load(
        manifestFile: File,
        glbFile: File,
        expectedContextRevision: String,
    ): DigitalTwinValidation {
        return try {
            if (!manifestFile.isFile || !glbFile.isFile) {
                return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.IO_FAILURE, "asset package files are unavailable")
            }
            if (manifestFile.length() !in 1..maximumManifestBytes) {
                return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.MANIFEST_TOO_LARGE, "manifest exceeds bounded size")
            }
            if (glbFile.length() !in 20..maximumGlbBytes) {
                return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.ASSET_TOO_LARGE, "GLB exceeds bounded size")
            }
            val manifestBytes = readBounded(manifestFile, maximumManifestBytes)
                ?: return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.MANIFEST_TOO_LARGE, "manifest changed while being read or exceeds bounded size")
            val glb = readBounded(glbFile, maximumGlbBytes)
                ?: return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.ASSET_TOO_LARGE, "GLB changed while being read or exceeds bounded size")
            val manifest = runCatching {
                json.decodeFromString<DigitalTwinManifest>(manifestBytes.toString(Charsets.UTF_8))
            }.getOrElse {
                return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_MANIFEST, "manifest cannot be decoded")
            }
            validate(manifest, glb, expectedContextRevision)
        } catch (_: SecurityException) {
            DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.IO_FAILURE, "asset package access denied")
        } catch (_: java.io.IOException) {
            DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.IO_FAILURE, "asset package could not be read")
        }
    }

    fun validate(
        manifest: DigitalTwinManifest,
        glb: ByteArray,
        expectedContextRevision: String,
    ): DigitalTwinValidation {
        if (!schemaVersion.matches(manifest.schemaVersion)) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INCOMPATIBLE_SCHEMA, "unsupported asset schema major")
        }
        if (manifest.assetId.isBlank() || manifest.boardIdentity.isBlank() || manifest.boardRevision.isBlank() ||
            manifest.assemblyContextRevision.isBlank() || manifest.componentNodePrefix.isBlank() ||
            manifest.assetId.length > 256 || manifest.boardIdentity.length > 512 ||
            manifest.boardRevision.length > 128 || manifest.assemblyContextRevision.length > 256 ||
            manifest.componentNodePrefix.length > 32 || !digest.matches(manifest.glbSha256)
        ) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_MANIFEST, "manifest identity or digest shape is invalid")
        }
        if (manifest.assemblyContextRevision != expectedContextRevision) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.CONTEXT_MISMATCH, "asset and assembly context revisions differ")
        }
        if (glb.size.toLong() != manifest.glbBytes || glb.size.toLong() > maximumGlbBytes) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.ASSET_TOO_LARGE, "GLB size differs from manifest or budget")
        }
        if (!manifest.boardOriginMetres.isFinite() || listOf(
                manifest.boardOriginMetres.x, manifest.boardOriginMetres.y, manifest.boardOriginMetres.z,
            ).any { kotlin.math.abs(it) > 10.0 } || !manifest.boundsMetres.isValid() ||
            manifest.defaultOpacity !in 0.05..0.85 || listOf(
                manifest.boundsMetres.minimum.x, manifest.boundsMetres.minimum.y, manifest.boundsMetres.minimum.z,
                manifest.boundsMetres.maximum.x, manifest.boundsMetres.maximum.y, manifest.boundsMetres.maximum.z,
            ).any { kotlin.math.abs(it) > 10.0 }
        ) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_GEOMETRY, "asset geometry contract is invalid")
        }
        if (!isGlbV2(glb)) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_GLB, "asset is not a complete GLB 2 container")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(glb).toHex()
        val declared = manifest.glbSha256.removePrefix("sha256:").lowercase()
        if (declared.length != 64 || !MessageDigest.isEqual(actual.toByteArray(), declared.toByteArray())) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.DIGEST_MISMATCH, "GLB digest does not match manifest")
        }
        return DigitalTwinValidation.Accepted(DigitalTwinPackage(manifest, glb.copyOf()))
    }

    private fun isGlbV2(bytes: ByteArray): Boolean {
        if (bytes.size < 20) return false
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != 0x46546c67 || header.int != 2 || header.int != bytes.size) return false
        var offset = 12
        var sawJson = false
        while (offset < bytes.size) {
            if (offset + 8 > bytes.size) return false
            val chunk = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
            val length = chunk.int
            val type = chunk.int
            if (length <= 0 || length % 4 != 0 || offset + 8L + length > bytes.size) return false
            if (offset == 12 && type != 0x4e4f534a) return false
            if (type == 0x4e4f534a) {
                if (sawJson) return false
                sawJson = true
            }
            offset += 8 + length
        }
        return sawJson && offset == bytes.size
    }

    /** Reads through a hard byte ceiling so a concurrent file growth cannot allocate unbounded memory. */
    private fun readBounded(file: File, maximumBytes: Long): ByteArray? {
        val output = ByteArrayOutputStream(minOf(file.length(), maximumBytes, 8192L).toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximumBytes) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/** Only a fully validated package becomes visible to the render thread. */
class DigitalTwinRegistry {
    private val active = AtomicReference<DigitalTwinPackage?>(null)
    fun activate(validation: DigitalTwinValidation): Boolean = when (validation) {
        is DigitalTwinValidation.Accepted -> { active.set(validation.packageData); true }
        is DigitalTwinValidation.Rejected -> false
    }
    fun snapshot(): DigitalTwinPackage? = active.get()
    fun clear() = active.set(null)
}
