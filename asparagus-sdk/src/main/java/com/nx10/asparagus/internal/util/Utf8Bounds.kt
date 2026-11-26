package com.nx10.asparagus.internal.util

/**
 * UTF-8 byte-length bounds helpers matching `batch.proto` lines 26-27, 53-54, and 68-70:
 * "Every text is valid UTF-8. A limit on the length of a text never cuts a character in two."
 */
object Utf8Bounds {
    const val MAX_HEADER_TEXT_BYTES: Int = 1024

    /**
     * Returns the exact UTF-8 byte length of [text].
     */
    fun utf8ByteLength(text: String): Int {
        if (text.isEmpty()) return 0
        return text.toByteArray(Charsets.UTF_8).size
    }

    /**
     * Truncates [text] so its UTF-8 encoding is at most [maxBytes] bytes, without ever
     * splitting a multi-byte UTF-8 code point or leaving an unpaired UTF-16 high surrogate.
     */
    fun truncateUtf8(text: String, maxBytes: Int = MAX_HEADER_TEXT_BYTES): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        if (text.length * 4 <= maxBytes && !containsUnpairedSurrogate(text)) {
            return text
        }

        var byteCount = 0
        var index = 0
        val length = text.length
        while (index < length) {
            val ch = text[index]
            val codePoint: Int
            val charCount: Int
            val cpBytes: Int

            if (Character.isHighSurrogate(ch)) {
                if (index + 1 < length && Character.isLowSurrogate(text[index + 1])) {
                    codePoint = Character.toCodePoint(ch, text[index + 1])
                    charCount = 2
                    cpBytes = 4
                } else {
                    // Unpaired high surrogate: stop before it to guarantee valid UTF-8.
                    break
                }
            } else if (Character.isLowSurrogate(ch)) {
                // Unpaired low surrogate: stop before it to guarantee valid UTF-8.
                break
            } else {
                codePoint = ch.code
                charCount = 1
                cpBytes = when {
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    else -> 3
                }
            }

            if (byteCount + cpBytes > maxBytes) {
                break
            }
            byteCount += cpBytes
            index += charCount
        }

        return if (index == length) text else text.substring(0, index)
    }

    private fun containsUnpairedSurrogate(text: String): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= len || !Character.isLowSurrogate(text[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return true
            } else {
                i += 1
            }
        }
        return false
    }
}
