package dev.tokito.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.dialogue.LocalSegmentationRuntime
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SegmentationDeviceTest {
    @Test fun overlaySurvivesUpdateGapAndTransientMissButExpires() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = ForegroundDeviceQa.launch()
        lateinit var view: dev.tokito.physical.SegmentMaskView
        lateinit var root: android.view.ViewGroup
        val mask = Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).apply { eraseColor(0x6e19d3b1) }
        val start = System.nanoTime()
        instrumentation.runOnMainSync {
            root = activity.findViewById(android.R.id.content)
            view = dev.tokito.physical.SegmentMaskView(activity)
            root.addView(view,android.view.ViewGroup.LayoutParams(640,640))
            view.layout(0,0,640,640)
            view.show(LocalSegmentationRuntime.Result(mask,android.graphics.Matrix(),listOf("raw_class"),1,
                listOf(LocalSegmentationRuntime.Box("raw_class",.9f,20f,20f,140f,140f))),
                android.graphics.Matrix(),start)
        }
        try {
            android.os.SystemClock.sleep(1900)
            instrumentation.runOnMainSync {
                assertFalse("The old 1.8s timer must not flash the result off",mask.isRecycled)
                val empty = Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888)
                view.show(LocalSegmentationRuntime.Result(empty,android.graphics.Matrix(),emptyList(),0,emptyList()),
                    android.graphics.Matrix(),System.nanoTime())
                assertTrue(empty.isRecycled)
                assertFalse("A single miss must preserve the previous bounded result",mask.isRecycled)
                val canvasBitmap = Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(canvasBitmap))
                assertTrue("Mask remains rendered with boxes and labels",canvasBitmap.getPixel(10,10) ushr 24 != 0)
                canvasBitmap.recycle()
            }
            android.os.SystemClock.sleep(1400)
            instrumentation.runOnMainSync { assertTrue("A miss must not extend stale prediction lifetime",mask.isRecycled) }
        } finally {
            instrumentation.runOnMainSync { view.clear(); root.removeView(view) }
        }
    }
    @Test fun installSignedBestBundleAndRunRealMasks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ForegroundDeviceQa.launch()
        val context = instrumentation.targetContext
        val archive = File(requireNotNull(context.getExternalFilesDir(null)), "tokito-segmentation.zip")
        assertTrue("Push signed segmentation ZIP to app external files before QA", archive.isFile)
        val repository = ModelBundleRepository(context)
        val installed = archive.inputStream().use { repository.install(ModelCapability.VISION,it) }
        assertEquals("tokito-component-yolo11m-seg",installed.modelId)
        LocalSegmentationRuntime.open(requireNotNull(repository.activeDirectory(ModelCapability.VISION))).use { runtime ->
            assertEquals(21,runtime.labels.size)
            val image = Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
            try {
                repeat(2) {
                    val output = runtime.analyze(image)
                    assertEquals(160,output.bitmap.width)
                    assertEquals(160,output.bitmap.height)
                    output.bitmap.recycle()
                }
            } finally { image.recycle() }
            val photograph = File(context.getExternalFilesDir(null), "segmentation-probe.jpg")
            assertTrue("Push a real reviewed board photograph", photograph.isFile)
            val realImage = requireNotNull(BitmapFactory.decodeFile(photograph.absolutePath))
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val output = runtime.analyze(realImage)
                instrumentation.sendStatus(2,android.os.Bundle().apply {
                    putInt("real_mask_count",output.count)
                    putLong("inference_ms",android.os.SystemClock.elapsedRealtime()-started)
                    putString("predicted_classes",output.labels.joinToString(","))
                })
                assertTrue("The reviewed probe produced no actual masks",output.count > 0)
                assertEquals(output.count,output.boxes.size)
                assertTrue(output.boxes.all { it.label in runtime.labels && it.score in 0f..1f })
                val pixels = IntArray(output.bitmap.width * output.bitmap.height)
                output.bitmap.getPixels(pixels,0,output.bitmap.width,0,0,output.bitmap.width,output.bitmap.height)
                assertTrue("Masks must remain present alongside labels and boxes",pixels.any { it ushr 24 != 0 })
                output.bitmap.recycle()
            } finally { realImage.recycle() }
        }
    }
}
