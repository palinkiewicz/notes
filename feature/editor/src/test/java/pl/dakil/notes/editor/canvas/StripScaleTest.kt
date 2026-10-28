package pl.dakil.notes.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.ui.sheet.SheetPainter

class StripScaleTest {

    // A point at 160/72 dp on a 420 dpi phone: the scale the editor measures at.
    private val phone = 160f / 72f * 2.625f

    @Test
    fun `a note short enough to measure is measured exactly as it always was`() {
        assertEquals(1f, StripScale.of(PageFormat(), pageCount = 20, basePtToPx = phone, paged = true))
    }

    @Test
    fun `a three-hundred page PDF is measured small enough for a layout to hold it`() {
        // The case that crashed: 300 A4 pages is 1.5 million pixels of strip at the usual scale.
        val k = StripScale.of(PageFormat(), pageCount = 300, basePtToPx = phone, paged = true)
        assertTrue(k > 1f)
        val height = SheetPainter.stripHeightPx(PageFormat(), 300, phone / k, paged = true)
        assertTrue(height <= StripScale.MAX_HEIGHT_PX)
    }

    @Test
    fun `the factor is a power of two, so it changes only at a few page counts`() {
        val k = StripScale.of(PageFormat(), pageCount = 120, basePtToPx = phone, paged = false)
        assertEquals(0, (k.toInt() and (k.toInt() - 1)))
        assertEquals(k, StripScale.of(PageFormat(), pageCount = 121, basePtToPx = phone, paged = false))
    }

    @Test
    fun `a page too wide for a tall layout is measured smaller too`() {
        val wide = PageFormat(size = PageSize.Custom(5000f, 400f))
        val k = StripScale.of(wide, pageCount = 1, basePtToPx = phone, paged = false)
        assertTrue(5000f * phone / k <= StripScale.MAX_WIDTH_PX)
    }
}
