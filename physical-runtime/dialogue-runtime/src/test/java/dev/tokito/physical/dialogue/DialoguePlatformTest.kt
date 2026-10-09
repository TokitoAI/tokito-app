package dev.tokito.physical.dialogue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DialoguePlatformTest {
    @Test fun `qairt cannot silently enter unconstrained tool generation`() {
        val failure = runCatching { requireDialogueMode(DialogueRuntime.QAIRT, true) }.exceptionOrNull()
        assertTrue(failure is DialogueFailure)
        assertEquals(DialogueFailureCode.POLICY_REJECTED, (failure as DialogueFailure).code)
        requireDialogueMode(DialogueRuntime.QAIRT, false)
        requireDialogueMode(DialogueRuntime.LLAMA_CPP, true)
    }

    @Test
    fun `arm64 runtime is admitted`() {
        assertTrue(supportsDialogueRuntime(arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun `unsupported emulator runtime is rejected before native loading`() {
        assertFalse(supportsDialogueRuntime(arrayOf("x86_64", "x86")))
    }
}
