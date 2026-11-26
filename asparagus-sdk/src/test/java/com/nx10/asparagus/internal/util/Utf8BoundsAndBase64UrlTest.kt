package com.nx10.asparagus.internal.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8BoundsAndBase64UrlTest {

    @Test
    fun `utf8ByteLength and truncateUtf8 never split multi-byte characters or surrogate pairs`() {
        // 'C'(1) 'a'(1) 'f'(1) 'é'(2) ' '(1) '小'(3) '店'(3) ' '(1) '🛒'(4) => 17 UTF-8 bytes
        val sample = "Café 小店 🛒"
        assertEquals(17, Utf8Bounds.utf8ByteLength(sample))
        assertEquals(sample, Utf8Bounds.truncateUtf8(sample, 17))
        assertEquals(sample, Utf8Bounds.truncateUtf8(sample, 1024))

        // Cutting right inside the 4-byte emoji (bytes 14..16) must drop the entire emoji, leaving no lone high surrogate
        for (limit in 13..16) {
            val truncated = Utf8Bounds.truncateUtf8(sample, limit)
            assertEquals("Café 小店 ", truncated)
            assertTrue(Utf8Bounds.utf8ByteLength(truncated) <= limit)
            assertFalse(Character.isHighSurrogate(truncated.last()))
        }

        // Cutting inside 3-byte '小' (bytes 7..8) drops '小' cleanly
        assertEquals("Café ", Utf8Bounds.truncateUtf8(sample, 6))
        assertEquals("Café ", Utf8Bounds.truncateUtf8(sample, 7))
        assertEquals("Café ", Utf8Bounds.truncateUtf8(sample, 8))
        assertEquals("Café 小", Utf8Bounds.truncateUtf8(sample, 9))

        // Cutting inside 2-byte 'é' (byte 4) drops 'é' cleanly
        assertEquals("Caf", Utf8Bounds.truncateUtf8(sample, 4))
        assertEquals("Caf", Utf8Bounds.truncateUtf8(sample, 3))
        assertEquals("Café", Utf8Bounds.truncateUtf8(sample, 5))

        // Over 1024-byte text of 4-byte emoji truncates at exactly 1024 bytes (256 emoji)
        val longEmoji = "🛒".repeat(300)
        val bounded = Utf8Bounds.truncateUtf8(longEmoji, Utf8Bounds.MAX_HEADER_TEXT_BYTES)
        assertEquals(1024, Utf8Bounds.utf8ByteLength(bounded))
        assertEquals(256, bounded.codePointCount(0, bounded.length))
        assertEquals("", Utf8Bounds.truncateUtf8(sample, 0))
    }

    @Test
    fun `base64Url decodes padded, unpadded, url-safe, and standard base64 payloads`() {
        val raw = """{"asOrganisationId":"made-up-org-1","msg":"Café 🛒"}""".toByteArray(Charsets.UTF_8)
        val encodedNoPad = Base64Url.encodeNoPadding(raw)
        assertFalse(encodedNoPad.contains('='))
        assertArrayEquals(raw, Base64Url.decode(encodedNoPad))

        // Add '=' padding back
        val padLen = (4 - (encodedNoPad.length % 4)) % 4
        val encodedPadded = encodedNoPad + "=".repeat(padLen)
        assertArrayEquals(raw, Base64Url.decode(encodedPadded))

        // Verify URL-safe '-' and '_' (bytes 0xFB, 0xFF, 0xFE -> "+//+" standard vs "-__-" url-safe)
        val specialBytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte())
        assertEquals("-__-", Base64Url.encodeNoPadding(specialBytes))
        assertArrayEquals(specialBytes, Base64Url.decode("-__-"))
        assertArrayEquals(specialBytes, Base64Url.decode("+//+"))

        // Verify 1-byte and 2-byte remainders with and without padding
        assertEquals("f", Base64Url.decode("Zg").toString(Charsets.UTF_8))
        assertEquals("f", Base64Url.decode("Zg==").toString(Charsets.UTF_8))
        assertEquals("fo", Base64Url.decode("Zm8").toString(Charsets.UTF_8))
        assertEquals("fo", Base64Url.decode("Zm8=").toString(Charsets.UTF_8))
        assertEquals("foo", Base64Url.decode("Zm9v").toString(Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `base64Url rejects invalid length mod 4 equals 1`() {
        Base64Url.decode("A")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `base64Url rejects illegal characters`() {
        Base64Url.decode("abc\$def")
    }
}
