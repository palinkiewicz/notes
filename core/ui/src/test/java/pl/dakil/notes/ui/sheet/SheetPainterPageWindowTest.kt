package pl.dakil.notes.ui.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.PageFormat

/**
 * Which pages a pass over the window paints.
 *
 * The sheet only stays smooth to scroll because this answer holds still while the view moves: the
 * paper and the ink read it inside their draw lambdas, and a pan that does not change it repaints
 * nothing. Paint by the window's exact edges instead and every frame of a pan re-records every
 * stroke on screen, which is the stutter this replaced.
 */
class SheetPainterPageWindowTest {

    private val format = PageFormat.DEFAULT
    private val ptToPx = 1f
    private val pageHeight = format.height * ptToPx
    private val stride = SheetPainter.pageStridePx(format, ptToPx, paged = true)

    private fun window(top: Float, bottom: Float, pageCount: Int = 10, paged: Boolean = true, slack: Int = 1) =
        SheetPainter.pageWindow(top, bottom, format, pageCount, ptToPx, paged, slack)

    @Test
    fun `a pan within one page leaves the window unchanged`() {
        // The property everything else rests on. Two views a third of a page apart, both inside
        // page three, must ask for the same pages, or panning repaints on every frame.
        val before = window(3 * stride + 10f, 3 * stride + 400f)
        val after = window(3 * stride + 300f, 3 * stride + 700f)
        assertEquals(before, after)
        assertEquals(2..4, before)
    }

    @Test
    fun `the window reaches a page beyond each edge so it is drawn before it slides into view`() {
        assertEquals(1..5, window(2 * stride + 100f, 4 * stride + 100f))
    }

    @Test
    fun `the window is clamped to the pages the document has`() {
        assertEquals(0..1, window(10f, 200f))
        assertEquals(8..9, window(9 * stride + 10f, 9 * stride + 200f))
    }

    @Test
    fun `a window over the gap below a page counts that page and the one after the gap`() {
        // The gap is desk, not paper, but the window still has to be asked for the pages either
        // side of it: both are on screen.
        val gapTop = 4 * stride + pageHeight + 5f
        val range = window(gapTop, gapTop + 10f, slack = 0)
        assertEquals(4..4, range)
        assertEquals(4..5, window(gapTop, 5 * stride + 10f, slack = 0))
    }

    @Test
    fun `continuous view has no gaps, so pages follow on at the page height`() {
        assertEquals(3..3, window(3 * pageHeight + 1f, 4 * pageHeight - 1f, paged = false, slack = 0))
    }

    @Test
    fun `an unbounded band asks for every page rather than overflowing`() {
        // The ink overlay's default band is the whole strip, ±Float.MAX_VALUE. Converted to Int
        // before the slack is applied, that would wrap round and ask for no pages at all.
        assertEquals(0..9, window(-Float.MAX_VALUE, Float.MAX_VALUE))
    }

    @Test
    fun `a band entirely above the paper asks for nothing`() {
        assertTrue(window(-5000f, -3000f).isEmpty())
    }

    @Test
    fun `a document with no pages asks for nothing`() {
        assertTrue(window(0f, 1000f, pageCount = 0).isEmpty())
    }
}
