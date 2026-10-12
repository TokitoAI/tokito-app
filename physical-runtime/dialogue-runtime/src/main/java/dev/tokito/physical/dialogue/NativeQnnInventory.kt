package dev.tokito.physical.dialogue

import android.content.Context
import android.os.Looper
import java.io.File

/** QNN System parses real signed binaries, not heuristic scans of binary strings. */
internal class NativeQnnInventory private constructor() {
    private external fun nativeGraphNames(path: ByteArray): Array<String>

    companion object {
        private const val LIBRARY_SHA256 = "f08e9ddc18a40719839cce8bbfb3afd3aef06f5ad486b21934a1c4568c9a6a48"

        fun read(context: Context, files: List<File>): Map<String, List<String>> {
            check(Looper.myLooper() != Looper.getMainLooper()) { "QNN inventory belongs off the UI thread" }
            require(files.size in 1..256 && files.map { it.name }.distinct().size == files.size)
            NativeQwenTokenizer.verifyPackagedLibrary(context, "libQnnSystem.so", LIBRARY_SHA256)
            System.loadLibrary("tokito_qwen_tokens")
            val native = NativeQnnInventory()
            return files.associate { file ->
                require(file.isFile && file.length() in 1..3L * 1024 * 1024 * 1024)
                file.name to native.nativeGraphNames(file.canonicalPath.toByteArray(Charsets.UTF_8)).toList()
            }
        }
    }
}
