package dev.tokito.app

import ai.tokito.physical.ar.ArCameraSurface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.ReferenceBoardTracker
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import dev.tokito.physical.model.ReferenceTwinCatalog
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReferenceTrackingDeviceTest {
    @Test fun matchesActualSignedReferenceImagesAndPerspectiveMovement() {
        ForegroundDeviceQa.launch()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ModelBundleRepository(context)
        File(requireNotNull(context.getExternalFilesDir(null)), "tokito-paired-vision.zip").inputStream().use {
            repository.install(ModelCapability.VISION,it)
        }
        val entries = ReferenceTwinCatalog.read(requireNotNull(repository.activeDirectory(ModelCapability.VISION)))
        val tracker = requireNotNull(ReferenceBoardTracker.load(entries))
        tracker.use {
            for (entry in entries.filter { it.tracking != null }) {
                val original = requireNotNull(BitmapFactory.decodeFile(File(entry.tracking!!.parentFile, "tracking.jpg").absolutePath))
                try {
                    val baseline = requireNotNull(tracker.analyze(ArCameraSurface.CameraFrame(original,System.nanoTime(),original.width,original.height,Matrix(),0L)))
                    for (angle in listOf(0f, 8f, -8f)) {
                        val image = Bitmap.createBitmap(original.width,original.height,Bitmap.Config.ARGB_8888)
                        try {
                            val movement = Matrix().apply {
                                postScale(0.78f,0.78f,original.width/2f,original.height/2f)
                                postRotate(angle,original.width/2f,original.height/2f)
                            }
                            Canvas(image).apply { drawColor(Color.DKGRAY); drawBitmap(original,movement,Paint(Paint.FILTER_BITMAP_FLAG)) }
                            val result = tracker.analyze(ArCameraSurface.CameraFrame(image,System.nanoTime(),image.width,image.height,Matrix(),0L))
                            assertNotNull("Actual signed reference must match under movement",result)
                            assertEquals(entry.manifest,result!!.entry.manifest)
                            assertTrue(result.inliers>=16)
                            assertTrue(result.reprojectionPixels<=2.5)
                            val expected = FloatArray(8)
                            baseline.pose.corners.forEachIndexed { i,p -> expected[i*2]=p.x.toFloat(); expected[i*2+1]=p.y.toFloat() }
                            movement.mapPoints(expected)
                            result.pose.corners.forEachIndexed { i,p ->
                                assertTrue("Registered corners must follow measured image movement",
                                    kotlin.math.hypot(p.x-expected[i*2],p.y-expected[i*2+1]) < 6.0)
                            }
                        } finally { image.recycle() }
                    }
                } finally { original.recycle() }
            }
            val blank = Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
            try {
                blank.eraseColor(Color.DKGRAY)
                assertNull(tracker.analyze(ArCameraSurface.CameraFrame(blank,System.nanoTime(),640,480,Matrix(),0L)))
            } finally { blank.recycle() }
        }
    }
}
