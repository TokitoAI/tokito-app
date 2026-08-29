package ai.tokito.physical.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Deterministic metrology/conformance object. It is deliberately not a product
 * PCB or fallback asset; production sessions must supply their own verified package.
 */
object CalibrationBoardAsset {
    const val ASSET_ID = "tokito-metric-calibration-board-v1"
    const val BOARD_WIDTH_METRES = 0.120
    const val BOARD_HEIGHT_METRES = 0.080

    fun create(contextRevision: String = "tokito-calibration-context-v1"): DigitalTwinPackage {
        val glb = buildGlb()
        val digest = MessageDigest.getInstance("SHA-256").digest(glb).joinToString("") { "%02x".format(it) }
        return DigitalTwinPackage(
            DigitalTwinManifest(
                schemaVersion = "1.0",
                assetId = ASSET_ID,
                boardIdentity = "Tokito metric calibration board",
                boardRevision = "1",
                assemblyContextRevision = contextRevision,
                glbSha256 = "sha256:$digest",
                glbBytes = glb.size.toLong(),
                units = AssetUnits.METRE,
                axes = AssetAxes.RIGHT_HANDED_Y_UP,
                boardOriginMetres = Vector3Contract(0.0, 0.0, 0.0),
                boundsMetres = Bounds3Contract(
                    Vector3Contract(0.0, 0.0, 0.0),
                    Vector3Contract(BOARD_WIDTH_METRES, 0.012, BOARD_HEIGHT_METRES),
                ),
                defaultOpacity = 0.48,
            ),
            glb,
        )
    }

    private fun buildGlb(): ByteArray {
        val vertices = arrayOf(
            floatArrayOf(-0.5f, -0.5f, -0.5f), floatArrayOf(0.5f, -0.5f, -0.5f),
            floatArrayOf(0.5f, 0.5f, -0.5f), floatArrayOf(-0.5f, 0.5f, -0.5f),
            floatArrayOf(-0.5f, -0.5f, 0.5f), floatArrayOf(0.5f, -0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(-0.5f, 0.5f, 0.5f),
        )
        val indices = shortArrayOf(
            0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6,
            0, 4, 5, 0, 5, 1, 3, 2, 6, 3, 6, 7,
            1, 5, 6, 1, 6, 2, 0, 3, 7, 0, 7, 4,
        )
        val binary = ByteBuffer.allocate(vertices.size * 12 + indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        vertices.forEach { vertex -> vertex.forEach(binary::putFloat) }
        indices.forEach(binary::putShort)
        val json = """
            {"asset":{"version":"2.0","generator":"Tokito metric conformance asset v1"},
             "extensionsUsed":["KHR_materials_unlit"],
             "scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[
               {"name":"calibration-root","children":[1,2,3,4]},
               {"name":"board","mesh":0,"translation":[0.06,0.001,0.04],"scale":[0.12,0.002,0.08]},
               {"name":"ref:U1","mesh":1,"translation":[0.038,0.006,0.038],"scale":[0.026,0.010,0.026]},
               {"name":"ref:D1","mesh":2,"translation":[0.086,0.004,0.021],"scale":[0.018,0.006,0.008]},
               {"name":"orientation","mesh":2,"translation":[0.010,0.004,0.010],"scale":[0.012,0.006,0.012]}
             ],
             "meshes":[
               {"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0}]},
               {"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":1}]},
               {"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":2}]}
             ],
             "materials":[
               {"name":"board-ghost","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[0.04,0.82,0.63,0.42],"metallicFactor":0,"roughnessFactor":1},"alphaMode":"BLEND","doubleSided":true},
               {"name":"component-primary","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[0.45,0.35,1.0,0.72],"metallicFactor":0,"roughnessFactor":1},"alphaMode":"BLEND","doubleSided":true},
               {"name":"orientation-warning","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[1.0,0.58,0.16,0.82],"metallicFactor":0,"roughnessFactor":1},"alphaMode":"BLEND","doubleSided":true}
             ],
             "buffers":[{"byteLength":168}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":96,"target":34962},{"buffer":0,"byteOffset":96,"byteLength":72,"target":34963}],
             "accessors":[
               {"bufferView":0,"componentType":5126,"count":8,"type":"VEC3","min":[-0.5,-0.5,-0.5],"max":[0.5,0.5,0.5]},
               {"bufferView":1,"componentType":5123,"count":36,"type":"SCALAR"}
             ]}
        """.trimIndent().replace("\n", "")
        val jsonBytes = json.toByteArray(Charsets.UTF_8).padToFour(0x20)
        val binBytes = binary.array().padToFour(0x00)
        return ByteBuffer.allocate(12 + 8 + jsonBytes.size + 8 + binBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67); putInt(2); putInt(capacity())
            putInt(jsonBytes.size); putInt(0x4e4f534a); put(jsonBytes)
            putInt(binBytes.size); putInt(0x004e4942); put(binBytes)
        }.array()
    }

    private fun ByteArray.padToFour(padding: Int): ByteArray {
        val padded = (size + 3) and -4
        if (padded == size) return this
        return copyOf(padded).also { output -> for (i in size until padded) output[i] = padding.toByte() }
    }
}
