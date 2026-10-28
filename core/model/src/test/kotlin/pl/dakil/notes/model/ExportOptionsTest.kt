package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOptionsTest {

    @Test
    fun `every choice survives being remembered`() {
        val options = ExportOptions(
            format = ExportFormat.JPEG,
            preset = ExportColorPreset.INTELLIGENT,
            rasterSize = RasterSize.CUSTOM,
            customWidthPx = 3000,
            pdfImages = PdfImageQuality.LOW,
            jpegQuality = JpegQuality.HIGH,
            transparent = true,
        )
        assertEquals(options, ExportOptions.decode(options.encode()))
    }

    @Test
    fun `nothing remembered, or something unreadable, opens the sheet on the defaults`() {
        // A corrupt preference must cost the user their last choice, never the export button.
        assertEquals(ExportOptions(), ExportOptions.decode(null))
        assertEquals(ExportOptions(), ExportOptions.decode(""))
        assertEquals(ExportOptions(), ExportOptions.decode("{not json"))
        assertEquals(ExportOptions(), ExportOptions.decode("[1, 2]"))
    }

    @Test
    fun `a value this build does not know keeps that one field's default and nothing else`() {
        // A later build may add a format; an older one reading its choice back should lose only that.
        val decoded = ExportOptions.decode("""{"format":"webp","size":"large","transparent":true}""")
        assertEquals(ExportFormat.PDF, decoded.format)
        assertEquals(RasterSize.LARGE, decoded.rasterSize)
        assertTrue(decoded.transparent)
    }

    @Test
    fun `a remembered custom width outside the range is pulled back into it`() {
        assertEquals(ExportOptions.MAX_WIDTH_PX, ExportOptions.decode("""{"customWidth":999999}""").customWidthPx)
        assertEquals(ExportOptions.MIN_WIDTH_PX, ExportOptions.decode("""{"customWidth":-4}""").customWidthPx)
    }

    @Test
    fun `only a PNG can leave its background out`() {
        // A JPEG has no alpha: "transparent" would come out black, so the switch is ignored there.
        assertTrue(ExportOptions(format = ExportFormat.PNG, transparent = true).transparentBackground)
        assertFalse(ExportOptions(format = ExportFormat.JPEG, transparent = true).transparentBackground)
        assertFalse(ExportOptions(format = ExportFormat.PDF, transparent = true).transparentBackground)
    }

    @Test
    fun `original quality decodes a picture at its full size`() {
        assertEquals(0, PdfImageQuality.ORIGINAL.decodeWidthPx(300f))
    }

    @Test
    fun `a shrinking quality decodes at its dpi against the printed width`() {
        // 144 pt is two inches.
        assertEquals(600, PdfImageQuality.HIGH.decodeWidthPx(144f))
        assertEquals(300, PdfImageQuality.MEDIUM.decodeWidthPx(144f))
        assertEquals(192, PdfImageQuality.LOW.decodeWidthPx(144f))
    }

    @Test
    fun `a sliver of a picture still asks for at least a pixel`() {
        assertEquals(1, PdfImageQuality.LOW.decodeWidthPx(0f))
    }
}
