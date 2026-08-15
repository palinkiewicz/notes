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
}
