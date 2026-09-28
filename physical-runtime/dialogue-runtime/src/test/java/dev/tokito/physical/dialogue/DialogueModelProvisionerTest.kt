package dev.tokito.physical.dialogue

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DialogueModelProvisionerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsCompletePinnedBundle() {
        val root = createBundle()

        val model = DialogueModelProvisioner.fromAdmittedBundle(root).installed()

        requireNotNull(model)
        assertEquals(DialogueRuntime.QAIRT, model.runtime)
        assertEquals(2, model.artifacts.size)
        assertEquals("part.bin", model.path.name)
    }

    @Test
    fun rejectsChangedArtifact() {
        val root = createBundle()
        File(root, "bundle/part.bin").appendText("changed")

        assertNull(DialogueModelProvisioner.fromAdmittedBundle(root).installed())
    }

    @Test
    fun rejectsUnadmittedArtifact() {
        val root = createBundle()
        File(root, "bundle/unexpected.bin").writeText("unexpected")

        assertNull(DialogueModelProvisioner.fromAdmittedBundle(root).installed())
    }

    @Test(expected = DialogueFailure::class)
    fun rejectsTraversalInManifest() {
        val root = createBundle()
        File(root, "dialogue-model.manifest.json").writeText(
            manifest("../outside", "bundle/part.bin", emptyList()),
        )

        DialogueModelProvisioner.fromAdmittedBundle(root)
    }

    private fun createBundle(): File {
        val root = temporaryFolder.newFolder()
        val bundle = File(root, "bundle").apply { mkdirs() }
        val config = File(bundle, "metadata.json").apply { writeText("{}") }
        val weights = File(bundle, "part.bin").apply { writeText("weights") }
        File(root, "dialogue-model.manifest.json").writeText(
            manifest(
                "bundle",
                "bundle/part.bin",
                listOf(
                    artifact("bundle/metadata.json", config),
                    artifact("bundle/part.bin", weights),
                ),
            ),
        )
        return root
    }

    private fun artifact(path: String, file: File): String =
        """{"path":"$path","size_bytes":${file.length()},"sha256":"${sha256(file)}"}"""

    private fun manifest(bundlePath: String, modelPath: String, artifacts: List<String>): String =
        """{"schema_version":2,"model_id":"test-model","runtime_id":"qairt","bundle_path":"$bundlePath","model_path":"$modelPath","artifacts":[${artifacts.joinToString()}]}"""

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
