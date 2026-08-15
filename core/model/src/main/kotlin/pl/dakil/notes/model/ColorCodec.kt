package pl.dakil.notes.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Colour conversion and parsing, in packed ARGB.
 *
 * Kept as pure Kotlin in the model rather than reaching for `android.graphics.Color` so it can be
 * unit-tested on the JVM, and so the file format, the renderer and the colour picker all agree on
 * exactly one implementation of "what does this hex string mean".
 */
object ColorCodec {

    /** `#AARRGGBB`, chosen over a raw integer so a human can read and edit a page's colours. */
    fun toHex(argb: Int, includeAlpha: Boolean = true): String {
        val sb = StringBuilder(9)
        sb.append('#')
        val value = if (includeAlpha) argb else argb and 0x00FFFFFF
        val digits = if (includeAlpha) 8 else 6
        val hex = (value.toLong() and 0xFFFFFFFFL).toString(16).uppercase()
        val trimmed = if (hex.length > digits) hex.substring(hex.length - digits) else hex
        repeat(digits - trimmed.length) { sb.append('0') }
        sb.append(trimmed)
        return sb.toString()
    }

    /**
     * Parses `#RGB`, `#RGBA`, `#RRGGBB` or `#AARRGGBB`, with or without the leading `#`.
     *
     * Returns null rather than throwing: this backs a text field the user types into character by
     * character, where most intermediate states are legitimately incomplete.
     */
    fun parse(text: String): Int? {
        val s = text.trim().removePrefix("#")
        if (s.isEmpty() || s.any { it.digitToIntOrNull(16) == null }) return null
        return when (s.length) {
            3 -> {
                val r = s[0].digitToInt(16); val g = s[1].digitToInt(16); val b = s[2].digitToInt(16)
                pack(255, r * 17, g * 17, b * 17)
            }
            4 -> {
                val r = s[0].digitToInt(16); val g = s[1].digitToInt(16)
                val b = s[2].digitToInt(16); val a = s[3].digitToInt(16)
                pack(a * 17, r * 17, g * 17, b * 17)
            }
            6 -> (s.toLong(16) or 0xFF000000L).toInt()
            8 -> s.toLong(16).toInt()
            else -> null
        }
    }

    fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    /**
     * Perceived brightness, 0..1.
     *
     * Used to decide whether a swatch needs a dark or a light check mark — a fixed colour would
     * disappear on either white or black paper, and the user can pick both.
     */
    fun luminance(argb: Int): Float =
        (0.299f * red(argb) + 0.587f * green(argb) + 0.114f * blue(argb)) / 255f

    /** Converts to hue (0..360), saturation (0..1) and value (0..1), written into [out]. */
    fun toHsv(argb: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val r = red(argb) / 255f
        val g = green(argb) / 255f
        val b = blue(argb) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        out[0] = when {
            delta < 1e-6f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (out[0] < 0f) out[0] += 360f
        out[1] = if (max < 1e-6f) 0f else delta / max
        out[2] = max
        return out
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float, alpha: Int = 255): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)

        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c

        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return pack(
            alpha.coerceIn(0, 255),
            ((r + m) * 255f).roundToInt(),
            ((g + m) * 255f).roundToInt(),
            ((b + m) * 255f).roundToInt(),
        )
    }

    /**
     * The ink colours offered before the user picks their own.
     *
     * Deliberately not derived from the Material colour scheme: pen colour is document content, not
     * app chrome. It must mean the same thing on every device, survive a theme change, and still be
     * that colour when the note is opened years later — none of which is true of a dynamic palette.
     */
    val INK_PRESETS = intArrayOf(
        0xFF1B1B1F.toInt(), // near-black
        0xFF3F5BA9.toInt(), // ink blue
        0xFFC0392B.toInt(), // red
        0xFF1E8449.toInt(), // green
        0xFFD68910.toInt(), // amber
        0xFF6C3483.toInt(), // violet
        0xFF00707A.toInt(), // teal
        0xFF7F8C8D.toInt(), // grey
    )

    /** Paper colours: warm and cool whites, plus the greys people use for dark pages. */
    val PAPER_PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFFFFDF8.toInt(), // warm white, the default
        0xFFF6F3E9.toInt(), // cream
        0xFFEAF2E8.toInt(), // pale green
        0xFFE8EEF6.toInt(), // pale blue
        0xFF12161A.toInt(), // near-black, the dark default
        0xFF1E1B18.toInt(), // warm dark
        0xFF000000.toInt(),
    )

    /** Rule colours, from barely-there to strong. */
    val LINE_PRESETS = intArrayOf(
        0xFF5B7FD4.toInt(), // classic blue rule
        0xFF9AA7B8.toInt(), // grey
        0xFFB5C7A8.toInt(), // green
        0xFFD0A05A.toInt(), // sepia
        0xFFD05A5A.toInt(), // margin red
        0xFF000000.toInt(),
        0xFFFFFFFF.toInt(),
    )
}
