package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ColorPaletteTest {

    private val green = 0xFF2E7D32.toInt()
    private val pink = 0xFFE91E63.toInt()
    private val wash = 0x662E7D32 // the same green at 40%

    @Test
    fun `a saved colour goes on the end and saving it again changes nothing`() {
        val once = ColorPalette.save(listOf(pink), green)
        assertEquals(listOf(pink, green), once)
        assertSame(once, ColorPalette.save(once, green))
    }

    @Test
    fun `a full palette refuses a new colour rather than dropping an old one`() {
        // Silent eviction is exactly what recents do, and exactly why saved colours exist.
        val full = List(ColorPalette.MAX_SAVED) { 0xFF000000.toInt() or it }
        assertSame(full, ColorPalette.save(full, green))
    }

    @Test
    fun `a translucent mix is a different saved colour from its opaque twin`() {
        assertEquals(listOf(green, wash), ColorPalette.save(listOf(green), wash))
        assertEquals(listOf(wash), ColorPalette.remove(listOf(green, wash), green))
    }

    @Test
    fun `moving a colour shifts the ones in between and clamps at the ends`() {
        val list = listOf(1, 2, 3, 4)
        assertEquals(listOf(2, 3, 1, 4), ColorPalette.move(list, 0, 2))
        assertEquals(listOf(4, 1, 2, 3), ColorPalette.move(list, 3, -5))
        assertEquals(list, ColorPalette.move(list, 9, 0))
    }

    @Test
    fun `recents skip colours already saved, so a slot is never spent on one`() {
        val recents = listOf(pink)
        assertSame(recents, ColorPalette.recordRecent(recents, green, saved = listOf(green), presets = IntArray(0)))
    }

    @Test
    fun `recents skip a preset at any opacity, since tapping a preset keeps the tool's alpha`() {
        val preset = ColorCodec.INK_PRESETS[1]
        val faint = ColorCodec.withAlpha(preset, 80)
        val recents = listOf(pink)
        assertSame(recents, ColorPalette.recordRecent(recents, faint, emptyList(), ColorCodec.INK_PRESETS))
    }

    @Test
    fun `an unsaved mix goes to the front of recents, de-duplicated and capped`() {
        val recents = List(ColorPalette.MAX_RECENT) { 0xFF000000.toInt() or (it + 1) }
        val next = ColorPalette.recordRecent(recents + green, green, emptyList(), IntArray(0))
        assertEquals(green, next.first())
        assertEquals(ColorPalette.MAX_RECENT, next.size)
        assertEquals(1, next.count { it == green })
    }

    @Test
    fun `a palette survives being written and read back, white and transparency included`() {
        // White is all ones and reads back as -1: where a signed/unsigned slip would show.
        val colors = listOf(0xFFFFFFFF.toInt(), wash, 0x00000000, green)
        assertEquals(colors, ColorPalette.decode(ColorPalette.encode(colors), cap = 24))
    }

    @Test
    fun `recents stored as decimal integers by earlier builds still read`() {
        assertEquals(listOf(-1, green), ColorPalette.decode("-1,$green", cap = 12))
    }

    @Test
    fun `an unreadable entry costs that colour and not the rest`() {
        assertEquals(listOf(green), ColorPalette.decode("#zz, ,${ColorCodec.toHex(green)},nonsense", cap = 24))
        assertEquals(emptyList<Int>(), ColorPalette.decode(null, cap = 24))
    }
}
