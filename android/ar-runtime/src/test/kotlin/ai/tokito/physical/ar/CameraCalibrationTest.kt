package ai.tokito.physical.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CameraCalibrationTest {
    @Test fun mapsLandscapeSensorIntoPortraitFillCenterPreview() {
        val mapped = assertNotNull(CameraCalibrationMapper.toPreview(
            3000.0, 3000.0, 2000.0, 1500.0,
            4000, 3000, 90, 1080, 1920,
        ))
        assertEquals(540.0, mapped.cx, 1e-6)
        assertEquals(960.0, mapped.cy, 1e-6)
        assertEquals(1080, mapped.widthPx)
        assertEquals(1920, mapped.heightPx)
    }

    @Test fun fillCenterAccountsForCropping() {
        val mapped = assertNotNull(CameraCalibrationMapper.toPreview(
            1000.0, 1000.0, 500.0, 250.0,
            1000, 500, 0, 1000, 1000,
        ))
        assertEquals(500.0, mapped.cx, 1e-6)
        assertEquals(500.0, mapped.cy, 1e-6)
        assertEquals(2000.0, mapped.fx, 1e-6)
    }

    @Test fun nonRightAngleRotationFailsClosed() {
        assertNull(CameraCalibrationMapper.toPreview(1.0, 1.0, 1.0, 1.0, 10, 10, 45, 10, 10))
    }
}
