package pl.dakil.notes.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale

class PdfComposerTest {

    private fun compose(overlay: PdfFile?, pages: List<PdfComposer.Page>): ByteArray =
        ByteArrayOutputStream().also { PdfComposer.compose(overlay, pages, it) }.toByteArray()

    private fun fullPage(source: PdfFile, index: Int, w: Float = 595f, h: Float = 842f) =
        PdfComposer.Backdrop(source, index, 0f, 0f, w, h)

    /** Where the matrix sends ([x], [y]). */
    private fun DoubleArray.map(x: Double, y: Double) = (this[0] * x + this[2] * y + this[4]) to (this[1] * x + this[3] * y + this[5])

    private fun assertPoint(expected: Pair<Double, Double>, actual: Pair<Double, Double>) {
        assertEquals(expected.first, actual.first, 1e-6)
        assertEquals(expected.second, actual.second, 1e-6)
    }

    private fun page(rotate: Int, crop: PdfBox) = PdfPage(0, PdfDict.EMPTY, null, crop, crop, rotate, 1f)

    @Test
    fun `an unrotated page's crop box is carried exactly onto the target rect`() {
        val m = PdfComposer.placement(page(0, PdfBox(10f, 20f, 110f, 220f)), 50.0, 60.0, 50.0, 100.0)
        assertPoint(50.0 to 60.0, m.map(10.0, 20.0))
        assertPoint(100.0 to 160.0, m.map(110.0, 220.0))
    }

    @Test
    fun `a page turned a quarter clockwise shows its top-left corner at the top-right`() {
        // Its own top-left (0, h) is where a viewer turning it clockwise puts the top-right.
        val m = PdfComposer.placement(page(90, PdfBox(0f, 0f, 100f, 200f)), 0.0, 0.0, 200.0, 100.0)
        assertPoint(200.0 to 100.0, m.map(0.0, 200.0))
        assertPoint(0.0 to 100.0, m.map(0.0, 0.0))
        assertPoint(0.0 to 0.0, m.map(100.0, 0.0))
    }

    @Test
    fun `a page turned upside down and three quarters lands its corners where a viewer shows them`() {
        val half = PdfComposer.placement(page(180, PdfBox(0f, 0f, 100f, 200f)), 0.0, 0.0, 100.0, 200.0)
        assertPoint(100.0 to 200.0, half.map(0.0, 0.0))
        val three = PdfComposer.placement(page(270, PdfBox(0f, 0f, 100f, 200f)), 0.0, 0.0, 200.0, 100.0)
        // Counter-clockwise a quarter: its bottom-left goes to the bottom-right.
        assertPoint(200.0 to 0.0, three.map(0.0, 0.0))
        assertPoint(0.0 to 100.0, three.map(100.0, 200.0))
    }

    @Test
    fun `a composed note reads back with its pages at their exact sizes`() {
        val source = PdfFile.open(TestPdf.simple(pages = 2))
        val out = PdfFile.open(
            compose(
                null,
                listOf(
                    PdfComposer.Page(595.276f, 841.89f, null, 0xFFFFFFFF.toInt(), listOf(fullPage(source, 1, 595.276f, 841.89f))),
                    PdfComposer.Page(595.276f, 841.89f, null, null, emptyList()),
                ),
            ),
        )
        assertEquals(2, out.pages.size)
        assertEquals(PdfBox(0f, 0f, 595.276f, 841.89f), out.pages[0].mediaBox)
        val xobjects = out.resolve((out.resolve(out.pages[0].resources) as PdfDict)["XObject"]) as PdfDict
        val form = out.resolve(xobjects["DkPdf0"]) as PdfStream
        assertEquals("Form", form.dict.name("Subtype"))
        // The page's content arrives exactly as it was, still saying what it said.
        assertTrue(String(form.raw(), Charsets.ISO_8859_1).contains("(Page 2) Tj"))
    }

    @Test
    fun `a font every page uses is copied once, and one page shown twice is one form`() {
        val source = PdfFile.open(TestPdf.simple(pages = 2))
        val pages = listOf(0, 1, 1).map { PdfComposer.Page(595f, 842f, null, null, listOf(fullPage(source, it))) }
        val bytes = compose(null, pages)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertEquals(1, Regex("/BaseFont /Helvetica").findAll(text).count())
        assertEquals(2, Regex("/Subtype /Form").findAll(text).count())
    }

    @Test
    fun `the overlay is drawn over the backdrop, its top on the page's top whatever its own height`() {
        // The note's exporter rounds page sizes to whole points, so its page can be a fraction
        // shorter than the composed one; the overlay is moved so its top edge is the page's.
        val overlay = PdfFile.open(TestPdf.simple(pages = 1, w = 595, h = 841))
        val source = PdfFile.open(TestPdf.simple(pages = 1))
        val out = PdfFile.open(
            compose(overlay, listOf(PdfComposer.Page(595.276f, 841.89f, 0, null, listOf(fullPage(source, 0, 595.276f, 841.89f))))),
        )
        val contents = (out.pages[0].dict["Contents"] as PdfArray).items.map { out.resolve(it) as PdfStream }
        val prefix = String(contents.first().raw(), Charsets.ISO_8859_1)
        val draw = prefix.indexOf("/DkPdf0 Do")
        val shift = prefix.indexOf("q 1 0 0 1 0 0.89 cm")
        assertTrue(prefix, draw in 0 until shift)
        assertTrue(String(contents[1].raw(), Charsets.ISO_8859_1).contains("(Page 1) Tj"))
        assertEquals("\nQ\n", String(contents.last().raw(), Charsets.ISO_8859_1))
        // The overlay's own font came along under the overlay's resources.
        val res = out.resolve(out.pages[0].resources) as PdfDict
        assertTrue(res["Font"] != null)
    }

    @Test
    fun `a name the overlay already uses is not taken for a backdrop`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 100 100]>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /Resources <</XObject <</DkPdf0 4 0 R>>>> /Contents 5 0 R>>")
        pdf.stream(4, "/Type /XObject /Subtype /Form /BBox [0 0 1 1]", "".toByteArray())
        pdf.stream(5, "", "/DkPdf0 Do".toByteArray())
        val overlay = PdfFile.open(pdf.startxref(pdf.table("/Size 6 /Root 1 0 R")))
        val source = PdfFile.open(TestPdf.simple())
        val out = PdfFile.open(compose(overlay, listOf(PdfComposer.Page(100f, 100f, 0, null, listOf(fullPage(source, 0, 100f, 100f))))))
        val xobjects = (out.resolve(out.pages[0].resources) as PdfDict)["XObject"] as PdfDict
        assertEquals(setOf("DkPdf0", "DkPdf1"), xobjects.entries.keys)
    }

    @Test
    fun `several content streams are joined into one form`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 100 100]>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /Contents [4 0 R 5 0 R]>>")
        pdf.stream(4, "/Filter /FlateDecode", TestPdf.deflate("BT (one".toByteArray()))
        pdf.stream(5, "", ") Tj ET".toByteArray())
        val source = PdfFile.open(pdf.startxref(pdf.table("/Size 6 /Root 1 0 R")))
        val out = PdfFile.open(compose(null, listOf(PdfComposer.Page(100f, 100f, null, null, listOf(fullPage(source, 0, 100f, 100f))))))
        val form = out.resolve((out.resolve((out.resolve(out.pages[0].resources) as PdfDict)["XObject"]) as PdfDict)["DkPdf0"]) as PdfStream
        val decoded = String(out.decode(form), Charsets.ISO_8859_1)
        assertTrue(decoded, decoded.contains("BT (one\n) Tj ET"))
    }

    @Test
    fun `a highlight already in the PDF is drawn from its appearance onto its rect`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 100 100]>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /Contents 4 0 R /Annots [5 0 R 7 0 R]>>")
        pdf.stream(4, "", "0 0 m 1 1 l S".toByteArray())
        pdf.obj(5, "<</Type /Annot /Subtype /Highlight /Rect [10 20 30 60] /AP <</N 6 0 R>>>>")
        pdf.stream(6, "/BBox [0 0 2 4]", "1 1 0 rg 0 0 2 4 re f".toByteArray())
        // Hidden: a viewer does not draw it, so neither does the export.
        pdf.obj(7, "<</Type /Annot /Subtype /Square /F 2 /Rect [0 0 5 5] /AP <</N 6 0 R>>>>")
        val source = PdfFile.open(pdf.startxref(pdf.table("/Size 8 /Root 1 0 R")))
        assertTrue(PdfComposer.canEmbed(source, 0))
        val out = PdfFile.open(compose(null, listOf(PdfComposer.Page(100f, 100f, null, null, listOf(fullPage(source, 0, 100f, 100f))))))
        val form = out.resolve((out.resolve((out.resolve(out.pages[0].resources) as PdfDict)["XObject"]) as PdfDict)["DkPdf0"]) as PdfStream
        val decoded = String(out.decode(form), Charsets.ISO_8859_1)
        assertEquals(1, Regex("/DkAnn\\d Do").findAll(decoded).count())
        // A 2 × 4 box onto a 20 × 40 rect at (10, 20): scaled ten times and moved there.
        assertTrue(decoded, decoded.contains("q 10 0 0 10 10 20 cm /DkAnn0 Do Q"))
    }

    @Test
    fun `composing the same note twice produces identical bytes`() {
        val source = PdfFile.open(TestPdf.simple(pages = 2))
        val pages = listOf(PdfComposer.Page(595f, 842f, null, 0xFFFFFFFF.toInt(), listOf(fullPage(source, 0))))
        assertArrayEquals(compose(null, pages), compose(null, pages))
    }

    @Test
    fun `every offset in the written cross-reference points at its object`() {
        val source = PdfFile.open(TestPdf.simple(pages = 3))
        val bytes = compose(null, (0 until 3).map { PdfComposer.Page(595f, 842f, null, null, listOf(fullPage(source, it))) })
        val text = String(bytes, Charsets.ISO_8859_1)
        val table = text.substring(text.lastIndexOf("\nxref\n"))
        val rows = Regex("(\\d{10}) 00000 n").findAll(table).map { it.groupValues[1].toInt() }.toList()
        rows.forEachIndexed { i, at -> assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj", at)) }
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun `numbers are written the same on every device`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.forLanguageTag("pl-PL"), Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals("1.5", formatNumber(1.5))
                assertEquals("0.00001", formatNumber(0.00001))
                assertEquals("0", formatNumber(-0.000001))
                assertEquals("841.89", PdfNumber.of(841.89f).text)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
