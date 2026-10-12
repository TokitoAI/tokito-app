package dev.tokito.app

import android.content.Intent
import android.view.MotionEvent
import android.view.View
import ai.tokito.physical.ar.ArCameraSurface
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.MainActivity
import dev.tokito.physical.session.PhysicalSessionContext
import dev.tokito.physical.session.PhysicalSessionStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CameraZoomDeviceTest {
    @Test fun realCameraButtonsPinchBoundsAndReset() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = PhysicalSessionStore.open(context).use { it.startSession(PhysicalSessionContext()) }
        val bench = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun view(name: String): View = bench.findViewById(context.resources.getIdentifier(name, "id", context.packageName))
        val surface = withContext(Dispatchers.Main) { view("cameraSurface") as ArCameraSurface }
        suspend fun ratio(): Float? = withContext(Dispatchers.Main) { surface.currentZoomRatio }
        suspend fun waitFor(condition: (Float) -> Boolean) = withTimeout(15_000) {
            while (ratio()?.let(condition) != true) delay(50)
        }
        try {
            waitFor { it.isFinite() }
            ForegroundDeviceQa.requireResumed(bench)
            val calibration = withContext(Dispatchers.Main) { surface.calibratedPreviewIntrinsics() }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putBoolean("metric_ar_camera_calibration_available", calibration != null)
                calibration?.let { putString("metric_ar_preview_intrinsics", "${it.fx},${it.fy},${it.cx},${it.cy},${it.widthPx},${it.heightPx}") }
            })
            val initial = requireNotNull(ratio())
            withContext(Dispatchers.Main) { assertTrue(view("zoomInButton").isEnabled); view("zoomInButton").performClick() }
            waitFor { it > initial }
            withContext(Dispatchers.Main) { view("zoomResetButton").performClick() }
            waitFor { kotlin.math.abs(it - initial) < 0.01f }
            val revision = surface.geometryRevision
            withContext(Dispatchers.Main) {
                val start = android.os.SystemClock.uptimeMillis()
                fun send(action: Int, time: Long, left: Float, right: Float, count: Int = 2) {
                    val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                    val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { x = if(i == 0) left else right; y = surface.height / 2f; pressure = 1f; size = 1f } }
                    val event = MotionEvent.obtain(start, time, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    surface.dispatchTouchEvent(event)
                    event.recycle()
                }
                val center = surface.width / 2f
                // Distances are device-scaled: a 120px span is below the
                // platform pinch threshold on the high-density iQOO panel.
                val span = surface.width / 8f
                send(MotionEvent.ACTION_DOWN, start, center - span, center + span, 1)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 20, center - span, center + span)
                for(i in 1..5) send(MotionEvent.ACTION_MOVE, start + 20 + i * 20L, center - span * (1 + i / 5f), center + span * (1 + i / 5f))
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 140, center - span * 2, center + span * 2)
                send(MotionEvent.ACTION_UP, start + 160, center - span * 2, center + span * 2, 1)
            }
            waitFor { it > initial }
            assertTrue(surface.geometryRevision > revision)
            withContext(Dispatchers.Main) { surface.setZoomRatio(Float.MAX_VALUE) }
            waitFor { it >= initial }
            delay(500)
            assertTrue(requireNotNull(ratio()).isFinite())
            withContext(Dispatchers.Main) { surface.setZoomRatio(-10f) }
            delay(500)
            assertTrue(requireNotNull(ratio()) > 0)
            withContext(Dispatchers.Main) { view("zoomResetButton").performClick() }
            waitFor { kotlin.math.abs(it - initial) < 0.01f }
        } finally { instrumentation.runOnMainSync { bench.finish() } }
    }
}
