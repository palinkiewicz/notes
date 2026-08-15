package pl.dakil.notes.format

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * LEB128 variable-length integers with zig-zag encoding for signed values.
 *
 * Stroke geometry is stored as deltas between consecutive points, and deltas from a digitiser are
 * almost always tiny — a varint spends one byte on the ±63 range that covers the overwhelming
 * majority of samples, where a raw float would spend four.
 */
object Varint {

    fun writeUnsigned(out: OutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    fun writeUnsigned(out: OutputStream, value: Int) = writeUnsigned(out, value.toLong() and 0xFFFFFFFFL)

    /** Zig-zag maps small negatives to small positives: -1 -> 1, 1 -> 2, -2 -> 3. */
    fun writeSigned(out: OutputStream, value: Int) =
        writeUnsigned(out, ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)

    fun readUnsignedLong(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = input.read()
            if (b < 0) throw EOFException("Truncated varint")
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw DakNoteFormatException("Varint longer than 64 bits")
    }

    fun readUnsignedInt(input: InputStream): Int = readUnsignedLong(input).toInt()

    fun readSigned(input: InputStream): Int {
        val raw = readUnsignedLong(input).toInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    fun writeU8(out: OutputStream, value: Int) = out.write(value and 0xFF)

    fun readU8(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Truncated byte")
        return b
    }

    fun writeU32(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun readU32(input: InputStream): Int =
        (readU8(input) shl 24) or (readU8(input) shl 16) or (readU8(input) shl 8) or readU8(input)
}
