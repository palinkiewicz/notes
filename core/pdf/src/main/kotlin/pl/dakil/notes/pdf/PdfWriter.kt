package pl.dakil.notes.pdf

import java.io.OutputStream

/**
 * Writes a PDF one object at a time, straight to [target].
 *
 * Objects may be written in any order once their numbers are [allocate]d, which is what lets a page
 * name its parent before the page tree exists. Nothing in the output depends on the clock — no
 * creation date, no random `/ID` — so the same input makes the same bytes.
 */
class PdfWriter(target: OutputStream) {

    private val out = Counting(target)
    private val offsets = ArrayList<Long>().apply { add(-1L) }
    private var finished = false

    init {
        // The comment of high bytes tells transfer tools the file is binary.
        out.write("%PDF-1.7\n%".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 10))
    }

    fun allocate(): PdfRef {
        offsets += -1L
        return PdfRef(offsets.size - 1)
    }

    fun add(obj: PdfObject): PdfRef = allocate().also { write(it, obj) }

    fun write(ref: PdfRef, obj: PdfObject) {
        check(!finished)
        require(offsets[ref.num] < 0) { "Object ${ref.num} written twice" }
        offsets[ref.num] = out.count
        ascii("${ref.num} 0 obj\n")
        if (obj is PdfStream) {
            val raw = obj.raw()
            serialize(obj.dict.without("Length").with("Length", PdfNumber.of(raw.size)))
            ascii("\nstream\n")
            out.write(raw)
            ascii("\nendstream")
        } else {
            serialize(obj)
        }
        ascii("\nendobj\n")
    }

    /** Writes the cross-reference and trailer. Every allocated object must have been written. */
    fun finish(root: PdfRef, info: PdfRef? = null) {
        check(!finished)
        finished = true
        val xref = out.count
        ascii("xref\n0 ${offsets.size}\n0000000000 65535 f\r\n")
        for (i in 1 until offsets.size) {
            val at = offsets[i]
            // An object allocated and never written is listed free rather than pointing nowhere.
            if (at < 0) ascii("0000000000 00001 f\r\n") else ascii("${at.toString().padStart(10, '0')} 00000 n\r\n")
        }
        ascii("trailer\n")
        val trailer = PdfDict.of("Size" to PdfNumber.of(offsets.size), "Root" to root)
        serialize(if (info != null) trailer.with("Info", info) else trailer)
        ascii("\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    private fun serialize(obj: PdfObject) {
        when (obj) {
            PdfNull -> ascii("null")
            is PdfBool -> ascii(if (obj.value) "true" else "false")
            is PdfNumber -> ascii(obj.text)
            is PdfName -> name(obj.value)
            is PdfString -> string(obj)
            is PdfRef -> ascii("${obj.num} ${obj.gen} R")
            is PdfArray -> {
                ascii("[")
                obj.items.forEachIndexed { i, item -> if (i > 0) ascii(" "); serialize(item) }
                ascii("]")
            }
            is PdfDict -> {
                ascii("<<")
                for ((k, v) in obj.entries) {
                    name(k)
                    ascii(" ")
                    serialize(v)
                }
                ascii(">>")
            }
            // A stream is only ever an indirect object; nested, the best that can be done is a reference
            // to nothing, which a reader treats as null.
            is PdfStream -> ascii("null")
        }
    }

    private fun name(value: String) {
        out.write('/'.code)
        for (ch in value) {
            val c = ch.code and 0xFF
            if (c < 0x21 || c > 0x7E || c == '#'.code || isDelimiter(c)) {
                ascii("#" + c.toString(16).padStart(2, '0').uppercase(java.util.Locale.ROOT))
            } else {
                out.write(c)
            }
        }
    }

    private fun string(s: PdfString) {
        if (s.hex) {
            ascii("<")
            for (b in s.bytes) ascii((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
            ascii(">")
            return
        }
        out.write('('.code)
        for (b in s.bytes) {
            when (val c = b.toInt() and 0xFF) {
                '('.code, ')'.code, '\\'.code -> { out.write('\\'.code); out.write(c) }
                13 -> ascii("\\r")
                else -> out.write(c)
            }
        }
        out.write(')'.code)
    }

    private fun ascii(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

    private class Counting(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { delegate.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); count += len }
        override fun flush() = delegate.flush()
    }
}
