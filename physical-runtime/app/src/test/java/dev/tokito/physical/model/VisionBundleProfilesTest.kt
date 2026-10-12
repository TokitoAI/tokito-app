package dev.tokito.physical.model

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionBundleProfilesTest {
    private fun withBundle(profiles: String?, test: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("tokito-vision-profiles").toFile()
        try {
            root.resolve("model.manifest.json").writeText(if (profiles == null) "{}" else "{\"vision_profiles\":$profiles}")
            root.resolve("pose").mkdir()
            root.resolve("segment").mkdir()
            root.resolve("segment/segmentation.json").writeText("{}")
            test(root)
        } finally { root.deleteRecursively() }
    }

    @Test fun routesBothTasksWithinOneAdmittedBundle() = withBundle("""[{"task":"board_pose","directory":"pose"},{"task":"component_segmentation","directory":"segment"}]""") { root ->
        val profiles = VisionBundleProfiles.fromAdmittedBundle(root)
        assertEquals(root.resolve("pose").canonicalFile, profiles.pose)
        assertEquals(root.resolve("segment").canonicalFile, profiles.segmentation)
    }

    @Test fun standalonePoseRemainsExplicitlyAvailable() = withBundle(null) { root ->
        val profiles = VisionBundleProfiles.fromAdmittedBundle(root)
        assertEquals(root, profiles.pose)
        assertNull(profiles.segmentation)
    }

    @Test fun rejectsAmbiguousEscapingOrWrongTaskProfiles() {
        val invalid = listOf(
            "[]",
            """[{"task":"board_pose","directory":"../pose"}]""",
            """[{"task":"board_pose","directory":"segment"}]""",
            """[{"task":"unknown","directory":"pose"}]""",
            """[{"task":"board_pose","directory":"pose","extra":true}]""",
            """[{"task":"board_pose","directory":"pose"},{"task":"board_pose","directory":"pose"}]""",
        )
        invalid.forEach { input -> withBundle(input) { root ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { VisionBundleProfiles.fromAdmittedBundle(root) }
        } }
    }
}
