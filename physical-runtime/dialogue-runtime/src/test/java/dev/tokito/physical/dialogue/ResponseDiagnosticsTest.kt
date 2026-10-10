package dev.tokito.physical.dialogue

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ResponseDiagnosticsTest {
    @Test fun independentWatchdogReportsFixedRolesWithoutRevokingWork() {
        val output = CopyOnWriteArrayList<String>()
        val deadline = CountDownLatch(1)
        val summary = CountDownLatch(1)
        val diagnostic = ResponseDiagnostics(20) {
            output += it
            if (it.contains("phase=watchdog_deadline ")) deadline.countDown()
            if (it.contains("phase=watchdog_threads ")) summary.countDown()
        }
        try {
            diagnostic.phase(ResponsePhase.TIMER_SETUP)
            diagnostic.phase(ResponsePhase.RECEIPT_SETUP)
            diagnostic.phase(ResponsePhase.WORKER_ENTER)
            // The caller is blocked, so its event loop cannot execute timers.
            assertTrue(deadline.await(5, TimeUnit.SECONDS))
            assertTrue(summary.await(5, TimeUnit.SECONDS))
            assertEquals(1, output.count { it.contains("phase=watchdog_deadline ") })
            assertTrue(output.any { it.contains("cancel=NOT_STARTED") })
            assertFalse(output.any { it.contains("revoke") || it.contains("cancel_job") })
            assertTrue(output.all { it.matches(Regex(
                "Response diagnostic phase=[a-z_]+( caller=[A-Z_]+ native=[A-Z_]+ cancel=[A-Z_]+)? elapsedMs=[0-9]+",
            )) })
        } finally { diagnostic.close() }
        assertTrue(output.last().contains("phase=receipt_end "))
    }

    @Test fun closeRemovesPendingDiagnosticAndIsIdempotent() {
        val output = CopyOnWriteArrayList<String>()
        val diagnostic = ResponseDiagnostics(60_000) { output += it }
        diagnostic.phase(ResponsePhase.RECEIPT_SETUP)
        diagnostic.close()
        diagnostic.close()
        assertEquals(1, output.count { it.contains("phase=receipt_end ") })
        assertFalse(output.any { it.contains("watchdog") })
    }

    @Test fun failingDiagnosticSinkCannotBecomeBackendFailure() {
        val diagnostic = ResponseDiagnostics(60_000) { error("Observer failure") }
        diagnostic.phase(ResponsePhase.TIMER_SETUP)
        diagnostic.phase(ResponsePhase.RECEIPT_SETUP)
        diagnostic.close()
    }
}
