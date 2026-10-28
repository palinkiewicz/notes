package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterScaleTest {

    private val a4Width = 595f
    private val a4Height = 842f

    private fun a4(options: ExportOptions) = RasterScale.of(a4Width, a4Height, options)

    @Test
    fun `the default size is the one every export had before there was a choice`() {
        // 150 dpi was the only PNG size; someone who never opens the new options gets the same file.
        val raster = a4(ExportOptions())
        assertEquals(1239, raster.widthPx)
        assertEquals(1754, raster.heightPx)
        assertFalse(raster.capped)
    }

    @Test
    fun `each preset is its dpi against the page`() {
        assertEquals(793, a4(ExportOptions(rasterSize = RasterSize.SMALL)).widthPx)
        assertEquals(2479, a4(ExportOptions(rasterSize = RasterSize.LARGE)).widthPx)
    }

    @Test
    fun `the largest preset still fits an A4 page whole`() {
        val raster = a4(ExportOptions(rasterSize = RasterSize.EXTRA_LARGE))
        assertFalse(raster.capped)
        assertEquals(4958, raster.widthPx)
    }

    @Test
    fun `a custom width sets the height by the page's proportions`() {
        val raster = a4(ExportOptions(rasterSize = RasterSize.CUSTOM, customWidthPx = 3000))
        assertEquals(3000, raster.widthPx)
        assertEquals(4245, raster.heightPx)
    }

    @Test
    fun `a page too large to hold is shrunk on both sides alike and says so`() {
        // A tall custom page at the widest width would be hundreds of megabytes of bitmap.
        val raster = RasterScale.of(
            widthPt = 595f,
            heightPt = 8420f,
            options = ExportOptions(rasterSize = RasterSize.CUSTOM, customWidthPx = ExportOptions.MAX_WIDTH_PX),
        )
        assertTrue(raster.capped)
        assertTrue(raster.widthPx.toLong() * raster.heightPx <= RasterScale.MAX_PIXELS)
        assertEquals(10.0, raster.heightPx.toDouble() / raster.widthPx * 595 / 842, 0.01)
    }

    @Test
    fun `a typed width is read only when it is a whole number in range`() {
        assertEquals(3000, RasterScale.parseWidth(" 3000 "))
        assertNull(RasterScale.parseWidth(""))
        assertNull(RasterScale.parseWidth("3000px"))
        assertNull(RasterScale.parseWidth("12.5"))
        assertNull(RasterScale.parseWidth("50"))
        assertNull(RasterScale.parseWidth("20000"))
    }
}
