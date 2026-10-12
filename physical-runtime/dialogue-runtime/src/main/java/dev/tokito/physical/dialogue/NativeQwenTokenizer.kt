package dev.tokito.physical.dialogue

import android.content.Context
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact pinned Qwen BPE. Serialized ownership prevents close/encode handle races. */
internal class NativeQwenTokenizer private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun encode(text: String): IntArray {
        check(handle != 0L) { "Qwen tokenizer is closed" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Qwen tokenization belongs off the UI thread" }
        val utf8 = text.toByteArray(Charsets.UTF_8)
        require(utf8.isNotEmpty() && utf8.size <= MAX_TEXT_BYTES)
        return nativeEncode(handle, utf8)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) { nativeClose(handle); handle = 0 }
    }

    private external fun nativeEncode(handle: Long, text: ByteArray): IntArray
    private external fun nativeClose(handle: Long)
    private external fun nativeOpen(json: ByteArray): Long

    companion object {
        const val TOKENIZER_SHA256 = "be75606093db2094d7cd20f3c2f385c212750648bd6ea4fb2bf507a6a4c55506"
        const val LIBRARY_SHA256 = "4c0d8dfae621c0487f3935556397398f537b16994f485a1f1eb0129afc2d7952"
        const val MAX_TEXT_BYTES = 256 * 1024
        private const val MAX_TOKENIZER_BYTES = 32 * 1024 * 1024

        fun open(context: Context, tokenizer: File): NativeQwenTokenizer {
            check(Looper.myLooper() != Looper.getMainLooper())
            require(tokenizer.isFile && tokenizer.length() in 1..MAX_TOKENIZER_BYTES.toLong())
            val json = tokenizer.inputStream().use { it.boundedBytes(MAX_TOKENIZER_BYTES) }
            require(sha256(json) == TOKENIZER_SHA256) { "Qwen tokenizer is not the admitted generator tokenizer" }
            verifyPackagedLibrary(context)
            System.loadLibrary("tokito_qwen_tokens")
            return NativeQwenTokenizer(0).apply { handle = nativeOpen(json).also { check(it != 0L) } }
        }

        internal fun verifyPackagedLibrary(context: Context, name: String = "libgeniex-proc.so", expected: String = LIBRARY_SHA256) {
            val entryName = "lib/arm64-v8a/$name"
            val apks = listOf(context.applicationInfo.sourceDir) +
                context.applicationInfo.splitSourceDirs.orEmpty().toList()
            val hashes = apks.mapNotNull { apk ->
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return@use null
                    require(entry.size in 1..64L * 1024 * 1024) { "Native tokenizer exceeds package bound" }
                    zip.getInputStream(entry).use { input -> digest(input, 64L * 1024 * 1024) }
                }
            }
            require(hashes.size == 1 && hashes.single() == expected) { "Pinned native library integrity mismatch: $name" }
        }

        private fun InputStream.boundedBytes(limit: Int): ByteArray {
            val data = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                require(data.size() + count <= limit) { "Tokenizer exceeds byte bound" }
                data.write(buffer, 0, count)
            }
            return data.toByteArray()
        }

        private fun digest(input: InputStream, limit: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                total += size
                require(total <= limit)
                digest.update(buffer, 0, size)
            }
            return digest.digest().hex()
        }

        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
