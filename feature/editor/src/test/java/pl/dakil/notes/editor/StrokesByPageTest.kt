package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.canvas.strokesByPage
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId

/**
 * Which page a committed stroke is painted on.
 *
 * The paged view draws one clipped pass per page, and this grouping is what it draws from. Getting
 * it wrong is the sort of thing that only shows up as ink missing from the bottom of a page, or a
 * stray dot at the top of the next one, so the boundary cases are pinned down here rather than by
 * scrolling around on a device.
 */
class StrokesByPageTest {

    private val pageHeight = 100f

    private fun stroke(top: Float, bottom: Float) = Stroke(
        ToolId.PEN, -1, 2f, BlendId.NORMAL,
        xs = floatArrayOf(0f, 10f),
        ys = floatArrayOf(top, bottom),
    )

    private fun layer(vararg strokes: Stroke, visible: Boolean = true) = InkBlock(
        id = BlockId("ink"),
        z = 0,
        rect = Rect(0f, 0f, 100f, 1000f),
        strokes = strokes.toList(),
        visible = visible,
    )

    @Test
    fun `a stroke well inside one page lands on that page alone`() {
        val s = stroke(120f, 180f)
        val pages = strokesByPage(listOf(layer(s)), pageCount = 3, pageHeight = pageHeight)

        assertEquals(emptyList<Stroke>(), pages[0])
        assertEquals(listOf(s), pages[1])
        assertEquals(emptyList<Stroke>(), pages[2])
    }

    @Test
    fun `a stroke drawn across a page break is painted on both sides of the join`() {
        // It is one continuous stroke in the document — the break is presentation — so each page's
        // clipped pass has to be given it in order to paint its own half.
        val s = stroke(80f, 130f)
        val pages = strokesByPage(listOf(layer(s)), pageCount = 3, pageHeight = pageHeight)

        assertEquals(listOf(s), pages[0])
        assertEquals(listOf(s), pages[1])
        assertEquals(emptyList<Stroke>(), pages[2])
    }

    @Test
    fun `a stroke ending exactly on a boundary stays off the page below`() {
        // This is the case the centreline rule exists for. Judged by the inflated bounds instead,
        // half of the stroke's round end cap would be painted at the very top of the next page as
        // a dot that belongs to nothing.
        val s = stroke(50f, 100f)
        val pages = strokesByPage(listOf(layer(s)), pageCount = 3, pageHeight = pageHeight)

        assertEquals(listOf(s), pages[0])
        assertEquals(emptyList<Stroke>(), pages[1])
    }

    @Test
    fun `a stroke starting exactly on a boundary belongs to the page it opens`() {
        val s = stroke(100f, 150f)
        val pages = strokesByPage(listOf(layer(s)), pageCount = 3, pageHeight = pageHeight)

        assertEquals(emptyList<Stroke>(), pages[0])
        assertEquals(listOf(s), pages[1])
    }

    @Test
    fun `a hidden layer contributes nothing`() {
        val s = stroke(10f, 20f)
        val pages = strokesByPage(
            listOf(layer(s, visible = false)), pageCount = 2, pageHeight = pageHeight,
        )
        assertTrue(pages.all { it.isEmpty() })
    }

    @Test
    fun `layers keep their paint order within a page`() {
        // Two layers' strokes are concatenated per page, and the lower layer has to stay underneath
        // the one above it — the grouping must not become a way to reorder the document.
        val under = stroke(10f, 20f)
        val over = stroke(30f, 40f)
        val lower = InkBlock(BlockId("a"), z = 0, rect = Rect.ZERO, strokes = listOf(under))
        val upper = InkBlock(BlockId("b"), z = 1, rect = Rect.ZERO, strokes = listOf(over))

        val pages = strokesByPage(listOf(lower, upper), pageCount = 1, pageHeight = pageHeight)

        assertEquals(2, pages[0].size)
        assertSame(under, pages[0][0])
        assertSame(over, pages[0][1])
    }

    @Test
    fun `a stroke hanging past the last page is not lost off the end of the buckets`() {
        // Pages can be removed while ink remains below them; walking off the end of the list would
        // be an index crash rather than a missing stroke.
        val s = stroke(180f, 260f)
        val pages = strokesByPage(listOf(layer(s)), pageCount = 2, pageHeight = pageHeight)

        assertEquals(listOf(s), pages[1])
        assertEquals(2, pages.size)
    }

    @Test
    fun `an excluded stroke is left out of every page it would have been on`() {
        // The current selection is kept out of these buckets because the overlay redraws it itself
        // under the live transform. Missing it from one bucket would paint a stroke being dragged
        // twice: once following the finger and once still lying where it started.
        val selected = stroke(80f, 120f)
        val other = stroke(10f, 20f)
        val pages = strokesByPage(
            listOf(layer(selected, other)),
            pageCount = 2,
            pageHeight = pageHeight,
            exclude = setOf(selected),
        )

        assertEquals(listOf(other), pages[0])
        assertTrue(pages[1].isEmpty())
    }

    @Test
    fun `a stroke above the first page is clamped onto it rather than dropped`() {
        // Negative document y is reachable by dragging a selection upwards; it must not index a
        // bucket that does not exist.
        val s = stroke(-40f, 20f)
        val pages = strokesByPage(listOf(layer(s)), pageCount = 2, pageHeight = pageHeight)

        assertEquals(listOf(s), pages[0])
    }
}
