package dev.tokito.physical.model

import dev.tokito.physical.dialogue.LocalSegmentationRuntime
import java.io.File
import org.json.JSONObject

/** Task routing is signed metadata, not a filename or directory-search heuristic.
 * The caller must first admit the entire bundle through ModelBundleRepository.
 */
data class VisionBundleProfiles(val pose: File?, val segmentation: File?) {
    companion object {
        fun fromAdmittedBundle(root: File): VisionBundleProfiles {
            val manifest = JSONObject(File(root, "model.manifest.json").readText())
            val profiles = manifest.optJSONArray("vision_profiles")
            if (profiles == null) {
                return if (LocalSegmentationRuntime.isBundle(root)) VisionBundleProfiles(null, root)
                else VisionBundleProfiles(root, null)
            }
            require(profiles.length() in 1..2)
            val directories = linkedMapOf<String, File>()
            val paths = hashSetOf<String>()
            repeat(profiles.length()) { index ->
                val profile = profiles.getJSONObject(index)
                require(profile.keys().asSequence().toSet() == setOf("task", "directory"))
                val task = profile.getString("task")
                require(task in setOf("board_pose", "component_segmentation") && task !in directories)
                val path = profile.getString("directory")
                require(path.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && paths.add(path))
                val directory = File(root, path).canonicalFile
                require(directory.parentFile == root.canonicalFile && directory.isDirectory)
                require(LocalSegmentationRuntime.isBundle(directory) == (task == "component_segmentation"))
                directories[task] = directory
            }
            return VisionBundleProfiles(directories["board_pose"], directories["component_segmentation"])
        }
    }
}
