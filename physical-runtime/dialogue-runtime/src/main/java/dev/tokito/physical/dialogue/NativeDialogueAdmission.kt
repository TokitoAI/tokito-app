package dev.tokito.physical.dialogue

import java.util.concurrent.Semaphore
import java.util.concurrent.Executors

/** Creation, generation and disposal must not overlap a quarantined vendor call,
 * including calls belonging to a replacement runtime in this process.
 */
internal object NativeDialogueAdmission {
    val permit = Semaphore(1, true)
    private val disposal = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokitoNativeDispose").apply { isDaemon = true }
    }

    fun disposeWhenIdle(block: () -> Unit) {
        // Replacement runtimes do not each create a thread waiting on stuck JNI.
        disposal.execute {
            permit.acquire()
            try { block() } finally { permit.release() }
        }
    }
}
