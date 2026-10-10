package dev.tokito.physical.dialogue

import kotlin.math.sqrt

internal object EmbeddingMath {
    fun meanPool(tokens: Array<FloatArray>, attention: LongArray, dimensions: Int): FloatArray {
        require(tokens.isNotEmpty() && tokens.size == attention.size)
        require(dimensions in 1..1024 && tokens.all { it.size == dimensions })
        require(attention.all { it == 0L || it == 1L } && attention.any { it == 1L })
        val summed = DoubleArray(dimensions)
        var count = 0
        for (i in tokens.indices) {
            require(tokens[i].all(Float::isFinite)) { "Non-finite embedding model output" }
            if (attention[i] == 0L) continue
            count++
            for (d in 0 until dimensions) summed[d] += tokens[i][d].toDouble()
        }
        return normalize(FloatArray(dimensions) { (summed[it] / count).toFloat() })
    }

    fun normalize(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty() && vector.all(Float::isFinite))
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        require(norm.isFinite() && norm > 1e-12) { "Zero or invalid embedding vector" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }
}
