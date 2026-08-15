package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many pages a sheet has.
 *
 * The rule being pinned down is the asymmetry: content can push the count *up* but never pull it
 * *down*. Both halves matter. Without the first, writing off the bottom of the last page would run
 * onto no paper; without the second, there would be no way to open a blank page to draw on, which
 * is the whole reason the field exists.
 */
class SheetPagesTest {

    private val pageHeight = PageSize.A4.height

    private fun inkAt(bottom: Float) = InkBlock(
        id = BlockId("b0"),
        z = 0,
        rect = Rect(0f, bottom - 10f, 100f, bottom),
        strokes = listOf(
            Stroke(
                ToolId.PEN, -1, 2f, BlendId.NORMAL,
                floatArrayOf(0f, 100f), floatArrayOf(bottom - 10f, bottom),
            )
        ),
    )

    @Test
    fun `a fresh sheet is one page`() {
        assertEquals(1, Sheet().pageCount())
    }

    @Test
    fun `an explicitly added page exists before anything is on it`() {
        val sheet = Sheet().withPages(3)
        assertEquals(3, sheet.pageCount())
        // This is the case the old derivation could not express at all.
        assertEquals(1, sheet.contentPageCount())
    }

    @Test
    fun `text running past the last page still adds paper under it`() {
        val sheet = Sheet(contentHeight = pageHeight * 2.5f).withPages(1)
        assertEquals(3, sheet.pageCount())
    }

    @Test
    fun `ink alone can extend the sheet`() {
        val sheet = Sheet(blocks = listOf(inkAt(pageHeight * 1.2f)))
        assertEquals(2, sheet.pageCount())
    }

    @Test
    fun `the explicit count is a floor, not a ceiling`() {
        val sheet = Sheet(contentHeight = pageHeight * 3.2f).withPages(2)
        assertEquals("content wins when it reaches further", 4, sheet.pageCount())
    }

    @Test
    fun `a blank trailing page can be removed`() {
        val sheet = Sheet(contentHeight = pageHeight * 0.5f).withPages(3)
        assertTrue(sheet.canRemoveLastPage())
    }

    @Test
    fun `a page with content on it cannot be removed`() {
        // Decrementing a counter is not a gesture that means "throw this away", so it must not be
        // able to. The user has to clear the page first.
        val sheet = Sheet(contentHeight = pageHeight * 2.4f).withPages(3)
        assertFalse(sheet.canRemoveLastPage())
    }

    @Test
    fun `the last remaining page can never be removed`() {
        assertFalse(Sheet().canRemoveLastPage())
        assertFalse(Sheet().withPages(1).canRemoveLastPage())
    }

    @Test
    fun `page count can never fall below one`() {
        assertEquals(1, Sheet().withPages(0).pageCount())
        assertEquals(1, Sheet().withPages(-5).pageCount())
    }

    @Test
    fun `strip height follows the page count`() {
        assertEquals(pageHeight * 4f, Sheet().withPages(4).stripHeight(), 0.01f)
    }

    // ---- Duplicate and remove ---------------------------------------------------------------------

    /** Text on page one only, ink on pages two and three, four pages total. */
    private fun drawingSheet(): Sheet = Sheet(
        contentHeight = pageHeight * 0.5f,
        blocks = listOf(
            InkBlock(
                id = BlockId("b0"), z = 0, rect = Rect.ZERO,
                strokes = listOf(strokeOnPage(1), strokeOnPage(2)),
            ).withRecomputedBounds()
        ),
    ).withPages(4)

    private fun strokeOnPage(page: Int): Stroke {
        val y = page * pageHeight + 100f
        return Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            floatArrayOf(50f, 150f), floatArrayOf(y, y + 20f),
        )
    }

    private fun Sheet.strokeYs(): List<Float> =
        inkLayers().single().strokes.map { it.ys[0] }

    @Test
    fun `a page the text flows through cannot be duplicated or removed`() {
        // Ink is anchored to the paper and text is not, so inserting paper under flowing text would
        // slide the ink out from under the words it was written against.
        val sheet = Sheet(contentHeight = pageHeight * 2.5f).withPages(4)
        assertFalse(sheet.canEditPage(0))
        assertFalse(sheet.canEditPage(2))
        assertTrue(sheet.canEditPage(3))
    }

    @Test
    fun `duplicating a page copies its ink onto the page after it`() {
        val result = drawingSheet().withPageDuplicated(1)

        assertEquals(5, result.pageCount())
        // The original stays on page 1, a copy lands on page 2, and what was on page 2 moves to 3.
        assertEquals(
            listOf(pageHeight + 100f, pageHeight * 2 + 100f, pageHeight * 3 + 100f),
            result.strokeYs().sorted(),
        )
    }

    @Test
    fun `removing a page deletes its ink and closes the gap`() {
        val result = drawingSheet().withPageRemoved(1)

        assertEquals(3, result.pageCount())
        // Page 1's stroke is gone; page 2's has slid up into its place.
        assertEquals(listOf(pageHeight + 100f), result.strokeYs())
    }

    @Test
    fun `removing the last page leaves the earlier ones untouched`() {
        val result = drawingSheet().withPageRemoved(3)

        assertEquals(3, result.pageCount())
        assertEquals(
            listOf(pageHeight + 100f, pageHeight * 2 + 100f),
            result.strokeYs().sorted(),
        )
    }

    @Test
    fun `a refused operation changes nothing at all`() {
        val sheet = Sheet(contentHeight = pageHeight * 2.5f).withPages(3)
        assertEquals(sheet, sheet.withPageDuplicated(0))
        assertEquals(sheet, sheet.withPageRemoved(1))
    }

    @Test
    fun `layer bounds are re-derived after a page operation`() {
        // A stale rect is not cosmetic: inkBottom feeds the page count, so a layer still claiming
        // to reach page three would hold an empty page open that nothing is drawn on.
        val before = drawingSheet()
        assertTrue(before.inkLayers().single().rect.bottom > pageHeight * 2)

        val layer = before.withPageRemoved(2).inkLayers().single()
        assertTrue(
            "the layer should no longer claim to reach the page its ink was removed from",
            layer.rect.bottom < pageHeight * 2,
        )
        assertEquals(2, before.withPageRemoved(2).contentPageCount())
    }
}
