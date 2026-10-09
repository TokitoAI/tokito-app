package dev.tokito.physical.symbols

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSymbolOperationTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellationBlocksNewWorkUntilCleanupFinishes() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val operation = BoardSymbolOperation(this) {}
        assertTrue(operation.start {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    finishCleanup.await()
                }
            }
        })
        runCurrent()
        operation.cancel()
        cleanupStarted.await()
        assertTrue(operation.busy)
        assertFalse(operation.start { error("Duplicate request executed") })
        finishCleanup.complete(Unit)
        runCurrent()
        assertFalse(operation.busy)
        assertTrue(operation.start {})
        runCurrent()
        assertFalse(operation.busy)
    }

    @Test fun immediateCompletionDoesNotLeaveACompletedJobBusy() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val operation = BoardSymbolOperation(scope) {}
            assertTrue(operation.start {})
            assertFalse(operation.busy)
            assertTrue(operation.start {})
            assertFalse(operation.busy)
        } finally { scope.cancel() }
    }

    @Test fun cancelledParentCannotLeaveTheActionBusy() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        val operation = BoardSymbolOperation(scope) {}
        assertTrue(operation.start { error("Cancelled scope executed") })
        assertFalse(operation.busy)
    }
}
