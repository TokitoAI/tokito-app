package dev.tokito.app

import ai.tokito.physical.ar.ArCameraSurface
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.LocalPoseVisionRuntime
import dev.tokito.physical.dialogue.LocalSegmentationRuntime
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import dev.tokito.physical.model.VisionBundleProfiles
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PairedVisionDeviceTest {
    @Test fun installSignedPairAndBorrowOneRealImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ForegroundDeviceQa.launch()
        val context = instrumentation.targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val archive = File(external, "tokito-paired-vision.zip")
        assertTrue("Push the signed paired vision bundle before device acceptance", archive.isFile)
        val repository = ModelBundleRepository(context)
        archive.inputStream().use { repository.install(ModelCapability.VISION, it) }
        val profiles = VisionBundleProfiles.fromAdmittedBundle(requireNotNull(repository.activeDirectory(ModelCapability.VISION)))
        LocalPoseVisionRuntime.load(requireNotNull(profiles.pose)).use { pose ->
            LocalSegmentationRuntime.open(requireNotNull(profiles.segmentation)).use { segment ->
                val image = requireNotNull(BitmapFactory.decodeFile(File(external, "segmentation-probe.jpg").absolutePath))
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val observation = pose.analyze(ArCameraSurface.CameraFrame(image, System.nanoTime(),
                        image.width, image.height, null, 0L), recycleInput = false)
                    assertFalse("Pose must not destroy a borrowed camera frame", image.isRecycled)
                    val result = segment.analyze(image)
                    try {
                        assertFalse("Segmentation must not destroy a borrowed camera frame", image.isRecycled)
                        assertTrue("Real probe must produce component predictions", result.count > 0)
                        assertEquals(result.count, result.boxes.size)
                        instrumentation.sendStatus(2, android.os.Bundle().apply {
                            putInt("real_mask_count", result.count)
                            putBoolean("pose_accepted", observation != null)
                            putLong("paired_inference_ms", android.os.SystemClock.elapsedRealtime() - started)
                        })
                    } finally { result.bitmap.recycle() }
                } finally { image.recycle() }
            }
        }
    }
}
