package dev.tokito.physical.dialogue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DialogueLifecycleTest {
    @Test fun closeRevokesPublicationBeforeLateWorkAndDefersDisposal() = runTest {
        var state = "ready"
        var disposals = 0
        val lifecycle = DialogueLifecycle { disposals++ }
        val release = CompletableDeferred<Unit>()
        val response = async {
            lifecycle.responding {
                lifecycle.publish { state = "generating" }
                // Models an uninterruptible vendor call, without sleeping or invoking JNI.
                withContext(NonCancellable) { release.await() }
                lifecycle.publish { state = "ready" }
            }
        }
        runCurrent()
        lifecycle.close { state = "closed" }
        assertEquals("closed", state)
        assertEquals(0, disposals)
        assertFalse(lifecycle.publish { state = "failed" })
        lifecycle.close { fail("Close must be idempotent") }
        release.complete(Unit)
        runCurrent()
        assertTrue(response.isCancelled)
        assertEquals("closed", state)
        assertEquals(1, disposals)
    }

    @Test fun callerCancellationRemainsCancellationAndDoesNotDisposeOpenEngine() = runTest {
        var disposals = 0
        val lifecycle = DialogueLifecycle { disposals++ }
        var failure: Throwable? = null
        val response = async {
            try { lifecycle.responding { awaitCancellation() } }
            catch (caught: Throwable) { failure = caught; throw caught }
        }
        runCurrent()
        val cancellation = CancellationException("Caller cancelled")
        response.cancel(cancellation)
        runCurrent()
        // Coroutine stack-trace recovery may copy CancellationException.
        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
        assertEquals(0, disposals)
        lifecycle.close { }
        assertEquals(1, disposals)
    }


    @Test fun closedRuntimeCannotStartOrPublishAnotherResponse() = runTest {
        var disposals = 0
        val lifecycle = DialogueLifecycle { disposals++ }
        lifecycle.close { }
        var entered = false
        val failure = runCatching { lifecycle.responding { entered = true } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(entered)
        assertFalse(lifecycle.publish { fail("Closed publication") })
        assertEquals(1, disposals)
    }
}
