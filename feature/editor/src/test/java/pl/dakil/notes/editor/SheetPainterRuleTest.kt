package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.canvas.SheetPainter

/**
 * How thick the paper's rules are drawn.
 *
 * The bug this replaces was not a wrong pixel but a wrong impression: the rules were a fixed width
 * on the glass, so zooming out closed the spacing while the ink stayed put, and a grid eventually
 * covered the sheet — you zoomed out of a note and got a grey card.
 */
class SheetPainterRuleTest {

    /** Points to pixels on a dense phone: 160/72 dp per point, at 2.75x. */
    private val ptToPx = 160f / 72f * 2.75f

    /** What the rule actually measures on screen: its document width times the zoom. */
    private fun onGlass(zoom: Float) = SheetPainter.ruleWidthPx(ptToPx, zoom) * zoom

    @Test
    fun `a rule is a width on the paper, so zooming in fattens it`() {
        val at1 = SheetPainter.ruleWidthPx(ptToPx, 1f)
        val at4 = SheetPainter.ruleWidthPx(ptToPx, 4f)

        // Equal in document pixels is exactly what "anchored to the paper" means: the strip is
        // scaled afterwards, so an unchanged document width is a rule that grows with the page.
        assertEquals(at1, at4, 0.0001f)
        assertEquals(1f, SheetPainter.ruleCoverage(ptToPx, 4f), 0.0001f)
    }

    @Test
    fun `zooming out thins the rule rather than leaving it a fixed pixel`() {
        val near = onGlass(1f)
        val far = onGlass(0.25f)

        assertTrue("a rule should measure less on screen the further out you zoom", far < near)
    }

    /**
     * The heart of it. A pixel is the thinnest mark there is, so past that point the thinning has
     * to become fading — otherwise the rules keep their full weight while their spacing collapses.
     */
    @Test
    fun `a sub-pixel rule is drawn as one pixel at proportionally less alpha`() {
        val zoom = 0.1f
        val wanted = ptToPx * 0.5f * zoom
        assertTrue("the fixture has to be in the sub-pixel range to be testing anything", wanted < 1f)

        assertEquals("pinned at one device pixel", 1f, onGlass(zoom), 0.0001f)
        assertEquals("the missing width moved into the alpha", wanted, SheetPainter.ruleCoverage(ptToPx, zoom), 0.0001f)
    }

    @Test
    fun `the ink-to-paper ratio holds across the zoom range`() {
        // Rules 24pt apart, the default. What must stay constant is the share of the sheet they
        // cover — that share rising as you zoom out is precisely what turned the paper grey.
        val spacingPx = 24f * ptToPx
        fun inkShare(zoom: Float) =
            onGlass(zoom) * SheetPainter.ruleCoverage(ptToPx, zoom) / (spacingPx * zoom)

        val reference = inkShare(1f)
        for (zoom in listOf(0.1f, 0.25f, 0.5f, 2f, 8f)) {
            assertEquals("ink share at ${zoom}x", reference, inkShare(zoom), 0.0001f)
        }
    }

    @Test
    fun `full-strength rules never fall below a pixel on screen`() {
        // Fading is for rules too thin to draw; a rule at full alpha still has to be visible.
        for (zoom in listOf(0.05f, 0.3f, 1f, 8f)) {
            if (SheetPainter.ruleCoverage(ptToPx, zoom) == 1f) {
                assertTrue("at ${zoom}x", onGlass(zoom) >= 1f)
            }
        }
    }
}
