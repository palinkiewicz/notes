package pl.dakil.notes.pdf

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

open class PdfFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Random access to a PDF's bytes.
 *
 * A PDF is read from the end — the cross-reference table is found by looking backwards from the
 * last line — and then by jumping to wherever an object lives, so it is addressed, never streamed,
 * and never loaded whole: a scanned PDF can be far larger than the parts of it a copy has to parse.
 */
interface PdfSource : Closeable {
    val length: Long

    /** Reads up to [len] bytes at [pos] into [buf]; returns how many, or -1 at the end. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    override fun close() {}
}

class ByteArraySource(private val bytes: ByteArray) : PdfSource {
    override val length: Long get() = bytes.size.toLong()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - pos).toInt()
        System.arraycopy(bytes, pos.toInt(), buf, off, n)
        return n
    }
}

class FileSource(file: File) : PdfSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    @Synchronized
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        raf.seek(pos)
        return raf.read(buf, off, len)
    }

    override fun close() = raf.close()
}

/** Exactly [len] bytes at [pos], or as many as there are. */
fun PdfSource.readBytes(pos: Long, len: Int): ByteArray {
    val out = ByteArray(maxOf(0, minOf(len.toLong(), length - pos).toInt()))
    var done = 0
    while (done < out.size) {
        val n = read(pos + done, out, done, out.size - done)
        if (n <= 0) break
        done += n
    }
    return if (done == out.size) out else out.copyOf(done)
}

/** The first offset at or after [from] where [pattern] occurs, or -1. */
fun PdfSource.indexOf(pattern: ByteArray, from: Long, until: Long = length): Long {
    val window = ByteArray(64 * 1024)
    var start = from
    while (start < until) {
        val n = read(start, window, 0, minOf(window.size.toLong(), until - start).toInt())
        if (n <= 0) return -1
        var i = 0
        while (i <= n - pattern.size) {
            var j = 0
            while (j < pattern.size && window[i + j] == pattern[j]) j++
            if (j == pattern.size) return start + i
            i++
        }
        if (start + n >= until) return -1
        // Step back by the pattern's length so a match across the window edge is not missed.
        start += maxOf(1, n - pattern.size + 1)
    }
    return -1
}

internal fun isWhite(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

internal fun isDelimiter(c: Int): Boolean =
    c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
        c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

internal fun isRegular(c: Int): Boolean = c >= 0 && !isWhite(c) && !isDelimiter(c)

/** One token of PDF syntax. */
internal sealed interface Token {
    data class Num(val text: String) : Token
    data class Name(val value: String) : Token
    class Str(val bytes: ByteArray, val hex: Boolean) : Token
    data class Keyword(val text: String) : Token
    data object ArrayOpen : Token
    data object ArrayClose : Token
    data object DictOpen : Token
    data object DictClose : Token
    data object End : Token
}

/** Reads tokens from a [PdfSource], through a small window so lexing is not a call per byte. */
internal class PdfLexer(private val source: PdfSource, start: Long = 0) {

    private val window = ByteArray(8 * 1024)
    private var windowStart = Long.MIN_VALUE
    private var windowLen = 0

    var pos: Long = start

    fun peek(): Int = byteAt(pos)

    fun next(): Int = byteAt(pos).also { if (it >= 0) pos++ }

    private fun byteAt(at: Long): Int {
        if (at < windowStart || at >= windowStart + windowLen) {
            windowStart = at
            windowLen = maxOf(0, source.read(at, window, 0, window.size))
            if (windowLen == 0) return -1
        }
        return window[(at - windowStart).toInt()].toInt() and 0xFF
    }

    fun skipWhite() {
        while (true) {
            val c = peek()
            if (isWhite(c)) {
                pos++
            } else if (c == '%'.code) {
                while (true) {
                    val d = next()
                    if (d < 0 || d == 10 || d == 13) break
                }
            } else {
                return
            }
        }
    }

    fun token(): Token {
        skipWhite()
        val c = next()
        return when {
            c < 0 -> Token.End
            c == '['.code -> Token.ArrayOpen
            c == ']'.code -> Token.ArrayClose
            c == '<'.code -> if (peek() == '<'.code) { pos++; Token.DictOpen } else hexString()
            c == '>'.code -> if (peek() == '>'.code) { pos++; Token.DictClose } else Token.Keyword(">")
            c == '('.code -> literalString()
            c == '/'.code -> name()
            // Stray delimiters a broken file can hold: read as a one-character keyword and ignored.
            c == ')'.code || c == '{'.code || c == '}'.code -> Token.Keyword(c.toChar().toString())
            else -> {
                val sb = StringBuilder().append(c.toChar())
                while (isRegular(peek())) sb.append(next().toChar())
                val text = sb.toString()
                if (looksNumeric(text)) Token.Num(text) else Token.Keyword(text)
            }
        }
    }

    private fun looksNumeric(text: String): Boolean {
        var digits = 0
        for ((i, ch) in text.withIndex()) {
            when {
                ch.isDigit() -> digits++
                ch == '.' -> {}
                (ch == '+' || ch == '-') && i == 0 -> {}
                else -> return false
            }
        }
        return digits > 0
    }

    private fun name(): Token {
        val bytes = ByteArrayOutputStream()
        while (isRegular(peek())) {
            val c = next()
            if (c == '#'.code) {
                val h = hexValue(peek())
                val l = if (h >= 0) hexValue(byteAt(pos + 1)) else -1
                if (h >= 0 && l >= 0) {
                    pos += 2
                    bytes.write(h * 16 + l)
                    continue
                }
            }
            bytes.write(c)
        }
        return Token.Name(String(bytes.toByteArray(), Charsets.ISO_8859_1))
    }

    private fun hexString(): Token {
        val out = ByteArrayOutputStream()
        var high = -1
        while (true) {
            val c = next()
            if (c < 0 || c == '>'.code) break
            val v = hexValue(c)
            if (v < 0) continue
            if (high < 0) high = v else { out.write(high * 16 + v); high = -1 }
        }
        if (high >= 0) out.write(high * 16)
        return Token.Str(out.toByteArray(), hex = true)
    }

    private fun literalString(): Token {
        val out = ByteArrayOutputStream()
        var depth = 1
        loop@ while (true) {
            val c = next()
            when {
                c < 0 -> break@loop
                c == '('.code -> { depth++; out.write(c) }
                c == ')'.code -> { if (--depth == 0) break@loop; out.write(c) }
                c == 13 -> { if (peek() == 10) pos++; out.write(10) }
                c == '\\'.code -> {
                    val e = next()
                    when (e) {
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        13 -> if (peek() == 10) pos++
                        10 -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                val d = peek()
                                if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++ }
                            }
                            out.write(v and 0xFF)
                        }
                        -1 -> break@loop
                        else -> out.write(e)
                    }
                }
                else -> out.write(c)
            }
        }
        return Token.Str(out.toByteArray(), hex = false)
    }

    private fun hexValue(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }
}

/**
 * Parses objects out of a [PdfSource].
 *
 * [lengthOf] resolves a stream's `/Length` when it is an indirect reference, which it often is —
 * the writer did not know the length until after the stream was written.
 */
internal class PdfParser(
    private val source: PdfSource,
    private val lengthOf: (PdfObject) -> Long? = { (it as? PdfNumber)?.toLong() },
) {

    /** The direct object starting at the lexer's position. */
    fun value(lexer: PdfLexer, depth: Int = 0): PdfObject {
        if (depth > MAX_DEPTH) throw PdfFormatException("Objects nested too deeply")
        return when (val t = lexer.token()) {
            is Token.Num -> numberOrRef(lexer, t)
            is Token.Name -> PdfName(t.value)
            is Token.Str -> PdfString(t.bytes, t.hex)
            Token.ArrayOpen -> {
                val items = ArrayList<PdfObject>()
                while (true) {
                    val mark = lexer.pos
                    val next = lexer.token()
                    if (next == Token.ArrayClose || next == Token.End) break
                    lexer.pos = mark
                    items += value(lexer, depth + 1)
                }
                PdfArray(items)
            }
            Token.DictOpen -> dict(lexer, depth)
            is Token.Keyword -> when (t.text) {
                "true" -> PdfBool(true)
                "false" -> PdfBool(false)
                else -> PdfNull
            }
            else -> PdfNull
        }
    }

    private fun dict(lexer: PdfLexer, depth: Int): PdfDict {
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            val t = lexer.token()
            if (t == Token.DictClose || t == Token.End) break
            // A key that is not a name is a broken file; skip it and keep what can be kept.
            if (t !is Token.Name) continue
            val mark = lexer.pos
            val next = lexer.token()
            if (next == Token.DictClose) { entries[t.value] = PdfNull; break }
            lexer.pos = mark
            entries[t.value] = value(lexer, depth + 1)
        }
        return PdfDict(entries)
    }

    private fun numberOrRef(lexer: PdfLexer, first: Token.Num): PdfObject {
        val num = first.text.toIntOrNull()
        if (num != null && num >= 0) {
            val mark = lexer.pos
            val second = lexer.token()
            val gen = (second as? Token.Num)?.text?.toIntOrNull()
            if (gen != null && gen >= 0) {
                val third = lexer.token()
                if (third is Token.Keyword && third.text == "R") return PdfRef(num, gen)
            }
            lexer.pos = mark
        }
        return PdfNumber(first.text)
    }

    /**
     * The indirect object `num gen obj … endobj` at [offset], or null when there is none there —
     * which is how a wrong cross-reference offset is noticed.
     */
    fun indirect(offset: Long): Pair<PdfRef, PdfObject>? {
        val lexer = PdfLexer(source, offset)
        val num = (lexer.token() as? Token.Num)?.text?.toIntOrNull() ?: return null
        val gen = (lexer.token() as? Token.Num)?.text?.toIntOrNull() ?: return null
        val kw = lexer.token()
        if (kw !is Token.Keyword || kw.text != "obj") return null
        val obj = value(lexer)
        if (obj !is PdfDict) return PdfRef(num, gen) to obj
        val mark = lexer.pos
        val after = lexer.token()
        if (after !is Token.Keyword || after.text != "stream") {
            lexer.pos = mark
            return PdfRef(num, gen) to obj
        }
        return PdfRef(num, gen) to stream(obj, lexer)
    }

    private fun stream(dict: PdfDict, lexer: PdfLexer): PdfStream {
        // The keyword is followed by CRLF or LF; a lone CR is wrong but common enough to accept.
        if (lexer.peek() == 13) lexer.pos++
        if (lexer.peek() == 10) lexer.pos++
        val start = lexer.pos
        val declared = dict["Length"]?.let(lengthOf)
        val length = if (declared != null && declared >= 0 && endsAt(start + declared)) {
            declared
        } else {
            // The length is missing or wrong: find the end by looking for it.
            val end = source.indexOf(ENDSTREAM, start)
            if (end < 0) throw PdfFormatException("Stream without endstream")
            var e = end
            if (e > start && byteAt(e - 1) == 10) e--
            if (e > start && byteAt(e - 1) == 13) e--
            e - start
        }
        val len = length.toInt()
        return PdfStream(dict) { source.readBytes(start, len) }
    }

    /** Whether `endstream` follows [at], after at most an end of line. */
    private fun endsAt(at: Long): Boolean {
        if (at > source.length) return false
        val probe = source.readBytes(at, ENDSTREAM.size + 2)
        var i = 0
        while (i < probe.size && i < 2 && (probe[i].toInt() == 13 || probe[i].toInt() == 10 || probe[i].toInt() == 32)) i++
        if (probe.size - i < ENDSTREAM.size) return false
        for (j in ENDSTREAM.indices) if (probe[i + j] != ENDSTREAM[j]) return false
        return true
    }

    private fun byteAt(at: Long): Int = source.readBytes(at, 1).firstOrNull()?.toInt()?.and(0xFF) ?: -1

    companion object {
        private const val MAX_DEPTH = 64
        val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)
    }
}
