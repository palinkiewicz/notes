package pl.dakil.notes.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The unit lengths on the paper are shown and typed in.
 *
 * The document itself is always points (1/72 inch) — this is a presentation choice, so switching it
 * never rewrites a note. Paper is a physical object, so "7 mm ruled" and "a 2 cm margin" have to be
 * things a user can ask for directly rather than approximate on a dimensionless slider.
 */
enum class MeasurementUnit(
    /** Stable token used in preferences, decoupled from the enum name. */
    val key: String,
    val suffix: String,
    /** How many of this unit fit in one point. */
    val perPoint: Float,
    /** Digits after the separator — the precision a value is snapped and shown at. */
    val decimals: Int,
) {
    // A centimetre stops at one decimal on purpose. The document stores whole points, so A4's
    // 595 pt is 2.0993 cm; asking for two decimals shows that rounding error back as "20,99 cm"
    // instead of the 21 cm the paper actually is. One decimal is also exactly a millimetre, which
    // is the precision every real rule spacing and margin is specified at anyway.
    CENTIMETRE("cm", "cm", 2.54f / 72f, 1),
    MILLIMETRE("mm", "mm", 25.4f / 72f, 1),
    INCH("in", "in", 1f / 72f, 2);

    fun fromPoints(points: Float): Float = points * perPoint

    fun toPoints(value: Float): Float = value / perPoint

    /**
     * The nearest point value that survives a display round-trip.
     *
     * Without it a slider drag leaves 19.843 pt behind a readout that says "0,7 cm", and reopening
     * the dialog would show a different number than the one just confirmed.
     */
    fun snapPoints(points: Float): Float {
        val step = 10f.pow(decimals)
        return toPoints((fromPoints(points) * step).roundToInt() / step)
    }

    /**
     * A human reading: `"2 cm"`, `"0,7 cm"`, `"7,5 mm"`.
     *
     * Trailing zeros are dropped — nobody writes a margin as "2,00 cm" — and the separator follows
     * the device locale, so the same value reads as `2,5` in Warsaw and `2.5` in London.
     */
    fun format(points: Float, withSuffix: Boolean = true): String {
        val text = trimZeros(String.format(Locale.getDefault(), "%.${decimals}f", fromPoints(points)))
        return if (withSuffix) "$text $suffix" else text
    }

    /**
     * Parses a typed length back to points.
     *
     * Both separators are accepted whatever the locale says: a keyboard's decimal key and the user's
     * habit do not always agree with it, and rejecting the "wrong" one is never the helpful answer.
     */
    fun parse(text: String): Float? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = cleaned.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return toPoints(value)
    }

    companion object {
        fun fromKey(key: String): MeasurementUnit =
            entries.firstOrNull { it.key == key } ?: CENTIMETRE

        private fun trimZeros(text: String): String {
            if ('.' !in text && ',' !in text) return text
            return text.trimEnd('0').trimEnd('.', ',')
        }
    }
}

/**
 * Which margin sides are tied together while editing.
 *
 * Derived from the values rather than stored: nothing new goes into `sheet.json`, and a fresh note —
 * whose four margins are all [PageMargins.DEFAULT] — reads back as [ALL] on its own.
 */
enum class MarginLink { ALL, AXES, EACH }

private fun near(a: Float, b: Float): Boolean = abs(a - b) < 0.01f

fun PageMargins.link(): MarginLink = when {
    near(top, bottom) && near(left, right) && near(top, left) -> MarginLink.ALL
    near(top, bottom) && near(left, right) -> MarginLink.AXES
    else -> MarginLink.EACH
}

/**
 * The margins as one line, using CSS shorthand.
 *
 * `"2 cm"` when all four agree, `"2 1,5 cm"` for vertical then horizontal, and
 * `"2 1,5 2 1,5 cm"` — top, right, bottom, left — when they are all independent. Borrowed rather
 * than invented: it is the notation anyone who has written a stylesheet already reads at a glance,
 * and it keeps the common case down to a single number.
 */
fun PageMargins.summary(unit: MeasurementUnit): String {
    val parts = when (link()) {
        MarginLink.ALL -> listOf(top)
        MarginLink.AXES -> listOf(top, left)
        MarginLink.EACH -> listOf(top, right, bottom, left)
    }
    return parts.joinToString(" ") { unit.format(it, withSuffix = false) } + " " + unit.suffix
}
