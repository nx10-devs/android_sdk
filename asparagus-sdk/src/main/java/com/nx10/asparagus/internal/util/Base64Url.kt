package com.nx10.asparagus.internal.util

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin RFC 4648 URL-safe Base64 codec.
 *
 * Avoids `java.util.Base64` (API 26+) and `android.util.Base64` (unmocked in JVM tests)
 * so it works identically across Android API 24–37 and local JVM unit tests.
 */
object Base64Url {
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        for (i in 0 until 26) {
            this['A'.code + i] = i
            this['a'.code + i] = 26 + i
        }
        for (i in 0 until 10) {
            this['0'.code + i] = 52 + i
        }
        this['-'.code] = 62
        this['+'.code] = 62
        this['_'.code] = 63
        this['/'.code] = 63
    }

    /**
     * Decodes URL-safe (`-`, `_`) or standard (`+`, `/`) Base64 text, with or without `=` padding.
     */
    fun decode(input: String): ByteArray {
        val cleaned = StringBuilder(input.length)
        var paddingSeen = false
        for (ch in input) {
            when {
                ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' -> continue
                ch == '=' -> paddingSeen = true
                else -> {
                    if (paddingSeen) {
                        throw IllegalArgumentException("Unexpected character '$ch' after Base64 padding")
                    }
                    cleaned.append(ch)
                }
            }
        }

        val len = cleaned.length
        if (len == 0) return ByteArray(0)
        val remainder = len % 4
        if (remainder == 1) {
            throw IllegalArgumentException("Invalid Base64 length: $len")
        }

        val out = ByteArrayOutputStream((len * 3) / 4)
        var i = 0
        while (i + 4 <= len) {
            val a = decodeChar(cleaned[i])
            val b = decodeChar(cleaned[i + 1])
            val c = decodeChar(cleaned[i + 2])
            val d = decodeChar(cleaned[i + 3])
            val triple = (a shl 18) or (b shl 12) or (c shl 6) or d
            out.write((triple ushr 16) and 0xFF)
            out.write((triple ushr 8) and 0xFF)
            out.write(triple and 0xFF)
            i += 4
        }

        when (remainder) {
            2 -> {
                val a = decodeChar(cleaned[i])
                val b = decodeChar(cleaned[i + 1])
                val bits = (a shl 18) or (b shl 12)
                out.write((bits ushr 16) and 0xFF)
            }
            3 -> {
                val a = decodeChar(cleaned[i])
                val b = decodeChar(cleaned[i + 1])
                val c = decodeChar(cleaned[i + 2])
                val bits = (a shl 18) or (b shl 12) or (c shl 6)
                out.write((bits ushr 16) and 0xFF)
                out.write((bits ushr 8) and 0xFF)
            }
        }

        return out.toByteArray()
    }

    /**
     * Encodes [bytes] to unpadded RFC 4648 URL-safe Base64.
     */
    fun encodeNoPadding(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val triple = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(URL_ALPHABET[(triple ushr 18) and 0x3F])
            sb.append(URL_ALPHABET[(triple ushr 12) and 0x3F])
            sb.append(URL_ALPHABET[(triple ushr 6) and 0x3F])
            sb.append(URL_ALPHABET[triple and 0x3F])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val b0 = bytes[i].toInt() and 0xFF
            sb.append(URL_ALPHABET[b0 ushr 2])
            sb.append(URL_ALPHABET[(b0 and 0x03) shl 4])
        } else if (rem == 2) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            sb.append(URL_ALPHABET[b0 ushr 2])
            sb.append(URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(URL_ALPHABET[(b1 and 0x0F) shl 2])
        }
        return sb.toString()
    }

    private fun decodeChar(ch: Char): Int {
        val code = ch.code
        val value = if (code < 128) DECODE_TABLE[code] else -1
        if (value < 0) {
            throw IllegalArgumentException("Invalid Base64 character: '$ch'")
        }
        return value
    }
}
