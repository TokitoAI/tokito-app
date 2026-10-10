package dev.tokito.physical.dialogue

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResponseWorkerTest {
    private suspend fun awaitReleased() = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (NativeDialogueAdmission.permit.availablePermits() == 0) delay(1)
        }
    }
    @Test fun ownedDeadlineReturnsWhileGenerationIsHeldAndBlocksReplacementNativeWork() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val disposalCount = AtomicInteger()
        val worker = ResponseWorker { disposalCount.incrementAndGet(); disposed.countDown() }
        val replacementDisposed = CountDownLatch(1)
        val replacement = ResponseWorker { replacementDisposed.countDown() }
        val loader = InitializationWorker<AutoCloseable>()
        var delivered = false
        try {
            val caller = async {
                runCatching {
                    worker.execute(100) { started.countDown(); release.await(); "late response" }
                        .also { delivered = true }
                }
            }
            runCurrent()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            advanceTimeBy(101)
            runCurrent()
            val failure = caller.await().exceptionOrNull()
            assertEquals(DialogueFailureCode.GENERATION_TIMEOUT, (failure as DialogueFailure).code)
            assertTrue(worker.isQuarantined())
            assertEquals(1L, release.count)
            assertEquals(0, disposalCount.get())
            assertTrue(runCatching { worker.execute(100) { "reuse" } }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { replacement.execute(100) { "replacement" } }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { loader.load(100) { AutoCloseable { } } }.exceptionOrNull() is IllegalStateException)
            // Empty replacements must not queue closures retaining whole runtimes
            // indefinitely behind the held native lease.
            val emptyRetirements = AtomicInteger()
            repeat(1_000) {
                val emptyLoader = InitializationWorker<AutoCloseable>()
                val emptyWorker = ResponseWorker(retireEmpty = emptyLoader::closeIfEmpty) {
                    emptyLoader.close()
                    emptyRetirements.incrementAndGet()
                }
                emptyWorker.close()
                emptyWorker.close()
            }
            assertEquals(1_000, emptyRetirements.get())
            assertEquals(1L, release.count)
            worker.close()
            assertEquals(0, disposalCount.get())
            release.countDown()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            assertFalse(delivered)
            assertEquals(1, disposalCount.get())
        } finally {
            release.countDown()
            worker.close()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            replacement.close()
            loader.close()
            assertTrue(replacementDisposed.await(5, TimeUnit.SECONDS))
            awaitReleased()
        }
    }

    @Test fun deadlineReturnsWhileNonCancellableCleanupIsHeldWithoutConcurrentDisposal() = runTest {
        val entered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = ResponseWorker { calls.incrementAndGet(); disposed.countDown() }
        try {
            val caller = async {
                runCatching {
                    worker.execute(100) {
                        entered.countDown()
                        try { awaitCancellation() }
                        finally {
                            withContext(NonCancellable) {
                                cleanupEntered.countDown()
                                releaseCleanup.await()
                            }
                        }
                    }
                }
            }
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(101)
            runCurrent()
            assertEquals(DialogueFailureCode.GENERATION_TIMEOUT,
                (caller.await().exceptionOrNull() as DialogueFailure).code)
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1L, releaseCleanup.count)
            worker.close()
            worker.close()
            assertEquals(0, calls.get())
            assertTrue(runCatching { worker.execute(100) { "reuse" } }.exceptionOrNull() is IllegalStateException)
            assertFalse(NativeDialogueAdmission.permit.tryAcquire())
        } finally {
            releaseCleanup.countDown()
            worker.close()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            awaitReleased()
        }
    }

    @Test fun enclosingDeadlineRemainsCancellationWhileBlockedWorkerKeepsItsLease() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val worker = ResponseWorker { disposed.countDown() }
        try {
            val caller = async {
                runCatching { withTimeout(100) {
                    worker.execute(10_000) { started.countDown(); release.await(); "late" }
                } }
            }
            runCurrent()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            advanceTimeBy(101)
            runCurrent()
            val failure = caller.await().exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertFalse(failure is DialogueFailure)
            assertTrue(worker.isQuarantined())
            assertEquals(1L, disposed.count)
        } finally {
            release.countDown()
            worker.close()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            awaitReleased()
        }
    }

    @Test fun explicitCancellationAndCloseReturnWithoutNativeJoinOrLateReady() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val worker = ResponseWorker { disposed.countDown() }
        val lifecycle = DialogueLifecycle { worker.close() }
        val state = AtomicReference("ready")
        val failure = AtomicReference<Throwable>()
        val caller = launch(Dispatchers.Default) {
            try {
                lifecycle.responding {
                    lifecycle.publish { state.set("generating") }
                    worker.execute(10_000) { started.countDown(); release.await(); "late" }
                    lifecycle.publish { state.set("ready") }
                }
            } catch (caught: Throwable) { failure.set(caught); throw caught }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            lifecycle.close { state.set("closed") }
            withTimeout(2_000) { caller.join() }
            assertTrue(caller.isCancelled)
            assertTrue(failure.get() is CancellationException)
            assertEquals("closed", state.get())
            assertEquals(1L, disposed.count)
            release.countDown()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            assertEquals("closed", state.get())
        } finally {
            release.countDown()
            worker.close()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            awaitReleased()
        }
    }

    @Test fun successfulWorkCanRepeatAndExceptionsDoNotBecomeTimeouts() = runBlocking {
        val disposed = CountDownLatch(1)
        val count = AtomicInteger()
        val worker = ResponseWorker { count.incrementAndGet(); disposed.countDown() }
        try {
            repeat(20) { assertEquals(it, worker.execute(2_000) { it }) }
            val expected = IllegalArgumentException("Backend rejected input")
            val failure = runCatching { worker.execute<String>(2_000) { throw expected } }.exceptionOrNull()
            assertEquals(expected.message, failure?.message)
            assertFalse(worker.isQuarantined())
            assertEquals("next", worker.execute(2_000) { "next" })
            assertEquals(0, count.get())
        } finally {
            worker.close()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            assertEquals(1, count.get())
            awaitReleased()
        }
    }

    @Test fun revocationDiagnosticsBracketCancellationWithoutChangingDeadline() = runTest {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val phases = java.util.concurrent.CopyOnWriteArrayList<ResponsePhase>()
        val worker = ResponseWorker { disposed.countDown() }
        try {
            val caller = async { runCatching {
                worker.execute(100, { phases += it }) { entered.countDown(); released.await(); "late" }
            } }
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(101)
            runCurrent()
            assertEquals(DialogueFailureCode.GENERATION_TIMEOUT,
                (caller.await().exceptionOrNull() as DialogueFailure).code)
            assertEquals(ResponsePhase.TIMER_SETUP, phases.first())
            assertTrue(phases.indexOf(ResponsePhase.REVOKE_ENTER) < phases.indexOf(ResponsePhase.REVOKE_EXIT))
            assertEquals(1L, released.count)
            assertTrue(worker.isQuarantined())
        } finally {
            released.countDown()
            worker.close()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            awaitReleased()
        }
    }
}
