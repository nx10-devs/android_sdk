package com.nx10.asparagus.batch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip compression/decompression utilities matching the intake's `1f 8b` magic detection
 * (`BUILDING_AN_SDK.md` line 227) with bounded decompression.
 */
object GzipCodec {
    private const val GZIP_MAGIC_0: Byte = 0x1F.toByte()
    private const val GZIP_MAGIC_1: Byte = 0x8B.toByte()

    fun isGzipped(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1

    fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(maxOf(64, raw.size / 2))
        GZIPOutputStream(out).use { gz ->
            gz.write(raw)
        }
        return out.toByteArray()
    }

    fun gunzip(
        compressed: ByteArray,
        maxBytes: Int = BatchBoundsAndHeaderValidator.MAX_UNZIPPED_BATCH_BYTES,
    ): ByteArray {
        require(maxBytes >= 0) { "maxBytes must be >= 0: $maxBytes" }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        GZIPInputStream(ByteArrayInputStream(compressed)).use { gz ->
            while (true) {
                val read = gz.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalArgumentException("Unzipped payload exceeds maxBytes ($maxBytes)")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
