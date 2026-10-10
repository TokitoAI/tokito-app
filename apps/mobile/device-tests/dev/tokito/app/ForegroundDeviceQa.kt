package dev.tokito.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.tokito.physical.dialogue.DialogueLimits
import dev.tokito.physical.dialogue.TokitoDialogueRuntime
import java.util.concurrent.atomic.AtomicReference

/** Real visible target UI, not a process importance flag or instrumentation
 * presence. No wake lock, keepalive service or OEM setting override.
 */
internal object ForegroundDeviceQa {
    fun launch(): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launcher)
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            val activity = AtomicReference<Activity?>()
            instrumentation.runOnMainSync {
                activity.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it.packageName == context.packageName && it.hasWindowFocus() && !it.isFinishing })
            }
            activity.get()?.let { return it }
            SystemClock.sleep(25)
        }
        error("Device QA requires a real resumed, focused target Activity")
    }

    fun requireResumed(activity: Activity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(activity) &&
                activity.hasWindowFocus() && !activity.isFinishing) { "Device QA foreground owner was lost" }
        }
    }

    fun runtime(activity: Activity, limits: DialogueLimits): TokitoDialogueRuntime {
        requireResumed(activity)
        val runtime = AtomicReference<TokitoDialogueRuntime?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runtime.set(TokitoDialogueRuntime(activity, activity as LifecycleOwner, limits))
        }
        return checkNotNull(runtime.get())
    }
}
