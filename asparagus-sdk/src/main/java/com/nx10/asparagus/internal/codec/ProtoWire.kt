package com.nx10.asparagus.internal.codec

import java.io.ByteArrayOutputStream

/**
 * Exception raised when binary Protobuf wire bytes are malformed or violate wire constraints.
 */
class ProtoDecodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object WireType {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH_DELIMITED = 2
    const val START_GROUP = 3
    const val END_GROUP = 4
    const val FIXED32 = 5
}

/**
 * Minimal, spec-exact Protobuf v3 binary wire writer.
 */
internal class ProtoWriter(initialCapacity: Int = 256) {
    private val out = ByteArrayOutputStream(initialCapacity)

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeTag(fieldNumber: Int, wireType: Int) {
        require(fieldNumber > 0) { "Field number must be positive: $fieldNumber" }
        writeVarint32(((fieldNumber shl 3) or (wireType and 0x7)).toUInt())
    }

    fun writeVarint32(value: UInt) {
        var v = value
        while ((v and 0x7Fu.inv()) != 0u) {
            out.write(((v and 0x7Fu) or 0x80u).toInt())
            v = v shr 7
        }
        out.write(v.toInt())
    }

    fun writeVarint64(value: ULong) {
        var v = value
        while ((v and 0x7FuL.inv()) != 0uL) {
            out.write(((v and 0x7FuL) or 0x80uL).toInt())
            v = v shr 7
        }
        out.write(v.toInt())
    }

    fun writeUInt32(fieldNumber: Int, value: Int, writeDefault: Boolean = false) {
        if (!writeDefault && value == 0) return
        writeTag(fieldNumber, WireType.VARINT)
        writeVarint32(value.toUInt())
    }

    fun writeUInt64(fieldNumber: Int, value: Long, writeDefault: Boolean = false) {
        if (!writeDefault && value == 0L) return
        writeTag(fieldNumber, WireType.VARINT)
        writeVarint64(value.toULong())
    }

    fun writeEnum(fieldNumber: Int, protoNumber: Int, writeDefault: Boolean = false) {
        if (!writeDefault && protoNumber == 0) return
        writeTag(fieldNumber, WireType.VARINT)
        // Proto3 enums encode as int32 (sign-extended to 64-bit varint if negative).
        writeVarint64(protoNumber.toLong().toULong())
    }

    fun writeBool(fieldNumber: Int, value: Boolean, writeDefault: Boolean = false) {
        if (!writeDefault && !value) return
        writeTag(fieldNumber, WireType.VARINT)
        out.write(if (value) 1 else 0)
    }

    fun writeSInt32(fieldNumber: Int, value: Int, writeDefault: Boolean = false) {
        if (!writeDefault && value == 0) return
        writeTag(fieldNumber, WireType.VARINT)
        writeRawSInt32(value)
    }

    fun writeRawSInt32(value: Int) {
        // ZigZag 32-bit: never sign-extended to 64 bits (at most 5 bytes).
        val zigZag: UInt = ((value shl 1) xor (value shr 31)).toUInt()
        writeVarint32(zigZag)
    }

    fun writeSInt64(fieldNumber: Int, value: Long, writeDefault: Boolean = false) {
        if (!writeDefault && value == 0L) return
        writeTag(fieldNumber, WireType.VARINT)
        writeRawSInt64(value)
    }

    fun writeRawSInt64(value: Long) {
        val zigZag: ULong = ((value shl 1) xor (value shr 63)).toULong()
        writeVarint64(zigZag)
    }

    fun writeFloat(fieldNumber: Int, value: Float, writeDefault: Boolean = false) {
        if (!writeDefault && value == 0f) return
        writeTag(fieldNumber, WireType.FIXED32)
        val bits = java.lang.Float.floatToRawIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits ushr 8) and 0xFF)
        out.write((bits ushr 16) and 0xFF)
        out.write((bits ushr 24) and 0xFF)
    }

    fun writeDouble(fieldNumber: Int, value: Double, writeDefault: Boolean = false) {
        if (!writeDefault && value == 0.0) return
        writeTag(fieldNumber, WireType.FIXED64)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        out.write((bits and 0xFFL).toInt())
        out.write(((bits ushr 8) and 0xFFL).toInt())
        out.write(((bits ushr 16) and 0xFFL).toInt())
        out.write(((bits ushr 24) and 0xFFL).toInt())
        out.write(((bits ushr 32) and 0xFFL).toInt())
        out.write(((bits ushr 40) and 0xFFL).toInt())
        out.write(((bits ushr 48) and 0xFFL).toInt())
        out.write(((bits ushr 56) and 0xFFL).toInt())
    }

    fun writeString(fieldNumber: Int, value: String, writeDefault: Boolean = false) {
        if (!writeDefault && value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(fieldNumber, WireType.LENGTH_DELIMITED)
        writeVarint32(bytes.size.toUInt())
        out.write(bytes)
    }

    fun writeBytes(fieldNumber: Int, bytes: ByteArray, writeEmpty: Boolean = false) {
        if (!writeEmpty && bytes.isEmpty()) return
        writeTag(fieldNumber, WireType.LENGTH_DELIMITED)
        writeVarint32(bytes.size.toUInt())
        out.write(bytes)
    }

    /**
     * Writes an embedded message. Present-but-empty submessages MUST write tag + 0x00 length
     * so presence is preserved on the wire.
     */
    fun writeMessage(fieldNumber: Int, encoder: (ProtoWriter) -> Unit) {
        val sub = ProtoWriter()
        encoder(sub)
        val bytes = sub.toByteArray()
        writeTag(fieldNumber, WireType.LENGTH_DELIMITED)
        writeVarint32(bytes.size.toUInt())
        out.write(bytes)
    }

    /**
     * Writes a packed `repeated sint32` field. Empty lists are omitted per proto3 rules.
     */
    fun writePackedSInt32(fieldNumber: Int, values: List<Int>) {
        if (values.isEmpty()) return
        val packed = ProtoWriter(values.size * 2)
        for (v in values) {
            packed.writeRawSInt32(v)
        }
        val bytes = packed.toByteArray()
        writeTag(fieldNumber, WireType.LENGTH_DELIMITED)
        writeVarint32(bytes.size.toUInt())
        out.write(bytes)
    }
}

/**
 * Minimal, spec-exact Protobuf v3 binary wire reader with strict bounds and depth checks.
 */
internal class ProtoReader(
    private val data: ByteArray,
    private var pos: Int = 0,
    private val limit: Int = data.size,
    private val depth: Int = 0,
) {
    val isAtEnd: Boolean
        get() = pos >= limit

    val remaining: Int
        get() = limit - pos

    fun readTag(): Int {
        if (isAtEnd) return 0
        val raw = readVarint64()
        if (raw == 0uL || raw > 0xFFFFFFFFuL) {
            throw ProtoDecodeException("Invalid protobuf tag: $raw")
        }
        val tag = raw.toInt()
        val fieldNumber = tag ushr 3
        if (fieldNumber <= 0) {
            throw ProtoDecodeException("Invalid protobuf field number: $fieldNumber")
        }
        return tag
    }

    fun readVarint64(): ULong {
        var result = 0uL
        var shift = 0
        while (shift < 64) {
            if (pos >= limit) {
                throw ProtoDecodeException("Unexpected end of input while reading varint")
            }
            val b = data[pos++].toInt() and 0xFF
            if (shift == 63 && b > 1) {
                throw ProtoDecodeException("Malformed 64-bit varint overflow")
            }
            result = result or ((b and 0x7F).toULong() shl shift)
            if ((b and 0x80) == 0) {
                return result
            }
            shift += 7
        }
        throw ProtoDecodeException("Malformed varint: exceeded 10 bytes")
    }

    fun readVarint32(): UInt = readVarint64().toUInt()

    fun readUInt32(): Int = readVarint32().toInt()

    fun readUInt64(): Long = readVarint64().toLong()

    fun readEnum(): Int = readVarint64().toInt()

    fun readBool(): Boolean = readVarint64() != 0uL

    fun readSInt32(): Int {
        val raw = readVarint32().toInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    fun readSInt64(): Long {
        val raw = readVarint64()
        return (raw shr 1).toLong() xor -(raw and 1uL).toLong()
    }

    fun readFloat(): Float {
        if (remaining < 4) {
            throw ProtoDecodeException("Unexpected end of input while reading float")
        }
        val bits = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun readDouble(): Double {
        if (remaining < 8) {
            throw ProtoDecodeException("Unexpected end of input while reading double")
        }
        val bits = (data[pos].toLong() and 0xFFL) or
            ((data[pos + 1].toLong() and 0xFFL) shl 8) or
            ((data[pos + 2].toLong() and 0xFFL) shl 16) or
            ((data[pos + 3].toLong() and 0xFFL) shl 24) or
            ((data[pos + 4].toLong() and 0xFFL) shl 32) or
            ((data[pos + 5].toLong() and 0xFFL) shl 40) or
            ((data[pos + 6].toLong() and 0xFFL) shl 48) or
            ((data[pos + 7].toLong() and 0xFFL) shl 56)
        pos += 8
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun readLength(): Int {
        val raw = readVarint64()
        if (raw > Int.MAX_VALUE.toULong()) {
            throw ProtoDecodeException("Length-delimited size too large: $raw")
        }
        val length = raw.toInt()
        if (length < 0 || length > remaining) {
            throw ProtoDecodeException("Length-delimited field ($length bytes) exceeds remaining input ($remaining bytes)")
        }
        return length
    }

    fun readString(): String {
        val length = readLength()
        val str = String(data, pos, length, Charsets.UTF_8)
        pos += length
        return str
    }

    fun readSubMessageReader(): ProtoReader {
        if (depth >= MAX_RECURSION_DEPTH) {
            throw ProtoDecodeException("Protobuf message nesting depth exceeded $MAX_RECURSION_DEPTH")
        }
        val length = readLength()
        val subStart = pos
        pos += length
        return ProtoReader(data, subStart, subStart + length, depth + 1)
    }

    /**
     * Reads either a packed (`LENGTH_DELIMITED`) or unpacked (`VARINT`) `repeated sint32` field.
     */
    fun readRepeatedSInt32(wireType: Int, target: MutableList<Int>) {
        when (wireType) {
            WireType.LENGTH_DELIMITED -> {
                val sub = readSubMessageReader()
                while (!sub.isAtEnd) {
                    target.add(sub.readSInt32())
                }
            }
            WireType.VARINT -> {
                target.add(readSInt32())
            }
            else -> throw ProtoDecodeException("Unexpected wire type $wireType for repeated sint32")
        }
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            WireType.VARINT -> readVarint64()
            WireType.FIXED64 -> {
                if (remaining < 8) throw ProtoDecodeException("Unexpected EOF skipping fixed64")
                pos += 8
            }
            WireType.LENGTH_DELIMITED -> {
                val length = readLength()
                pos += length
            }
            WireType.FIXED32 -> {
                if (remaining < 4) throw ProtoDecodeException("Unexpected EOF skipping fixed32")
                pos += 4
            }
            else -> throw ProtoDecodeException("Unsupported protobuf wire type: $wireType")
        }
    }

    fun expectWireType(actual: Int, expected: Int, fieldName: String) {
        if (actual != expected) {
            throw ProtoDecodeException("Wire type mismatch for $fieldName: expected $expected, got $actual")
        }
    }

    companion object {
        private const val MAX_RECURSION_DEPTH = 64
    }
}
