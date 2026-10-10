package dev.tokito.physical.dialogue

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InitializationWorkerTest {
    @Test fun cancellingLoadReceiptDoesNotDestroyAnAlreadyAdmittedEngine() = runBlocking {
        val worker = InitializationWorker<Resource>()
        val result = Resource()
        try {
            worker.load(2_000) { result }
            awaitFinished(worker)
            worker.cancelPending()
            assertSame(result, worker.current())
            assertEquals(0, result.closes.get())
        } finally {
            worker.close()
            assertTrue(result.disposed.await(2, TimeUnit.SECONDS))
            awaitNativeAdmissionRelease()
        }
    }
    private suspend fun awaitFinished(worker: InitializationWorker<Resource>) {
        withTimeout(2_000) { while (worker.isFinishing()) delay(1) }
    }
    private fun awaitNativeAdmissionRelease() {
        // Resource's close notification precedes the worker's finally/release.
        // Waiting for that notification is NOT proof another JNI load is safe.
        val acquired = NativeDialogueAdmission.permit.tryAcquire(2, TimeUnit.SECONDS)
        assertTrue("Native disposal did not release admission", acquired)
        if (acquired) NativeDialogueAdmission.permit.release()
    }
    private class Resource(private val closeGate: CountDownLatch? = null) : AutoCloseable {
        val closes = AtomicInteger()
        val disposed = CountDownLatch(1)
        override fun close() { closes.incrementAndGet(); disposed.countDown(); closeGate?.await() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun timeoutReturnsWhileNativeLoadIsBlockedAndLateResultIsDisposed() = runTest {
        val worker = InitializationWorker<Resource>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = Resource()
        try {
            val loading = async {
                try { worker.load(100) { started.countDown(); release.await(); result }; false }
                catch (_: TimeoutCancellationException) { true }
            }
            runCurrent()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            advanceTimeBy(101)
            runCurrent()
            assertTrue(withTimeout(2_000) { loading.await() })
            assertNull(worker.current())
            assertEquals(1L, release.count)
            try { worker.load(100) { Resource() }; fail("Concurrent JNI load admitted") }
            catch (_: IllegalStateException) { }
            val replacement = InitializationWorker<Resource>()
            try {
                try { replacement.load(100) { Resource() }; fail("Runtime recreation bypassed blocked JNI admission") }
                catch (_: IllegalStateException) { }
            } finally { replacement.close() }
            release.countDown()
            assertTrue(result.disposed.await(2, TimeUnit.SECONDS))
            withContext(Dispatchers.Default) { awaitFinished(worker) }
            assertEquals(1, result.closes.get())
            assertNull(worker.current())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun externalCancellationIsPreservedAndDoesNotInstallLateEngine() = runBlocking {
        val worker = InitializationWorker<Resource>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = Resource()
        val loading = launch(Dispatchers.Default) { worker.load(10_000) { started.countDown(); release.await(); result } }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            loading.cancelAndJoin()
            assertTrue(loading.isCancelled)
            assertNull(worker.current())
            release.countDown()
            assertTrue(result.disposed.await(2, TimeUnit.SECONDS))
            awaitFinished(worker)
            assertEquals(1, result.closes.get())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun closeRevokesPendingLoadPromptlyAndDisposesLateResultOnce() = runBlocking {
        val worker = InitializationWorker<Resource>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = Resource()
        val loading = launch(Dispatchers.Default) { worker.load(10_000) { started.countDown(); release.await(); result } }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.close()
            withTimeout(2_000) { loading.join() }
            assertTrue(loading.isCancelled)
            assertNull(worker.current())
            release.countDown()
            assertTrue(result.disposed.await(2, TimeUnit.SECONDS))
            awaitFinished(worker)
            worker.close()
            assertEquals(1, result.closes.get())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun successfulLoadIsOwnedUntilCloseAndBackendFailuresStayFailures() = runBlocking {
        val worker = InitializationWorker<Resource>()
        val result = Resource()
        val replacement = Resource()
        try {
            worker.load(2_000) { result }
            awaitFinished(worker)
            assertSame(result, worker.current())
            worker.discardCurrent()
            assertNull(worker.current())
            assertTrue(result.disposed.await(2, TimeUnit.SECONDS))
            awaitNativeAdmissionRelease()
            worker.load(2_000) { replacement }
            awaitFinished(worker)
            assertEquals(1, result.closes.get())
            assertSame(replacement, worker.current())
        } finally {
            val owned = worker.current()
            worker.close()
            if (owned != null) assertTrue(owned.disposed.await(2, TimeUnit.SECONDS))
            awaitNativeAdmissionRelease()
        }
        assertTrue(replacement.disposed.await(2, TimeUnit.SECONDS))
        assertEquals(1, replacement.closes.get())
        val failureWorker = InitializationWorker<Resource>()
        try {
            val expected = IllegalArgumentException("failure")
            try { failureWorker.load(2_000) { throw expected }; fail("Failure was hidden") }
            catch (failure: IllegalArgumentException) { assertEquals(expected.message, failure.message) }
            awaitFinished(failureWorker)
            assertNull(failureWorker.current())
        } finally { failureWorker.close() }
    }

    @Test fun disposalNotificationDoesNotReleaseNativeOwnershipBeforeCloseUnwinds() = runBlocking {
        val closeGate = CountDownLatch(1)
        val result = Resource(closeGate)
        val worker = InitializationWorker<Resource>()
        val replacement = Resource()
        try {
            worker.load(2_000) { result }
            awaitFinished(worker)
            worker.discardCurrent()
            assertTrue(result.disposed.await(2, TimeUnit.SECONDS))
            try { worker.load(2_000) { replacement }; fail("Disposal still owns native admission") }
            catch (_: IllegalStateException) { }
            assertEquals(0, replacement.closes.get())
            closeGate.countDown()
            awaitNativeAdmissionRelease()
            worker.load(2_000) { replacement }
            awaitFinished(worker)
            assertSame(replacement, worker.current())
        } finally {
            closeGate.countDown()
            val owned = worker.current()
            worker.close()
            if (owned != null) assertTrue(owned.disposed.await(2, TimeUnit.SECONDS))
            awaitNativeAdmissionRelease()
        }
        assertEquals(1, result.closes.get())
        assertEquals(1, replacement.closes.get())
    }
}
