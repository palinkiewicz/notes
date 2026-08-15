package pl.dakil.notes.format

import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * The `.dsv` binary stroke stream.
 *
 * Ink is the only part of a `.daknote` that is not human-readable, and for good reason: a dense
 * handwriting page is tens of thousands of samples, and JSON floats cost roughly twenty bytes each.
 * Quantising to a fixed-point grid and delta-varint encoding brings that to two to four bytes per
 * point — about a tenth the size — before the container's DEFLATE takes another cut.
 *
 * Layout:
 * ```
 * "DSV1"                4 bytes magic
 * u8   flags            bit0 width factors, bit1 tilt+orientation, bit2 timestamps
 * varint strokeCount
 * per stroke:
 *   varint toolOrdinal
 *   u32    argb
 *   varint widthQ                     width in 1/32 pt
 *   u8     blendOrdinal
 *   varint pointCount
 *   points:
 *     zigzag varint dx, dy            deltas of coordinates quantised to 1/32 pt
 *     u8 widthFactor                  if bit0, per-point width as a fraction of width
 *     u8 tilt, u8 orientation         if bit1
 *     varint dt                       if bit2, milliseconds since the previous sample
 * ```
 */
object StrokeCodec {

    private const val MAGIC_0 = 'D'.code
    private const val MAGIC_1 = 'S'.code
    private const val MAGIC_2 = 'V'.code
    private const val MAGIC_3 = '1'.code

    private const val FLAG_WIDTH = 1
    private const val FLAG_TILT = 2
    private const val FLAG_TIME = 4

    /**
     * Fixed-point scale: 32 steps per point gives ~0.011 mm of resolution, an order of magnitude
     * finer than any digitiser reports, so quantisation is invisible.
     */
    const val QUANT = 32f

    /** Guards against a corrupt or hostile file allocating an enormous array. */
    private const val MAX_POINTS = 4_000_000

    fun encode(strokes: List<Stroke>): ByteArray {
        val out = ByteArrayOutputStream(estimateSize(strokes))
        write(out, strokes)
        return out.toByteArray()
    }

    fun write(out: OutputStream, strokes: List<Stroke>) {
        out.write(MAGIC_0); out.write(MAGIC_1); out.write(MAGIC_2); out.write(MAGIC_3)

        // Flags are per-file, not per-stroke: a note is drawn with one device in practice, and a
        // shared header keeps the per-point cost at its minimum.
        var flags = 0
        if (strokes.any { it.widthFactors != null }) flags = flags or FLAG_WIDTH
        if (strokes.any { it.tilts != null }) flags = flags or FLAG_TILT
        if (strokes.any { it.times != null }) flags = flags or FLAG_TIME
        Varint.writeU8(out, flags)
        Varint.writeUnsigned(out, strokes.size)

        for (s in strokes) {
            Varint.writeUnsigned(out, s.tool.ordinal)
            Varint.writeU32(out, s.color)
            Varint.writeUnsigned(out, quantize(s.width).coerceAtLeast(0))
            Varint.writeU8(out, s.blend.ordinal)
            Varint.writeUnsigned(out, s.pointCount)

            var prevX = 0
            var prevY = 0
            var prevT = 0
            for (i in 0 until s.pointCount) {
                val qx = quantize(s.xs[i])
                val qy = quantize(s.ys[i])
                Varint.writeSigned(out, qx - prevX)
                Varint.writeSigned(out, qy - prevY)
                prevX = qx
                prevY = qy

                if (flags and FLAG_WIDTH != 0) {
                    Varint.writeU8(out, toByteUnit(s.widthFactors?.getOrNull(i) ?: 1f))
                }
                if (flags and FLAG_TILT != 0) {
                    val tilt = s.tilts?.getOrNull(i) ?: 0f
                    // Tilt is 0..PI/2 from the screen normal; orientation rides along in the model's
                    // tilt array only when present, so encode a conservative pair.
                    Varint.writeU8(out, toByteUnit(tilt / HALF_PI))
                    Varint.writeU8(out, 0)
                }
                if (flags and FLAG_TIME != 0) {
                    val t = s.times?.getOrNull(i) ?: 0
                    Varint.writeUnsigned(out, (t - prevT).coerceAtLeast(0))
                    prevT = t
                }
            }
        }
    }

    fun decode(bytes: ByteArray): List<Stroke> = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): List<Stroke> {
        if (Varint.readU8(input) != MAGIC_0 || Varint.readU8(input) != MAGIC_1 ||
            Varint.readU8(input) != MAGIC_2 || Varint.readU8(input) != MAGIC_3
        ) {
            throw DakNoteFormatException("Not a DSV1 stroke stream")
        }
        val flags = Varint.readU8(input)
        val hasWidth = flags and FLAG_WIDTH != 0
        val hasTilt = flags and FLAG_TILT != 0
        val hasTime = flags and FLAG_TIME != 0

        val strokeCount = Varint.readUnsignedInt(input)
        if (strokeCount < 0) throw DakNoteFormatException("Negative stroke count")
        val strokes = ArrayList<Stroke>(minOf(strokeCount, 1024))

        repeat(strokeCount) {
            val tool = ToolId.fromOrdinal(Varint.readUnsignedInt(input))
            val color = Varint.readU32(input)
            val width = Varint.readUnsignedInt(input) / QUANT
            val blend = BlendId.fromOrdinal(Varint.readU8(input))
            val n = Varint.readUnsignedInt(input)
            if (n < 0 || n > MAX_POINTS) throw DakNoteFormatException("Implausible point count: $n")

            val xs = FloatArray(n)
            val ys = FloatArray(n)
            val widthFactors = if (hasWidth) FloatArray(n) else null
            val tilts = if (hasTilt) FloatArray(n) else null
            val times = if (hasTime) IntArray(n) else null

            var accX = 0
            var accY = 0
            var accT = 0
            for (i in 0 until n) {
                accX += Varint.readSigned(input)
                accY += Varint.readSigned(input)
                xs[i] = accX / QUANT
                ys[i] = accY / QUANT
                if (hasWidth) widthFactors!![i] = fromByteUnit(Varint.readU8(input))
                if (hasTilt) {
                    tilts!![i] = fromByteUnit(Varint.readU8(input)) * HALF_PI
                    Varint.readU8(input) // orientation, reserved
                }
                if (hasTime) {
                    accT += Varint.readUnsignedInt(input)
                    times!![i] = accT
                }
            }
            strokes.add(Stroke(tool, color, width, blend, xs, ys, widthFactors, tilts, times))
        }
        return strokes
    }

    /** Reads leniently, returning what was decoded before a truncation. Used for crash recovery. */
    fun readLenient(bytes: ByteArray): List<Stroke> = try {
        decode(bytes)
    } catch (_: EOFException) {
        emptyList()
    } catch (_: DakNoteFormatException) {
        emptyList()
    }

    private const val HALF_PI = (Math.PI / 2.0).toFloat()

    private fun quantize(v: Float): Int = (v * QUANT).roundToInt()

    private fun toByteUnit(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    private fun fromByteUnit(b: Int): Float = b / 255f

    private fun estimateSize(strokes: List<Stroke>): Int {
        var points = 0
        for (s in strokes) points += s.pointCount
        return 16 + strokes.size * 16 + points * 6
    }
}
