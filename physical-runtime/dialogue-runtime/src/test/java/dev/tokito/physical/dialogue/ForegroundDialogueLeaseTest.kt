package dev.tokito.physical.dialogue

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundDialogueLeaseTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun onlyResumedOwnerIsAdmittedAndPauseIsTerminal() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(runCatching { ForegroundDialogueLease(owner.lifecycle) {} }.isFailure)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var revoked = 0
        val lease = ForegroundDialogueLease(owner.lifecycle) { revoked++ }
        lease.requireActive()
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertEquals(1, revoked)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(runCatching { lease.requireActive() }.isFailure)
        lease.close()
        lease.detach()
        assertEquals(1, revoked)
    }

    @Test fun backgroundRevokesHeldResponseWithoutJoiningNativeOrLateReady() = runBlocking {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val worker = ResponseWorker { disposed.countDown() }
        val lifecycle = DialogueLifecycle { worker.close() }
        val state = AtomicReference("ready")
        val lease = ForegroundDialogueLease(owner.lifecycle) { lifecycle.close { state.set("closed") } }
        val caller = launch(Dispatchers.Default) {
            lifecycle.responding {
                lease.requireActive()
                lifecycle.publish { state.set("generating") }
                worker.execute(60_000) { entered.countDown(); release.await(); "late" }
                lifecycle.publish { state.set("ready") }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            withTimeout(2_000) { caller.join() }
            assertTrue(caller.isCancelled)
            assertEquals("closed", state.get())
            assertEquals(1L, release.count)
            assertEquals(1L, disposed.count)
            assertTrue(worker.isQuarantined())
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertTrue(runCatching { lease.requireActive() }.isFailure)
        } finally {
            release.countDown()
            worker.close()
            lease.detach()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.Default) { withTimeout(5_000) {
                while (NativeDialogueAdmission.permit.availablePermits() == 0) delay(1)
            } }
        }
        assertEquals("closed", state.get())
    }
}
