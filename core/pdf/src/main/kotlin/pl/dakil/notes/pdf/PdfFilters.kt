package pl.dakil.notes.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The stream filters a copy may have to undo.
 *
 * Only the general-purpose ones. Decoding is needed for cross-reference and object streams, and for
 * a page's content when its streams have to be joined into one; picture codecs — DCT, JPX, CCITT,
 * JBIG2 — are never decoded, because a picture is copied exactly as it was encoded.
 */
object PdfFilters {

    class Unsupported(filter: String) : PdfFormatException("Unsupported filter $filter")

    private val SUPPORTED = setOf(
        "FlateDecode", "Fl", "LZWDecode", "LZW", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
        "RunLengthDecode", "RL",
    )

    /** Whether every filter on [dict] is one this can undo. */
    fun canDecode(dict: PdfDict, resolve: (PdfObject?) -> PdfObject?): Boolean =
        filters(dict, resolve).all { it.first in SUPPORTED }

    /** The stream's bytes with every filter undone. */
    fun decode(stream: PdfStream, resolve: (PdfObject?) -> PdfObject?): ByteArray {
        var data = stream.raw()
        for ((name, params) in filters(stream.dict, resolve)) {
            data = when (name) {
                "FlateDecode", "Fl" -> predict(inflate(data), params)
                "LZWDecode", "LZW" -> predict(lzw(data, (params?.get("EarlyChange") as? PdfNumber)?.toInt() ?: 1), params)
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "ASCII85Decode", "A85" -> ascii85(data)
                "RunLengthDecode", "RL" -> runLength(data)
                else -> throw Unsupported(name)
            }
        }
        return data
    }

    /** Deflates [data] for a stream written fresh, with a fixed level so the output is stable. */
    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun filters(dict: PdfDict, resolve: (PdfObject?) -> PdfObject?): List<Pair<String, PdfDict?>> {
        val names = when (val f = resolve(dict["Filter"])) {
            is PdfName -> listOf(f.value)
            is PdfArray -> f.items.mapNotNull { (resolve(it) as? PdfName)?.value }
            else -> emptyList()
        }
        val params = when (val p = resolve(dict["DecodeParms"] ?: dict["DP"])) {
            is PdfDict -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDict }
            else -> emptyList()
        }
        return names.mapIndexed { i, name -> name to params.getOrNull(i) }
    }

    /**
     * Inflates, keeping whatever came out before an error: a truncated or slightly corrupt stream
     * is common in the wild, and readers are expected to draw what they can of it.
     */
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(maxOf(64, data.size * 3))
            val chunk = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try { inflater.inflate(chunk) } catch (_: DataFormatException) { break }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** Undoes a PNG (10–15) or TIFF (2) predictor, as cross-reference streams use. */
    private fun predict(data: ByteArray, params: PdfDict?): ByteArray {
        val predictor = (params?.get("Predictor") as? PdfNumber)?.toInt() ?: 1
        if (predictor < 2) return data
        val colors = (params?.get("Colors") as? PdfNumber)?.toInt()?.coerceAtLeast(1) ?: 1
        val bits = (params?.get("BitsPerComponent") as? PdfNumber)?.toInt()?.coerceAtLeast(1) ?: 8
        val columns = (params?.get("Columns") as? PdfNumber)?.toInt()?.coerceAtLeast(1) ?: 1
        val bpp = maxOf(1, (colors * bits + 7) / 8)
        val rowLen = (colors * bits * columns + 7) / 8

        if (predictor == 2) {
            if (bits != 8) return data
            val out = data.copyOf()
            var row = 0
            while (row + rowLen <= out.size) {
                for (i in bpp until rowLen) {
                    out[row + i] = (out[row + i] + out[row + i - bpp]).toByte()
                }
                row += rowLen
            }
            return out
        }

        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLen)
        var pos = 0
        while (pos < data.size) {
            val type = data[pos].toInt() and 0xFF
            pos++
            val cur = ByteArray(rowLen)
            val n = minOf(rowLen, data.size - pos)
            System.arraycopy(data, pos, cur, 0, n)
            pos += n
            for (i in 0 until rowLen) {
                val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val raw = cur[i].toInt() and 0xFF
                cur[i] = when (type) {
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upLeft)
                    else -> raw
                }.toByte()
            }
            out.write(cur, 0, rowLen)
            prev = cur
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2)
        var high = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (high < 0) high = v else { out.write(high * 16 + v); high = -1 }
        }
        if (high >= 0) out.write(high * 16)
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        val group = IntArray(5)
        var n = 0
        var i = 0
        // A leading `<~` is legal in a stream.
        if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            if (c == '~'.code) break
            if (isWhite(c)) continue
            if (c == 'z'.code && n == 0) {
                repeat(4) { out.write(0) }
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            group[n++] = c - '!'.code
            if (n == 5) {
                var v = 0L
                for (d in group) v = v * 85 + d
                for (s in 3 downTo 0) out.write(((v shr (8 * s)) and 0xFF).toInt())
                n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) group[k] = 84
            var v = 0L
            for (d in group) v = v * 85 + d
            for (s in 3 downTo 4 - (n - 1)) out.write(((v shr (8 * s)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    private fun runLength(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val len = data[i++].toInt() and 0xFF
            when {
                len == 128 -> break
                len < 128 -> {
                    val n = minOf(len + 1, data.size - i)
                    out.write(data, i, n)
                    i += n
                }
                i < data.size -> {
                    val b = data[i++].toInt()
                    repeat(257 - len) { out.write(b) }
                }
            }
        }
        return out.toByteArray()
    }

    private fun lzw(data: ByteArray, earlyChange: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size * 3)
        val table = ArrayList<ByteArray>(4096)
        fun reset() {
            table.clear()
            for (i in 0 until 256) table += byteArrayOf(i.toByte())
            table += ByteArray(0) // 256: clear
            table += ByteArray(0) // 257: end
        }
        reset()
        var codeLen = 9
        var bitBuf = 0L
        var bitCount = 0
        var prev: ByteArray? = null
        for (b in data) {
            bitBuf = (bitBuf shl 8) or (b.toLong() and 0xFF)
            bitCount += 8
            while (bitCount >= codeLen) {
                val code = ((bitBuf shr (bitCount - codeLen)) and ((1L shl codeLen) - 1)).toInt()
                bitCount -= codeLen
                when {
                    code == 256 -> { reset(); codeLen = 9; prev = null }
                    code == 257 -> return out.toByteArray()
                    else -> {
                        val entry = when {
                            code < table.size -> table[code]
                            prev != null -> prev + prev[0]
                            else -> return out.toByteArray()
                        }
                        out.write(entry)
                        if (prev != null && table.size < 4096) table += prev + entry[0]
                        prev = entry
                        val limit = (1 shl codeLen) - earlyChange
                        if (table.size >= limit && codeLen < 12) codeLen++
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
