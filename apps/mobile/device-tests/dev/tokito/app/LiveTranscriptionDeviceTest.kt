package dev.tokito.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.MainActivity
import dev.tokito.physical.session.PhysicalSessionContext
import dev.tokito.physical.session.PhysicalSessionStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real native call button and microphone events, not injected transcripts. */
class LiveTranscriptionDeviceTest {
    @Test fun microphoneUpdatesCaptionAndPauseClearsIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = PhysicalSessionStore.open(context).use { it.startSession(PhysicalSessionContext()) }
        val bench = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun view(name: String): View = bench.findViewById(context.resources.getIdentifier(name, "id", context.packageName))
        try {
            withTimeout(150_000) {
                while (withContext(Dispatchers.Main) { (view("callStateText") as TextView).text.toString() } != "Ready") delay(200)
            }
            withContext(Dispatchers.Main) { view("callButton").performClick() }
            withTimeout(30_000) {
                while (withContext(Dispatchers.Main) { (view("callStateText") as TextView).text.toString() } != "Listening") delay(100)
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("caption_qa", "microphone_listening") })
            withTimeout(60_000) {
                while (withContext(Dispatchers.Main) { (view("transcriptLabel") as TextView).text.toString() } != "You said") delay(50)
            }
            withContext(Dispatchers.Main) {
                val text = (view("transcriptText") as TextView).text.toString()
                assertTrue(text.isNotBlank() && text.length <= 512)
                assertEquals(View.VISIBLE, view("transcriptPanel").visibility)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, view("transcriptText").accessibilityLiveRegion)
                val controls = IntArray(2).also { view("voiceControls").getLocationOnScreen(it) }
                val caption = IntArray(2).also { view("transcriptPanel").getLocationOnScreen(it) }
                assertTrue("Caption overlaps the voice controls", caption[0] >= controls[0] + view("voiceControls").width)
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("caption_qa", "final_caption_visible") })
        } finally {
            instrumentation.runOnMainSync { bench.finish() }
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { (view("transcriptText") as TextView).text.isNotEmpty() }) delay(50)
        }
    }
}
