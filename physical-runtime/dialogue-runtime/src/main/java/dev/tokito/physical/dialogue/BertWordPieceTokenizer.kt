package dev.tokito.physical.dialogue

import java.text.Normalizer
import java.util.Locale

/** BERT uncased BasicTokenizer + greedy WordPiece, for the admitted MiniLM vocabulary. */
internal class BertWordPieceTokenizer(vocabulary: List<String>, private val maxTokens: Int = 256) {
    private val vocabulary = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val unknown: Long
    private val cls: Long
    private val sep: Long

    init {
        require(maxTokens in 8..256)
        require(vocabulary.size in 5..65536 && vocabulary.toSet().size == vocabulary.size)
        require(vocabulary.all { it.isNotEmpty() && it.length <= 128 })
        unknown = requireNotNull(this.vocabulary["[UNK]"])
        cls = requireNotNull(this.vocabulary["[CLS]"])
        sep = requireNotNull(this.vocabulary["[SEP]"])
        require(this.vocabulary.containsKey("[PAD]"))
    }

    fun encode(text: String): LongArray {
        require(text.isNotBlank() && text.length <= 8192) { "Embedding input must contain 1..8192 characters" }
        val output = ArrayList<Long>(maxTokens)
        output.add(cls)
        for (token in tokensWithSpecials(text)) {
            val pieces = wordPieces(token)
            for (piece in pieces) {
                if (output.size == maxTokens - 1) break
                output.add(piece)
            }
            if (output.size == maxTokens - 1) break
        }
        require(output.size > 1) { "Embedding input has no lexical content" }
        output.add(sep)
        return output.toLongArray()
    }

    private fun tokensWithSpecials(text: String): List<String> {
        val output = ArrayList<String>()
        var offset = 0
        for (match in SPECIAL_TOKEN_PATTERN.findAll(text)) {
            output.addAll(basicTokens(text.substring(offset, match.range.first)))
            output.add(match.value)
            offset = match.range.last + 1
        }
        output.addAll(basicTokens(text.substring(offset)))
        return output
    }

    private fun wordPieces(word: String): List<Long> {
        if (word.codePointCount(0, word.length) > 100) return listOf(unknown)
        val boundaries = ArrayList<Int>()
        boundaries.add(0)
        var offset = 0
        while (offset < word.length) {
            offset += Character.charCount(word.codePointAt(offset))
            boundaries.add(offset)
        }
        val pieces = ArrayList<Long>()
        var start = 0
        while (start < boundaries.lastIndex) {
            var end = boundaries.lastIndex
            var match: Long? = null
            while (end > start) {
                val value = word.substring(boundaries[start], boundaries[end])
                match = vocabulary[if (start == 0) value else "##$value"]
                if (match != null) break
                end--
            }
            if (match == null) return listOf(unknown)
            pieces.add(match)
            start = end
        }
        return pieces
    }

    private fun basicTokens(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            offset += Character.charCount(cp)
            when {
                cp == 0 || cp == 0xfffd -> Unit
                whitespace(cp) -> cleaned.append(' ')
                Character.getType(cp) in CONTROL_TYPES -> Unit
                chinese(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val output = ArrayList<String>()
        for (raw in cleaned.toString().split(' ').filter(String::isNotEmpty)) {
            // Hugging Face's uncased BERT tokenizer preserves explicit special tokens.
            if (raw in SPECIAL_TOKENS) {
                output.add(raw)
                continue
            }
            val normalized = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            val token = StringBuilder()
            var index = 0
            while (index < normalized.length) {
                val cp = normalized.codePointAt(index)
                index += Character.charCount(cp)
                when {
                    Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> Unit
                    punctuation(cp) -> {
                        if (token.isNotEmpty()) { output.add(token.toString()); token.setLength(0) }
                        output.add(String(Character.toChars(cp)))
                    }
                    else -> token.appendCodePoint(cp)
                }
            }
            if (token.isNotEmpty()) output.add(token.toString())
        }
        return output
    }

    private fun whitespace(cp: Int) = cp == 9 || cp == 10 || cp == 13 || cp == 32 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()
    private fun punctuation(cp: Int) = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
        Character.getType(cp) in PUNCTUATION_TYPES
    private fun chinese(cp: Int) = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf || cp in 0x20000..0x2a6df ||
        cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f || cp in 0x2b820..0x2ceaf ||
        cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f

    companion object {
        private val SPECIAL_TOKENS = setOf("[UNK]", "[SEP]", "[PAD]", "[CLS]", "[MASK]")
        private val SPECIAL_TOKEN_PATTERN = Regex("\\[(?:UNK|SEP|PAD|CLS|MASK)]")
        private val CONTROL_TYPES = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt())
        private val PUNCTUATION_TYPES = setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    }
}
