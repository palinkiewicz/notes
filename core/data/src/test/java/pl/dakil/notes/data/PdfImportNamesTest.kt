package pl.dakil.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PdfImportNamesTest {

    @Test
    fun `a note made from a PDF is named after the file, without its extension`() {
        assertEquals("Lecture 3", PdfImport.titleFromDisplayName("Lecture 3.pdf"))
        assertEquals("notes.v2", PdfImport.titleFromDisplayName("notes.v2.pdf"))
        assertNull(PdfImport.titleFromDisplayName(".pdf"))
        assertNull(PdfImport.titleFromDisplayName(null))
    }

    @Test
    fun `an upper-case extension is dropped on a Turkish device too`() {
        // Lower-cased under the device locale, `.PDF` becomes `.pdf` everywhere but Turkey, where the
        // `I`-less extension is fine but the habit is not: every file comparison folds with ROOT.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("RAPORT", PdfImport.titleFromDisplayName("RAPORT.PDF"))
            assertEquals("İNDEKS", PdfImport.titleFromDisplayName("İNDEKS.PDF"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a PDF is recognised by its header, even after a little junk`() {
        val clean = "%PDF-1.7\n".toByteArray()
        assertTrue(PdfImport.looksLikePdf(clean, clean.size))
        val junk = "\u0000\u0000garbage%PDF-1.4".toByteArray()
        assertTrue(PdfImport.looksLikePdf(junk, junk.size))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertFalse(PdfImport.looksLikePdf(png, png.size))
    }
}
