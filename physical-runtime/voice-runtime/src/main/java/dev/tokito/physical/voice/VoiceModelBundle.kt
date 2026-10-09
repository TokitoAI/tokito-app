package dev.tokito.physical.voice

import dev.tokito.physical.voice.internal.ModelInventory
import dev.tokito.physical.voice.internal.SherpaBackendFactory
import dev.tokito.physical.voice.internal.SherpaBundleConfig
import java.io.File

/** Validates speech files before the signed model installer activates a slot. */
object VoiceModelBundle {
    fun verify(admittedDirectory: File) {
        val root = File(admittedDirectory, "bundle")
        val verification = ModelInventory(root, SherpaBackendFactory.SHERPA_VERSION).verify()
        require(verification.valid) { "Speech model verification failed: ${verification.reason}" }
        SherpaBundleConfig.load(root)
    }
}
