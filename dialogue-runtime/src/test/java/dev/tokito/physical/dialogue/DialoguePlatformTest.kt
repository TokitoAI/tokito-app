package dev.tokito.physical.dialogue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialoguePlatformTest {
    @Test
    fun `arm64 runtime is admitted`() {
        assertTrue(supportsDialogueRuntime(arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun `unsupported emulator runtime is rejected before native loading`() {
        assertFalse(supportsDialogueRuntime(arrayOf("x86_64", "x86")))
    }
}
