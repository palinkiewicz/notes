package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF pages as the backdrop of sheet pages: where they are fitted, and that every page operation
 * leaves each one on the page it belongs to.
 */
class SheetPdfTest {

    // A4 in exact points, which no float can hold: the case where `k·H / H` stops being `k`.
    private val a4 = PageFormat(size = PageSize.Custom(595.276f, 841.89f))
    private val data = InMemoryMedia(ByteArray(8))

    private fun pdfSheet(pages: Int, format: PageFormat = a4): Sheet =
        Sheet(format = format, blocks = listOf(InkBlock(BlockId("ink"), 0, Rect.ZERO)))
            .withPdfPagesInserted(0, "media/x.pdf", data, List(pages) { PdfSourcePage(it, format.width, format.height) })
            .let { it.copy(pages = pages) }

    private fun Sheet.pdf(): List<PdfPageBlock> = blocks.filterIsInstance<PdfPageBlock>()

    /** Which PDF page each sheet page shows, top to bottom. */
    private fun Sheet.order(): List<Int> = pdf().sortedBy { pageOf(it) }.map { it.page }

    private fun stroke(top: Float, bottom: Float) = Stroke(
        ToolId.PEN, -1, 2f, BlendId.NORMAL, floatArrayOf(10f, 10f), floatArrayOf(top, bottom),
    )

    @Test
    fun `a page the same shape as the paper fills it`() {
        assertEquals(Rect(0f, 0f, 595.276f, 841.89f), PdfFit.rect(0, a4, 595.276f, 841.89f))
    }

    @Test
    fun `a landscape page on portrait paper is letterboxed in the middle`() {
        val r = PdfFit.rect(0, a4, 841.89f, 595.276f)
        assertEquals(595.276f, r.width, 1e-3f)
        assertEquals(595.276f * 595.276f / 841.89f, r.height, 1e-2f)
        assertEquals(841.89f - r.bottom, r.top, 1e-2f)
    }

    @Test
    fun `a narrow page is pillarboxed in the middle`() {
        val r = PdfFit.rect(0, a4, 300f, 841.89f)
        assertEquals(841.89f, r.height, 1e-3f)
        assertEquals(595.276f - r.right, r.left, 1e-3f)
    }

    @Test
    fun `a page that differs from the paper by rounding alone is still full bleed`() {
        // The same A4 written as 595 × 842 by another tool should not come back with a sliver of
        // letterbox at the sides.
        val r = PdfFit.rect(0, PageFormat(size = PageSize.Custom(595f, 842f)), 595.276f, 841.89f)
        assertEquals(0f, r.left, 0f)
    }

    @Test
    fun `a fitted page is on the page it was fitted to, by every rule that asks, far down a long note`() {
        // An older build judges by the top; page counting judges by the bottom. Both have to agree
        // with the page index for every page of a long PDF, not only the first.
        val sheet = Sheet(format = a4)
        for (k in 0 until 500) {
            val r = PdfFit.rect(k, a4, a4.width, a4.height)
            assertEquals("top of page $k", k, sheet.pageAt(r.top))
            val count = sheet.copy(blocks = listOf(PdfPageBlock(BlockId("p"), 0, r, src = "s", page = 0, data = data)))
                .contentPageCount()
            assertEquals("pages reached by page $k", k + 1, count)
        }
    }

    @Test
    fun `a PDF note has exactly as many pages as the PDF`() {
        assertEquals(40, pdfSheet(40).pageCount())
    }

    @Test
    fun `inserting PDF pages in the middle moves later pages and their ink down`() {
        val sheet = pdfSheet(3).let { s ->
            val ink = s.inkLayers().single().copy(strokes = listOf(stroke(2 * a4.height + 100f, 2 * a4.height + 200f)))
            s.withBlock(ink)
        }
        val inserted = sheet.withPdfPagesInserted(
            1, "media/y.pdf", data, listOf(PdfSourcePage(4, 400f, 300f), PdfSourcePage(7, 400f, 300f)),
        )
        assertEquals(5, inserted.pageCount())
        assertEquals(listOf(0, 4, 7, 1, 2), inserted.order())
        val moved = inserted.inkLayers().single().strokes.single()
        assertEquals(4 * a4.height + 100f, moved.ys.first(), 1e-2f)
    }

    @Test
    fun `inserting at the end appends, and at the start pushes everything down`() {
        val end = pdfSheet(2).withPdfPagesInserted(2, "media/y.pdf", data, listOf(PdfSourcePage(9, 100f, 100f)))
        assertEquals(listOf(0, 1, 9), end.order())
        val start = pdfSheet(2).withPdfPagesInserted(0, "media/y.pdf", data, listOf(PdfSourcePage(9, 100f, 100f)))
        assertEquals(listOf(9, 0, 1), start.order())
    }

    @Test
    fun `a stroke across the insertion point is cut there`() {
        val sheet = pdfSheet(2).let { s ->
            s.withBlock(s.inkLayers().single().copy(strokes = listOf(stroke(a4.height - 50f, a4.height + 50f))))
        }
        val inserted = sheet.withPagesInserted(1, 1)
        val pieces = inserted.inkLayers().single().strokes
        assertEquals(2, pieces.size)
        assertTrue(pieces.any { it.ys.max() <= a4.height + 1e-3f })
        assertTrue(pieces.any { it.ys.min() >= 2 * a4.height - 1e-3f })
    }

    @Test
    fun `blank pages added after a PDF are kept when more are inserted`() {
        val sheet = pdfSheet(2).withPages(4)
        assertEquals(6, sheet.withPagesInserted(1, 2).pageCount())
    }

    @Test
    fun `page operations keep every PDF page on its own page, with no drift after hundreds of swaps`() {
        var sheet = pdfSheet(6)
        repeat(200) { i -> sheet = sheet.withPagesSwapped(i % 5, i % 5 + 1) }
        for (block in sheet.pdf()) {
            val k = sheet.pageOf(block)
            assertEquals(PdfFit.rect(k, a4, a4.width, a4.height), block.rect)
        }
        assertEquals(6, sheet.pdf().map { sheet.pageOf(it) }.toSet().size)
    }

    @Test
    fun `duplicating and removing a page carry its PDF page with it`() {
        val dup = pdfSheet(3).withPageDuplicated(1)
        assertEquals(listOf(0, 1, 1, 2), dup.order())
        assertEquals(4, dup.pdf().map { it.id }.toSet().size)
        assertEquals(listOf(0, 2), pdfSheet(3).withPageRemoved(1).order())
    }

    @Test
    fun `changing the paper refits each PDF page to the page it was on`() {
        val sheet = pdfSheet(3)
        val letter = PageFormat(size = PageSize.Custom(612f, 792f))
        val refit = sheet.withFormatRefitted(letter)
        assertEquals(listOf(0, 1, 2), refit.order())
        for (block in refit.pdf()) assertTrue(block.rect.height <= 792f && block.rect.width <= 612f + 1e-3f)
    }

    @Test
    fun `the pages with a PDF backdrop are exactly those that have one`() {
        assertEquals(setOf(0, 2), pdfSheet(2).withPagesInserted(1, 1).pdfPageIndices())
    }
}
