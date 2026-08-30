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
