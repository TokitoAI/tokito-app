package dev.tokito.physical

import ai.tokito.physical.ar.ArCameraSurface
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionDeviceTest {
    @Test
    fun closingDuringQueuedInferenceDoesNotUseAClosedInterpreter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundle = File(context.filesDir, "models/vision")

        repeat(3) {
            val runtime = LocalPoseVisionRuntime.load(bundle)
            val executor = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val futures = (0 until 8).map {
                val frame = cameraFrame()
                executor.submit<PoseVisionObservation?> {
                    started.countDown()
                    runtime.analyze(frame)
                }
            }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(5)
            val closer = Thread(runtime::close, "tokito-vision-close-test")
            closer.start()
            closer.join(30_000)
            assertTrue(!closer.isAlive)
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            val rejected = cameraFrame()
            assertNull(runtime.analyze(rejected))
            assertTrue(rejected.bitmap.isRecycled)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun cameraFrame(): ArCameraSurface.CameraFrame = ArCameraSurface.CameraFrame(
        bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888),
        timestampNanos = System.nanoTime(),
        previewWidth = 640,
        previewHeight = 480,
        analysisToPreview = null,
    )
}
