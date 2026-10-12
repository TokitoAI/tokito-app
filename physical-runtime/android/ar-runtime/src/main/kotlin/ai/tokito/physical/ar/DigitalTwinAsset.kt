package ai.tokito.physical.ar

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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
    val boardPlaneBoundsMetres: Bounds3Contract? = null,
    val defaultOpacity: Double = 0.42,
    val componentNodePrefix: String = "ref:",
    val boardPlaneCornerPointsMetres: List<Vector3Contract>? = null,
    val physicalGeometryVerified: Boolean = false,
    val arRegistrationVerified: Boolean = false,
) {
    val registrationEligible: Boolean get() = physicalGeometryVerified && arRegistrationVerified
    /**
     * Metric board-plane landmarks in the same clockwise order required by
     * registration.
     *
     * When the manifest supplies explicit corner points they take priority:
     * the detector's corner semantics belong to the vision model, and
     * authored physical landmarks (for example the four corner mounting
     * holes, whose centres come from the PCB source) can be stated in
     * exactly the model's keypoint order instead of being derived from an
     * axis-aligned bounds rectangle. Absent that field, the previous
     * bounds-derived quad is used unchanged.
     */
    fun boardPlaneCorners(): List<Point2> {
        boardPlaneCornerPointsMetres?.let { corners ->
            if (corners.size == 4) return corners.map { Point2(it.x, it.z) }
        }
        val board = boardPlaneBoundsMetres ?: boundsMetres
        return listOf(
            Point2(board.minimum.x, board.minimum.z),
            Point2(board.maximum.x, board.minimum.z),
            Point2(board.maximum.x, board.maximum.z),
            Point2(board.minimum.x, board.maximum.z),
        )
    }
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
        RESOURCE_BUDGET,
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
            manifest.boardPlaneBoundsMetres?.let { !it.isValid() || !manifest.boundsMetres.contains(it) } == true ||
            manifest.defaultOpacity !in 0.05..0.85 || listOf(
                manifest.boundsMetres.minimum.x, manifest.boundsMetres.minimum.y, manifest.boundsMetres.minimum.z,
                manifest.boundsMetres.maximum.x, manifest.boundsMetres.maximum.y, manifest.boundsMetres.maximum.z,
            ).any { kotlin.math.abs(it) > 10.0 }
        ) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_GEOMETRY, "asset geometry contract is invalid")
        }
        manifest.boardPlaneCornerPointsMetres?.let { corners ->
            // explicit registration landmarks must be exactly four finite
            // points inside the declared bounds; a malformed list would
            // otherwise feed the pose estimator silently wrong geometry
            val bounds = manifest.boundsMetres
            val valid = corners.size == 4 && corners.all { point ->
                point.isFinite() &&
                    point.x in bounds.minimum.x..bounds.maximum.x &&
                    point.y in bounds.minimum.y..bounds.maximum.y &&
                    point.z in bounds.minimum.z..bounds.maximum.z
            }
            if (!valid) {
                return DigitalTwinValidation.Rejected(
                    DigitalTwinValidation.Code.INVALID_GEOMETRY,
                    "board plane corner points are not four finite in-bounds landmarks",
                )
            }
        }
        if (!isGlbV2(glb)) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_GLB, "asset is not a complete GLB 2 container")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(glb).toHex()
        val declared = manifest.glbSha256.removePrefix("sha256:").lowercase()
        if (declared.length != 64 || !MessageDigest.isEqual(actual.toByteArray(), declared.toByteArray())) {
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.DIGEST_MISMATCH, "GLB digest does not match manifest")
        }
        validateSceneBudget(glb)?.let { return it }
        return DigitalTwinValidation.Accepted(DigitalTwinPackage(manifest, glb.copyOf()))
    }

    /** Byte limits alone do not bound native draw handles: STEP exports may have one primitive per face. */
    private fun validateSceneBudget(bytes: ByteArray): DigitalTwinValidation.Rejected? {
        val length = ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun budget(detail: String) = DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.RESOURCE_BUDGET, detail)
        if (length > 4 * 1024 * 1024) return budget("GLB scene JSON exceeds renderer budget; optimize the source asset")
        return try {
            val scene = json.parseToJsonElement(bytes.copyOfRange(20, 20 + length).toString(Charsets.UTF_8)) as? JsonObject
                ?: error("GLB scene is not an object")
            fun array(key: String): JsonArray = scene[key]?.let { it as? JsonArray ?: error("Invalid scene array") } ?: JsonArray(emptyList())
            if (array("nodes").size > 8192 || array("materials").size > 1024 || array("textures").size > 128) {
                return budget("GLB object count exceeds renderer budget; optimize the source asset")
            }
            val meshes = array("meshes")
            if (meshes.size > 8192) return budget("GLB mesh count exceeds renderer budget")
            var primitives = 0L
            meshes.forEach { mesh ->
                val entries = (mesh as? JsonObject)?.get("primitives") as? JsonArray ?: error("Invalid mesh primitives")
                primitives += entries.size
                if (primitives > 8192) return budget("GLB draw primitives exceed renderer budget; optimize without removing component references")
            }
            null
        } catch (_: RuntimeException) {
            DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_GLB, "GLB scene JSON is malformed")
        }
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

    private fun Bounds3Contract.contains(other: Bounds3Contract): Boolean =
        other.minimum.x >= minimum.x && other.minimum.y >= minimum.y && other.minimum.z >= minimum.z &&
            other.maximum.x <= maximum.x && other.maximum.y <= maximum.y && other.maximum.z <= maximum.z
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
