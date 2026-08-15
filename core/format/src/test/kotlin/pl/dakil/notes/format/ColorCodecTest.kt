package pl.dakil.notes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.ColorCodec
import kotlin.math.abs

class ColorCodecTest {

    @Test
    fun `hex round-trips`() {
        for (color in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x80FFE14D.toInt(), 0x00123456)) {
            assertEquals(color, ColorCodec.parse(ColorCodec.toHex(color)))
        }
    }

    @Test
    fun `hex is fixed width and upper case`() {
        // The format writes these into the file; unstable formatting would churn diffs.
        assertEquals("#FF0A0B0C", ColorCodec.toHex(0xFF0A0B0C.toInt()))
        assertEquals("#0A0B0C", ColorCodec.toHex(0xFF0A0B0C.toInt(), includeAlpha = false))
        assertEquals("#00000000", ColorCodec.toHex(0))
    }

    @Test
    fun `parsing accepts the forms people type`() {
        assertEquals(0xFFFF0000.toInt(), ColorCodec.parse("#F00"))
        assertEquals(0xFFFF0000.toInt(), ColorCodec.parse("FF0000"))
        assertEquals(0xFFFF0000.toInt(), ColorCodec.parse("  #ff0000  "))
        assertEquals(0x80FF0000.toInt(), ColorCodec.parse("#80FF0000"))
        // #RGBA: alpha last, matching CSS.
        assertEquals(0x88FF0000.toInt(), ColorCodec.parse("#F008"))
    }

    @Test
    fun `parsing rejects nonsense without throwing`() {
        // This backs a text field typed character by character; most states are incomplete.
        for (bad in listOf("", "#", "#12", "#12345", "zzzzzz", "#GGGGGG", "#1234567890")) {
            assertNull("expected null for '$bad'", ColorCodec.parse(bad))
        }
    }

    @Test
    fun `hsv round-trips within a rounding step`() {
        val colors = listOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF3F5BA9.toInt(), 0xFFFFFDF8.toInt(), 0xFF12161A.toInt(),
            0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        )
        for (color in colors) {
            val hsv = ColorCodec.toHsv(color)
            val back = ColorCodec.fromHsv(hsv[0], hsv[1], hsv[2], ColorCodec.alpha(color))
            for (shift in listOf(24, 16, 8, 0)) {
                val a = (color ushr shift) and 0xFF
                val b = (back ushr shift) and 0xFF
                assertTrue("channel drift on ${ColorCodec.toHex(color)}", abs(a - b) <= 1)
            }
        }
    }

    @Test
    fun `greys have zero saturation and achromatic hue`() {
        val hsv = ColorCodec.toHsv(0xFF808080.toInt())
        assertEquals(0f, hsv[1], 1e-4f)
        assertEquals(0f, hsv[0], 1e-4f)
    }

    @Test
    fun `hue wraps rather than clamping`() {
        assertEquals(ColorCodec.fromHsv(0f, 1f, 1f), ColorCodec.fromHsv(360f, 1f, 1f))
        assertEquals(ColorCodec.fromHsv(350f, 1f, 1f), ColorCodec.fromHsv(-10f, 1f, 1f))
    }

    @Test
    fun `luminance separates colours that need a dark mark from those that need a light one`() {
        // This decides the check-mark colour on a swatch; getting it wrong makes the mark vanish.
        assertTrue(ColorCodec.luminance(0xFFFFFFFF.toInt()) > 0.9f)
        assertTrue(ColorCodec.luminance(0xFF000000.toInt()) < 0.1f)
        assertTrue(ColorCodec.luminance(0xFFFFFF00.toInt()) > 0.55f) // yellow needs a dark mark
        assertTrue(ColorCodec.luminance(0xFF0000FF.toInt()) < 0.55f) // blue needs a light one
    }

    @Test
    fun `alpha helpers do not disturb the colour channels`() {
        val color = 0xFF3F5BA9.toInt()
        assertEquals(0x803F5BA9.toInt(), ColorCodec.withAlpha(color, 0x80))
        assertEquals(0x80, ColorCodec.alpha(ColorCodec.withAlpha(color, 0x80)))
        assertEquals(color and 0x00FFFFFF, ColorCodec.withAlpha(color, 0x80) and 0x00FFFFFF)
    }

    @Test
    fun `presets are opaque and distinct`() {
        for (set in listOf(ColorCodec.INK_PRESETS, ColorCodec.PAPER_PRESETS, ColorCodec.LINE_PRESETS)) {
            assertEquals(set.size, set.toSet().size)
            for (color in set) assertEquals(0xFF, ColorCodec.alpha(color))
        }
    }
}
