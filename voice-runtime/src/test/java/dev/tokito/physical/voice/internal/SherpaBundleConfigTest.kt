package dev.tokito.physical.voice.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SherpaBundleConfigTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun loadsOnlyDeclaredLocalModelPaths() {
        val root = createBundle()

        val config = SherpaBundleConfig.load(root)

        assertEquals(setOf("en"), config.languages)
        assertEquals("zipformer2", config.sttModelType)
        assertEquals(File(root, "stt/encoder.onnx").canonicalFile, config.encoder)
        assertEquals(File(root, "tts/model.onnx").canonicalFile, config.ttsModel)
    }

    @Test
    fun rejectsPathTraversal() {
        val root = createBundle(encoder = "../outside.onnx")

        assertThrows(IllegalArgumentException::class.java) {
            SherpaBundleConfig.load(root)
        }
    }

    @Test
    fun rejectsUnknownEngineInsteadOfGuessing() {
        val root = createBundle(sttEngine = "placeholder")

        assertThrows(IllegalArgumentException::class.java) {
            SherpaBundleConfig.load(root)
        }
    }

    private fun createBundle(
        encoder: String = "stt/encoder.onnx",
        sttEngine: String = "sherpa-onnx-online-transducer",
    ): File {
        val root = temporary.newFolder()
        listOf(
            "stt/encoder.onnx",
            "stt/decoder.onnx",
            "stt/joiner.onnx",
            "stt/tokens.txt",
            "tts/model.onnx",
            "tts/tokens.txt",
            "tts/espeak-ng-data/voices/en",
        ).forEach { relative ->
            File(root, relative).apply {
                requireNotNull(parentFile).mkdirs()
                writeText("fixture")
            }
        }
        File(root, "voice-runtime.json").writeText(
            """
            {
              "schema_version": 1,
              "languages": ["en"],
              "stt": {
                "engine": "$sttEngine",
                "model_type": "zipformer2",
                "encoder": "$encoder",
                "decoder": "stt/decoder.onnx",
                "joiner": "stt/joiner.onnx",
                "tokens": "stt/tokens.txt"
              },
              "tts": {
                "engine": "sherpa-onnx-vits",
                "model": "tts/model.onnx",
                "tokens": "tts/tokens.txt",
                "data_dir": "tts/espeak-ng-data",
                "speaker_id": 0,
                "speed": 1.0
              }
            }
            """.trimIndent(),
        )
        return root
    }
}
