package dev.tokito.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Device bootstrap uses the same signed, bounded installer as the UI. */
class SignedDeviceProvisionTest {
    @Test fun installSignedLocalCapabilities() {
        ForegroundDeviceQa.launch()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val repository = ModelBundleRepository(context)
        for ((capability, name) in listOf(
            ModelCapability.DIALOGUE to "tokito-dialogue-signed.zip",
            ModelCapability.VOICE to "tokito-voice-signed.zip",
            ModelCapability.EMBEDDING to "tokito-knowledge.zip",
            ModelCapability.VISION to "tokito-paired-vision.zip",
        )) {
            File(root, name).inputStream().use { repository.install(capability, it) }
            assertNotNull(repository.activeBundle(capability))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("installed_capability", capability.wireValue)
            })
        }
    }
}
