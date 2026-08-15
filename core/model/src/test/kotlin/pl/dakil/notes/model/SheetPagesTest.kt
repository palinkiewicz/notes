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

    /** One stroke running from the middle of page 1 to the middle of page 2. */
    private fun spanningSheet(): Sheet = Sheet(
        contentHeight = pageHeight * 0.5f,
        blocks = listOf(
            InkBlock(
                id = BlockId("b0"), z = 0, rect = Rect.ZERO,
                strokes = listOf(
                    Stroke(
                        ToolId.PEN, -1, 2f, BlendId.NORMAL,
                        floatArrayOf(50f, 50f),
                        floatArrayOf(pageHeight * 1.5f, pageHeight * 2.5f),
                    )
                ),
            ).withRecomputedBounds()
        ),
    ).withPages(3)

    @Test
    fun `duplicating a page copies only the part of a drawing that is on it`() {
        val result = spanningSheet().withPageDuplicated(1)
        val spans = result.inkLayers().single().strokes.map { it.ys.first() to it.ys.last() }

        assertEquals(4, result.pageCount())
        assertEquals(
            listOf(
                // The original page-1 half, still where it was.
                pageHeight * 1.5f to pageHeight * 2f,
                // Its copy on the new page 2 — and nothing of what was on page 2.
                pageHeight * 2.5f to pageHeight * 3f,
                // What was on page 2 has moved down to page 3 with it.
                pageHeight * 3f to pageHeight * 3.5f,
            ),
            spans.sortedBy { it.first },
        )
    }

    @Test
    fun `removing a page keeps the part of a drawing that was not on it`() {
        val result = spanningSheet().withPageRemoved(2)
        val spans = result.inkLayers().single().strokes.map { it.ys.first() to it.ys.last() }

        // Page 2's half is gone; page 1's half stays exactly where it was.
        assertEquals(listOf(pageHeight * 1.5f to pageHeight * 2f), spans)
    }

    @Test
    fun `moving a page down exchanges it with the one after`() {
        val result = drawingSheet().withPagesSwapped(1, 2)
        assertEquals(
            listOf(pageHeight + 100f, pageHeight * 2 + 100f),
            result.strokeYs().sorted(),
        )
        // Same positions, opposite order: what was on page 1 is now on page 2.
        val first = result.inkLayers().single().strokes.first { it.ys[0] < pageHeight * 2 }
        assertEquals(pageHeight + 100f, first.ys[0], 0.01f)
    }

    @Test
    fun `a swap leaves the page count alone`() {
        val sheet = drawingSheet()
        assertEquals(sheet.pageCount(), sheet.withPagesSwapped(1, 3).pageCount())
    }

    @Test
    fun `a swap is its own inverse`() {
        val sheet = drawingSheet()
        val there = sheet.withPagesSwapped(1, 2)
        val back = there.withPagesSwapped(1, 2)
        assertEquals(sheet.strokeYs().sorted(), back.strokeYs().sorted())
    }

    @Test
    fun `only the ends of the note lock a reorder arrow`() {
        // Unlike duplicate and remove, a swap changes no page's existence, so the only page that
        // cannot move up is the first and the only one that cannot move down is the last.
        val sheet = Sheet(contentHeight = pageHeight * 2.5f).withPages(5)
        val last = sheet.pageCount() - 1

        assertFalse(sheet.canMovePageUp(0))
        for (i in 1..last) assertTrue("page $i should move up", sheet.canMovePageUp(i))

        assertFalse(sheet.canMovePageDown(last))
        for (i in 0 until last) assertTrue("page $i should move down", sheet.canMovePageDown(i))
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
