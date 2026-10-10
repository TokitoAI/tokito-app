package dev.tokito.physical.session

/** Explicit control-plane decoding; opaque identifiers are never trimmed or UUID-coerced. */
object PhysicalSessionInput {
    private val keys = setOf("projectId", "designId", "contextRevision")
    const val MAX_VALUE_BYTES = 1024

    fun decode(input: Map<String, Any?>?): PhysicalSessionContext {
        require(input == null || input.keys.all(keys::contains)) { "Unknown physical session context field" }
        fun value(key: String): String? {
            val raw = input?.get(key) ?: return null
            require(raw is String) { "$key must be a string or null" }
            require(raw.isNotBlank()) { "$key cannot be blank" }
            require(raw.length <= MAX_VALUE_BYTES) { "$key exceeds its character limit" }
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES) { "$key exceeds its byte limit" }
            require(raw.none(Char::isISOControl)) { "$key contains a control character" }
            return raw
        }
        return PhysicalSessionContext(value("projectId"), value("designId"), value("contextRevision"))
    }
}
