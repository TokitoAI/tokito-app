package ai.tokito.physical.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DigitalTwinAssetTest {
    @Test
    fun `board pose corners come from signed asset bounds`() {
        val manifest = CalibrationBoardAsset.create().manifest.copy(
            boundsMetres = Bounds3Contract(
                minimum = Vector3Contract(-0.031, -0.002, 0.014),
                maximum = Vector3Contract(0.089, 0.011, 0.094),
            ),
        )

        assertEquals(
            listOf(
                Point2(-0.031, 0.014),
                Point2(0.089, 0.014),
                Point2(0.089, 0.094),
                Point2(-0.031, 0.094),
            ),
            manifest.boardPlaneCorners(),
        )
    }

    @Test
    fun `board pose uses authored board plane inside render bounds`() {
        val packageData = CalibrationBoardAsset.create()
        val boardPlane = Bounds3Contract(
            minimum = Vector3Contract(0.01, 0.0, 0.02),
            maximum = Vector3Contract(0.11, 0.01, 0.07),
        )
        val manifest = packageData.manifest.copy(
            boundsMetres = Bounds3Contract(
                minimum = Vector3Contract(0.0, 0.0, 0.0),
                maximum = Vector3Contract(0.12, 0.02, 0.08),
            ),
            boardPlaneBoundsMetres = boardPlane,
        )

        assertEquals(Point2(0.01, 0.02), manifest.boardPlaneCorners().first())
        assertIs<DigitalTwinValidation.Accepted>(
            validator.validate(manifest, packageData.glb, manifest.assemblyContextRevision),
        )
        val outside = manifest.copy(
            boardPlaneBoundsMetres = boardPlane.copy(maximum = Vector3Contract(0.13, 0.01, 0.07)),
        )
        assertEquals(
            DigitalTwinValidation.Code.INVALID_GEOMETRY,
            assertIs<DigitalTwinValidation.Rejected>(
                validator.validate(outside, packageData.glb, outside.assemblyContextRevision),
            ).code,
        )
    }

    @Test
    fun `explicit corner landmarks outrank bounds-derived corners`() {
        // the detector's corner semantics belong to the vision model, so a
        // manifest can state authored landmarks (e.g. mounting-hole centres)
        // in exactly the model's keypoint order
        val packageData = CalibrationBoardAsset.create()
        val bounds = packageData.manifest.boundsMetres
        val holes = listOf(
            Vector3Contract(bounds.minimum.x + 0.004, 0.0, bounds.minimum.z + 0.004),
            Vector3Contract(bounds.maximum.x - 0.004, 0.0, bounds.minimum.z + 0.004),
            Vector3Contract(bounds.maximum.x - 0.004, 0.0, bounds.maximum.z - 0.004),
            Vector3Contract(bounds.minimum.x + 0.004, 0.0, bounds.maximum.z - 0.004),
        )
        val manifest = packageData.manifest.copy(boardPlaneCornerPointsMetres = holes)

        assertEquals(holes.map { Point2(it.x, it.z) }, manifest.boardPlaneCorners())
        assertIs<DigitalTwinValidation.Accepted>(
            validator.validate(manifest, packageData.glb, manifest.assemblyContextRevision),
        )
    }

    @Test
    fun `malformed corner landmarks are rejected not silently ignored`() {
        val packageData = CalibrationBoardAsset.create()
        val bounds = packageData.manifest.boundsMetres
        val inBounds = Vector3Contract(bounds.minimum.x + 0.01, 0.0, bounds.minimum.z + 0.01)
        val outOfBounds = Vector3Contract(bounds.maximum.x + 0.05, 0.0, bounds.minimum.z)

        for (corners in listOf(
            listOf(inBounds, inBounds, inBounds), // wrong count
            listOf(inBounds, inBounds, inBounds, outOfBounds), // outside bounds
        )) {
            val manifest = packageData.manifest.copy(boardPlaneCornerPointsMetres = corners)
            assertEquals(
                DigitalTwinValidation.Code.INVALID_GEOMETRY,
                assertIs<DigitalTwinValidation.Rejected>(
                    validator.validate(manifest, packageData.glb, manifest.assemblyContextRevision),
                ).code,
            )
        }
    }

    @Test
    fun `manifest without corner landmarks keeps the bounds-derived quad`() {
        val manifest = CalibrationBoardAsset.create().manifest
        val board = manifest.boardPlaneBoundsMetres ?: manifest.boundsMetres
        assertEquals(Point2(board.minimum.x, board.minimum.z), manifest.boardPlaneCorners().first())
    }

    private val validator = DigitalTwinValidator(maximumGlbBytes = 2L * 1024L * 1024L)

    @Test fun calibrationAssetIsARealValidatedGlb() {
        val packageData = CalibrationBoardAsset.create()
        val result = validator.validate(packageData.manifest, packageData.glb, "tokito-calibration-context-v1")
        assertIs<DigitalTwinValidation.Accepted>(result)
        assertEquals(0x67, packageData.glb[0].toInt() and 0xff)
    }

    @Test fun digestMismatchFailsClosed() {
        val packageData = CalibrationBoardAsset.create()
        val changed = packageData.glb.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val result = validator.validate(packageData.manifest, changed, "tokito-calibration-context-v1")
        assertEquals(DigitalTwinValidation.Code.DIGEST_MISMATCH, assertIs<DigitalTwinValidation.Rejected>(result).code)
    }

    @Test fun contextMismatchFailsBeforeRendering() {
        val packageData = CalibrationBoardAsset.create()
        val result = validator.validate(packageData.manifest, packageData.glb, "different-context")
        assertEquals(DigitalTwinValidation.Code.CONTEXT_MISMATCH, assertIs<DigitalTwinValidation.Rejected>(result).code)
    }

    @Test fun malformedContainerIsRejectedEvenWithMatchingDigestShape() {
        val packageData = CalibrationBoardAsset.create()
        val broken = packageData.glb.copyOf().also { it[4] = 1 }
        val manifest = packageData.manifest.copy(
            glbSha256 = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(broken).joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            DigitalTwinValidation.Code.INVALID_GLB,
            assertIs<DigitalTwinValidation.Rejected>(validator.validate(manifest, broken, manifest.assemblyContextRevision)).code,
        )
    }

    @Test fun malformedSchemaAndOversizedIdentityAreRejected() {
        val packageData = CalibrationBoardAsset.create()
        assertEquals(
            DigitalTwinValidation.Code.INCOMPATIBLE_SCHEMA,
            assertIs<DigitalTwinValidation.Rejected>(validator.validate(
                packageData.manifest.copy(schemaVersion = "1"),
                packageData.glb,
                packageData.manifest.assemblyContextRevision,
            )).code,
        )
        assertEquals(
            DigitalTwinValidation.Code.INVALID_MANIFEST,
            assertIs<DigitalTwinValidation.Rejected>(validator.validate(
                packageData.manifest.copy(assetId = "x".repeat(257)),
                packageData.glb,
                packageData.manifest.assemblyContextRevision,
            )).code,
        )
    }

    @Test fun registryNeverReplacesGoodAssetWithRejectedInput() {
        val packageData = CalibrationBoardAsset.create()
        val registry = DigitalTwinRegistry()
        assertTrue(registry.activate(validator.validate(packageData.manifest, packageData.glb, packageData.manifest.assemblyContextRevision)))
        assertFalse(registry.activate(DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_GLB, "bad")))
        assertNotNull(registry.snapshot())
    }
}
