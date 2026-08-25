package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour maths behind the export colour presets: HSL round-trips and the two inversions built
 * on top of them. This is the one part of the export feature that is pure enough to unit-test
 * directly, so it carries the worked examples from the export spec rather than only abstract cases.
 */
class ColorCodecTest {

    private fun assertCloseColor(expected: Int, actual: Int, tolerance: Int = 2) {
        assertTrue(
            "expected ${ColorCodec.toHex(expected)} but was ${ColorCodec.toHex(actual)}",
            kotlin.math.abs(ColorCodec.red(expected) - ColorCodec.red(actual)) <= tolerance &&
                kotlin.math.abs(ColorCodec.green(expected) - ColorCodec.green(actual)) <= tolerance &&
                kotlin.math.abs(ColorCodec.blue(expected) - ColorCodec.blue(actual)) <= tolerance,
        )
    }

    @Test
    fun `toHsl and fromHsl round trip every preset colour`() {
        for (argb in ColorCodec.INK_PRESETS + ColorCodec.PAPER_PRESETS + ColorCodec.LINE_PRESETS) {
            val hsl = ColorCodec.toHsl(argb)
            val back = ColorCodec.fromHsl(hsl[0], hsl[1], hsl[2], ColorCodec.alpha(argb))
            assertCloseColor(argb, back)
        }
    }

    @Test
    fun `pure gray has no hue or saturation`() {
        val gray = ColorCodec.pack(255, 128, 128, 128)
        val hsl = ColorCodec.toHsl(gray)
        assertEquals(0f, hsl[1], 1e-4f)
        assertEquals(128 / 255f, hsl[2], 0.01f)
    }

    @Test
    fun `invertRgb round trips and preserves alpha`() {
        val translucentBlue = ColorCodec.pack(128, 30, 60, 200)
        val inverted = ColorCodec.invertRgb(translucentBlue)
        assertEquals(128, ColorCodec.alpha(inverted))
        assertEquals(225, ColorCodec.red(inverted))
        assertEquals(195, ColorCodec.green(inverted))
        assertEquals(55, ColorCodec.blue(inverted))
        assertEquals(translucentBlue, ColorCodec.invertRgb(inverted))
    }

    @Test
    fun `invertRgb turns black into white and back`() {
        assertEquals(ColorCodec.pack(255, 255, 255, 255), ColorCodec.invertRgb(ColorCodec.pack(255, 0, 0, 0)))
        assertEquals(ColorCodec.pack(255, 0, 0, 0), ColorCodec.invertRgb(ColorCodec.pack(255, 255, 255, 255)))
    }

    @Test
    fun `invertLightness turns black into white and back`() {
        val black = ColorCodec.pack(255, 0, 0, 0)
        val white = ColorCodec.pack(255, 255, 255, 255)
        assertCloseColor(white, ColorCodec.invertLightness(black))
        assertCloseColor(black, ColorCodec.invertLightness(white))
    }

    @Test
    fun `invertLightness leaves pure gray unchanged`() {
        val gray = ColorCodec.pack(255, 128, 128, 128)
        assertCloseColor(gray, ColorCodec.invertLightness(gray))
    }

    @Test
    fun `invertLightness turns light gray into dark gray`() {
        val light = ColorCodec.pack(255, 220, 220, 220)
        val dark = ColorCodec.pack(255, 35, 35, 35)
        assertCloseColor(dark, ColorCodec.invertLightness(light))
    }

    @Test
    fun `invertLightness turns light blue into dark blue with the same hue and saturation`() {
        val lightBlue = ColorCodec.pack(255, 173, 216, 230)
        val inverted = ColorCodec.invertLightness(lightBlue)

        val originalHsl = ColorCodec.toHsl(lightBlue)
        val invertedHsl = ColorCodec.toHsl(inverted)

        assertEquals(originalHsl[0], invertedHsl[0], 1f)
        assertEquals(originalHsl[1], invertedHsl[1], 0.02f)
        assertTrue(invertedHsl[2] < originalHsl[2])
    }

    @Test
    fun `invertLightness turns dark orange into light orange`() {
        val darkOrange = ColorCodec.pack(255, 120, 60, 0)
        val inverted = ColorCodec.invertLightness(darkOrange)

        val originalHsl = ColorCodec.toHsl(darkOrange)
        val invertedHsl = ColorCodec.toHsl(inverted)

        assertEquals(originalHsl[0], invertedHsl[0], 1f)
        assertEquals(originalHsl[1], invertedHsl[1], 0.02f)
        assertTrue(invertedHsl[2] > originalHsl[2])
    }

    @Test
    fun `preset apply matches the individual transforms`() {
        val color = ColorCodec.pack(255, 30, 60, 200)
        assertEquals(color, ExportColorPreset.AS_IS.apply(color))
        assertEquals(ColorCodec.invertRgb(color), ExportColorPreset.INVERSION.apply(color))
        assertEquals(ColorCodec.invertLightness(color), ExportColorPreset.INTELLIGENT.apply(color))
    }
}
