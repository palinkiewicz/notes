package pl.dakil.notes.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfFileTest {

    @Test
    fun `a plain PDF lists its pages with the size the page tree gives them`() {
        val pdf = PdfFile.open(TestPdf.simple(pages = 3, w = 612, h = 792))
        assertEquals(3, pdf.pages.size)
        assertEquals(612f, pdf.pages[2].width)
        assertEquals(792f, pdf.pages[2].height)
        assertFalse(pdf.recovered)
    }

    @Test
    fun `resources, sizes and rotation are inherited down the page tree`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 600 800] /Rotate 90 /Resources <</Font <<>>>>>>")
        pdf.obj(3, "<</Type /Pages /Parent 2 0 R /Kids [4 0 R] /Count 1 /CropBox [10 20 510 720]>>")
        pdf.obj(4, "<</Type /Page /Parent 3 0 R>>")
        val file = PdfFile.open(pdf.startxref(pdf.table("/Size 5 /Root 1 0 R")))
        val page = file.pages.single()
        assertEquals(90, page.rotate)
        assertEquals(PdfBox(10f, 20f, 510f, 720f), page.cropBox)
        assertTrue(file.resolve(page.resources) is PdfDict)
        // Shown turned a quarter: the crop box's height across, its width down.
        assertEquals(700f, page.width)
        assertEquals(500f, page.height)
    }

    @Test
    fun `a crop box larger than the media box is clipped to it`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /CropBox [-50 -50 1000 1000]>>")
        val page = PdfFile.open(pdf.startxref(pdf.table("/Size 4 /Root 1 0 R"))).pages.single()
        assertEquals(PdfBox(0f, 0f, 300f, 400f), page.cropBox)
    }

    @Test
    fun `objects in a compressed object stream are found through a cross-reference stream`() {
        // The PDF 1.5 layout most producers now write: no table at all, and the dictionaries packed
        // into one deflated stream that the cross-reference stream points into.
        val catalog = "<</Type /Catalog /Pages 2 0 R>>"
        val pages = "<</Type /Pages /Kids [3 0 R] /Count 1>>"
        val page = "<</Type /Page /Parent 2 0 R /MediaBox [0 0 420 595]>>"
        val bodies = listOf(catalog, pages, page)
        val header = StringBuilder()
        val content = StringBuilder()
        bodies.forEachIndexed { i, b -> header.append("${i + 1} ${content.length} "); content.append(b).append(' ') }
        val packed = (header.toString() + content).toByteArray()
        val first = header.length

        val pdf = TestPdf()
        pdf.stream(4, "/Type /ObjStm /N 3 /First $first /Filter /FlateDecode", TestPdf.deflate(packed))
        val xrefAt = pdf.size
        // Rows: type, offset-or-stream (2 bytes), index (1 byte); PNG Up predictor over 4 columns.
        val rows = listOf(
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(2, 0, 4, 0),
            byteArrayOf(2, 0, 4, 1),
            byteArrayOf(2, 0, 4, 2),
            byteArrayOf(1, 0, pdf.offsetOf(4).toByte(), 0),
            byteArrayOf(1, ((xrefAt shr 8) and 0xFF).toByte(), (xrefAt and 0xFF).toByte(), 0),
        )
        val predicted = ByteArrayOutputStream()
        var prev = ByteArray(4)
        for (row in rows) {
            predicted.write(2)
            predicted.write(ByteArray(4) { (row[it] - prev[it]).toByte() })
            prev = row
        }
        pdf.stream(
            5,
            "/Type /XRef /Size 6 /W [1 2 1] /Root 1 0 R /Filter /FlateDecode /DecodeParms <</Predictor 12 /Columns 4>>",
            TestPdf.deflate(predicted.toByteArray()),
        )
        val file = PdfFile.open(pdf.startxref(xrefAt))
        assertFalse(file.recovered)
        assertEquals(420f, file.pages.single().width)
    }

    @Test
    fun `an incremental update replaces an object, and the newer version wins`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /MediaBox [0 0 100 100]>>")
        val first = pdf.table("/Size 4 /Root 1 0 R")
        pdf.raw("startxref\n$first\n%%EOF\n")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /MediaBox [0 0 200 300]>>")
        val second = pdf.table("/Size 4 /Root 1 0 R", prev = first)
        assertEquals(200f, PdfFile.open(pdf.startxref(second)).pages.single().width)
    }

    @Test
    fun `a file whose cross-reference points at the wrong offsets is rebuilt by scanning`() {
        val good = TestPdf.simple(pages = 2)
        // Every object moved 7 bytes later than the table says, as a careless edit leaves it.
        val text = String(good, Charsets.ISO_8859_1).replaceFirst("%PDF-1.7\n", "%PDF-1.7\n%junk!\n")
        val file = PdfFile.open(text.toByteArray(Charsets.ISO_8859_1))
        assertTrue(file.recovered)
        assertEquals(2, file.pages.size)
    }

    @Test
    fun `an object the cross-reference leaves out is still found`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /Contents 4 0 R>>")
        pdf.raw("4 0 obj\n<</Length 3>>\nstream\nq Q\nendstream\nendobj\n")
        val file = PdfFile.open(pdf.startxref(pdf.table("/Size 5 /Root 1 0 R")))
        assertTrue(file.resolve(file.pages.single().dict["Contents"]) is PdfStream)
    }

    @Test
    fun `a file with no startxref at all is rebuilt by scanning`() {
        val text = String(TestPdf.simple(pages = 1), Charsets.ISO_8859_1).substringBefore("startxref")
        assertEquals(1, PdfFile.open(text.toByteArray(Charsets.ISO_8859_1)).pages.size)
    }

    @Test
    fun `a stream whose declared length is wrong is cut at its endstream`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /Contents 4 0 R>>")
        pdf.rawObj(4, "4 0 obj\n<</Length 999>>\nstream\nq Q\nendstream\nendobj\n")
        val bytes = pdf.startxref(pdf.table("/Size 5 /Root 1 0 R"))
        val file = PdfFile.open(bytes)
        val content = file.resolve(file.pages.single().dict["Contents"]) as PdfStream
        assertArrayEquals("q Q".toByteArray(), content.raw())
    }

    @Test
    fun `an encrypted file still gives its page sizes but cannot be embedded`() {
        val pdf = TestPdf()
        pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        pdf.obj(2, "<</Type /Pages /Kids [3 0 R] /Count 1>>")
        pdf.obj(3, "<</Type /Page /Parent 2 0 R /MediaBox [0 0 500 500]>>")
        pdf.obj(4, "<</Filter /Standard /V 1 /R 2 /O <00> /U <00> /P -4>>")
        val file = PdfFile.open(pdf.startxref(pdf.table("/Size 5 /Root 1 0 R /Encrypt 4 0 R")))
        assertTrue(file.isEncrypted)
        assertEquals(500f, file.pages.single().width)
        assertFalse(PdfComposer.canEmbed(file, 0))
    }

    @Test
    fun `the lexer reads the awkward corners of PDF syntax`() {
        val src = ByteArraySource(
            "[/A#20B (a(b)c\\051\\\\) <414> .5 -3. +1 % comment\n true null 1 0 R]".toByteArray(Charsets.ISO_8859_1),
        )
        val a = PdfParser(src).value(PdfLexer(src)) as PdfArray
        assertEquals(PdfName("A B"), a[0])
        assertEquals("a(b)c)\\", String((a[1] as PdfString).bytes, Charsets.ISO_8859_1))
        assertArrayEquals(byteArrayOf(0x41, 0x40), (a[2] as PdfString).bytes)
        assertEquals(0.5, (a[3] as PdfNumber).toDouble(), 0.0)
        assertEquals(-3.0, (a[4] as PdfNumber).toDouble(), 0.0)
        assertEquals(1.0, (a[5] as PdfNumber).toDouble(), 0.0)
        assertEquals(PdfBool(true), a[6])
        assertEquals(PdfNull, a[7])
        assertEquals(PdfRef(1, 0), a[8])
    }

    @Test
    fun `the general-purpose filters decode what the specification's own examples encode`() {
        fun decode(filter: String, data: ByteArray) =
            PdfFilters.decode(PdfStream(PdfDict.of("Filter" to PdfName(filter)), data)) { it }
        assertArrayEquals("Hi".toByteArray(), decode("ASCIIHexDecode", "48 69>".toByteArray()))
        assertArrayEquals("test".toByteArray(), decode("ASCII85Decode", "FCfN8~>".toByteArray()))
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 7, 7, 7),
            decode("RunLengthDecode", byteArrayOf(2, 1, 2, 3, (257 - 3).toByte(), 7, 128.toByte())),
        )
        // The LZW example from the PDF specification, §7.4.4.2.
        val lzw = byteArrayOf(0x80.toByte(), 0x0B, 0x60, 0x50, 0x22, 0x0C, 0x0C, 0x85.toByte(), 0x01)
        assertArrayEquals(
            byteArrayOf(45, 45, 45, 45, 45, 65, 45, 45, 45, 66),
            decode("LZWDecode", lzw),
        )
    }
}
