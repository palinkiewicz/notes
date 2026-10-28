package pl.dakil.notes.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Builds small PDFs byte by byte, so each test can put exactly the structure it is about into a
 * file — an object stream, an incremental update, a cross-reference that lies — and nothing else.
 */
internal class TestPdf {

    private val out = ByteArrayOutputStream()
    private val offsets = sortedMapOf<Int, Long>()

    init {
        ascii("%PDF-1.7\n")
    }

    fun obj(num: Int, body: String): TestPdf {
        offsets[num] = out.size().toLong()
        ascii("$num 0 obj\n$body\nendobj\n")
        return this
    }

    fun stream(num: Int, dict: String, data: ByteArray): TestPdf {
        offsets[num] = out.size().toLong()
        ascii("$num 0 obj\n<<$dict /Length ${data.size}>>\nstream\n")
        out.write(data)
        ascii("\nendstream\nendobj\n")
        return this
    }

    val size: Long get() = out.size().toLong()

    /** A classic table for every object written since the last call, and its trailer. */
    fun table(trailer: String, prev: Long? = null): Long {
        val at = size
        ascii("xref\n")
        for ((num, off) in offsets) ascii("$num 1\n${off.toString().padStart(10, '0')} 00000 n\r\n")
        offsets.clear()
        ascii("trailer\n<<$trailer${prev?.let { " /Prev $it" } ?: ""}>>\n")
        return at
    }

    fun startxref(at: Long): ByteArray {
        ascii("startxref\n$at\n%%EOF\n")
        return out.toByteArray()
    }

    fun offsetOf(num: Int): Long = offsets.getValue(num)

    fun raw(text: String): TestPdf { ascii(text); return this }

    /** An object written verbatim, [text] being all of `n 0 obj … endobj`. */
    fun rawObj(num: Int, text: String): TestPdf {
        offsets[num] = size
        ascii(text)
        return this
    }

    private fun ascii(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

    companion object {
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater()
            d.setInput(data)
            d.finish()
            val buf = ByteArray(data.size + 64)
            val n = d.deflate(buf)
            d.end()
            return buf.copyOf(n)
        }

        /** The smallest well-formed PDF: [pages] pages of [w] × [h], each with its own content. */
        fun simple(pages: Int = 1, w: Int = 595, h: Int = 842): ByteArray {
            val pdf = TestPdf()
            pdf.obj(1, "<</Type /Catalog /Pages 2 0 R>>")
            val kids = (0 until pages).joinToString(" ") { "${10 + it * 2} 0 R" }
            pdf.obj(2, "<</Type /Pages /Kids [$kids] /Count $pages /MediaBox [0 0 $w $h]>>")
            pdf.obj(3, "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>")
            for (i in 0 until pages) {
                pdf.obj(10 + i * 2, "<</Type /Page /Parent 2 0 R /Resources <</Font <</F1 3 0 R>>>> /Contents ${11 + i * 2} 0 R>>")
                pdf.stream(11 + i * 2, "", "BT /F1 12 Tf 72 720 Td (Page ${i + 1}) Tj ET".toByteArray())
            }
            return pdf.startxref(pdf.table("/Size ${11 + pages * 2} /Root 1 0 R"))
        }
    }
}
