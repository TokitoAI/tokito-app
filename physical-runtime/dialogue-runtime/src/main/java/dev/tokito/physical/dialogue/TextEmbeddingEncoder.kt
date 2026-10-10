package dev.tokito.physical.dialogue

/** Local neural encoder. Identity binds model weights, tokenizer and preprocessing. */
interface TextEmbeddingEncoder : AutoCloseable {
    val modelIdentity: String
    val dimensions: Int
    /** Returns a finite, unit-L2 vector; invalid input/output fails rather than fabricating evidence. */
    fun embed(text: String): FloatArray
}
