package dev.tokito.physical

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OptionalDialoguePreparationTest {
    @Test fun lifecycleCancellationCannotReportPreparationCompleteOrUnavailable() = runBlocking {
        var completed = false
        var unavailable = false
        try {
            prepareOptionalDialogue({ throw CancellationException("session stopped") }, { unavailable = true })
            completed = true
        } catch (_: CancellationException) { }
        assertFalse(completed)
        assertFalse(unavailable)
    }

    @Test fun missingOptionalModelRetainsExplicitUnavailablePath() = runBlocking {
        var unavailable: Throwable? = null
        val failure = IllegalStateException("model absent")
        prepareOptionalDialogue({ throw failure }, { unavailable = it })
        assertSame(failure, unavailable)
    }
}
