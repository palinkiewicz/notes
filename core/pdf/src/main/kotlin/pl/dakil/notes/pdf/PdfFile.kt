package pl.dakil.notes.pdf

import java.io.Closeable
import java.io.File

/** A rectangle in a PDF's own space: y up, from [x0], [y0] to [x1], [y1]. */
data class PdfBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0

    fun intersect(other: PdfBox): PdfBox {
        val r = PdfBox(maxOf(x0, other.x0), maxOf(y0, other.y0), minOf(x1, other.x1), minOf(y1, other.y1))
        return if (r.width > 0f && r.height > 0f) r else this
    }

    fun toArray(): PdfArray = PdfArray(listOf(x0, y0, x1, y1).map { PdfNumber.of(it) })

    companion object {
        /** US Letter: the default the specification gives a page that states no size at all. */
        val LETTER = PdfBox(0f, 0f, 612f, 792f)

        fun of(obj: PdfObject?): PdfBox? {
            val a = obj as? PdfArray ?: return null
            if (a.size < 4) return null
            val n = a.items.take(4).map { (it as? PdfNumber)?.toFloat() ?: return null }
            // Corners in either order: the specification allows any two opposite ones.
            return PdfBox(minOf(n[0], n[2]), minOf(n[1], n[3]), maxOf(n[0], n[2]), maxOf(n[1], n[3]))
                .takeIf { it.width > 0f && it.height > 0f }
        }
    }
}

/**
 * One page, with everything it inherits from the page tree already resolved onto it.
 *
 * [cropBox] is the part that is shown, clipped to [mediaBox]. [rotate] is clockwise, in quarter
 * turns' worth of degrees. [width] and [height] are the page as it is shown — after the rotation,
 * in points — which is the size a note made from it takes.
 */
class PdfPage(
    val index: Int,
    val dict: PdfDict,
    val resources: PdfObject?,
    val mediaBox: PdfBox,
    val cropBox: PdfBox,
    val rotate: Int,
    val userUnit: Float,
) {
    val width: Float get() = (if (rotate % 180 == 0) cropBox.width else cropBox.height) * userUnit
    val height: Float get() = (if (rotate % 180 == 0) cropBox.height else cropBox.width) * userUnit
}

/**
 * A PDF opened for reading its structure: the cross-reference, its objects, and its pages.
 *
 * Total in the way the note reader is: a cross-reference that is missing, points at the wrong
 * offsets or is otherwise broken sends it down a slower path that finds every object by scanning
 * the file, which is how every desktop reader treats a damaged PDF, and how most damaged PDFs open.
 */
class PdfFile private constructor(private val source: PdfSource) : Closeable {

    private sealed interface Entry {
        data class At(val offset: Long) : Entry
        data class InStream(val stream: Int, val index: Int) : Entry
        data object Free : Entry
    }

    private val entries = HashMap<Int, Entry>()
    private val cache = HashMap<Int, PdfObject>()
    private val objectStreams = HashMap<Int, Pair<IntArray, ByteArray>>()
    private val parser = PdfParser(source) { length -> (resolve(length) as? PdfNumber)?.toLong() }

    /** The trailer: `/Root`, `/Info`, `/Encrypt` and the rest, newest section first. */
    var trailer: PdfDict = PdfDict.EMPTY
        private set

    /** Whether the file was rebuilt by scanning, because its cross-reference could not be used. */
    var recovered: Boolean = false
        private set

    /**
     * Whether the file is encrypted. Its structure can still be read — names and numbers are never
     * encrypted, so the page sizes are right — but its strings and streams cannot be copied.
     */
    val isEncrypted: Boolean get() = trailer["Encrypt"] != null

    val pages: List<PdfPage> by lazy { readPages() }

    override fun close() = source.close()

    /** Follows [obj] while it is a reference, to the object it names. */
    fun resolve(obj: PdfObject?): PdfObject? {
        var o = obj
        var hops = 0
        while (o is PdfRef) {
            if (hops++ > 32) return null
            o = getObject(o.num)
        }
        return o
    }

    fun getObject(num: Int): PdfObject? {
        cache[num]?.let { return it }
        val obj = when (val e = entries[num]) {
            is Entry.At -> parser.indirect(e.offset)?.takeIf { it.first.num == num }?.second
            is Entry.InStream -> fromObjectStream(e.stream, e.index, num)
            Entry.Free, null -> null
        } ?: scanned[num]?.let { at -> parser.indirect(at)?.takeIf { it.first.num == num }?.second }
            ?: return null
        // Stream dictionaries are cached, their bytes are not: see [PdfStream].
        cache[num] = obj
        return obj
    }

    /** The decoded bytes of [stream]. */
    fun decode(stream: PdfStream): ByteArray = PdfFilters.decode(stream) { resolve(it) }

    fun canDecode(stream: PdfStream): Boolean = PdfFilters.canDecode(stream.dict) { resolve(it) }

    // ---- Cross-reference -------------------------------------------------------------------------

    private fun load() {
        // Usable means the catalog and its page tree resolve: an xref whose offsets are all off by
        // a few bytes parses perfectly and finds nothing.
        val ok = runCatching { readXref() }.getOrDefault(false) &&
            (resolve(trailer["Root"]) as? PdfDict)?.let { resolve(it["Pages"]) is PdfDict } == true
        if (!ok) {
            entries.clear()
            cache.clear()
            objectStreams.clear()
            recovered = true
            reconstruct()
        }
        if (resolve(trailer["Root"]) !is PdfDict) throw PdfFormatException("No document catalog")
    }

    private fun readXref(): Boolean {
        var offset = findStartXref() ?: return false
        val seen = HashSet<Long>()
        var first = true
        while (offset >= 0 && seen.add(offset)) {
            val section = readSection(offset) ?: return false
            // Newest first: an entry an update has already given is not overwritten by an older one.
            for ((num, entry) in section.first) entries.putIfAbsent(num, entry)
            trailer = if (first) section.second else PdfDict(section.second.entries + trailer.entries)
            first = false
            offset = (section.second["Prev"] as? PdfNumber)?.toLong() ?: -1
        }
        return true
    }

    private fun findStartXref(): Long? {
        val tail = minOf(source.length, 2048L)
        val at = source.length - tail
        val bytes = source.readBytes(at, tail.toInt())
        val text = String(bytes, Charsets.ISO_8859_1)
        val i = text.lastIndexOf("startxref")
        if (i < 0) return null
        val lexer = PdfLexer(ByteArraySource(bytes), (i + "startxref".length).toLong())
        val value = (lexer.token() as? Token.Num)?.text?.toLongOrNull() ?: return null
        return value.takeIf { it in 0 until source.length }
    }

    /** One cross-reference section and its trailer: a table, or a stream. */
    private fun readSection(offset: Long): Pair<Map<Int, Entry>, PdfDict>? {
        val lexer = PdfLexer(source, offset)
        val mark = lexer.pos
        val first = lexer.token()
        if (first is Token.Keyword && first.text == "xref") return readTable(lexer)
        lexer.pos = mark
        val (_, obj) = parser.indirect(offset) ?: return null
        val stream = obj as? PdfStream ?: return null
        if (stream.dict.name("Type") != "XRef") return null
        return readXrefStream(stream) to stream.dict
    }

    private fun readTable(lexer: PdfLexer): Pair<Map<Int, Entry>, PdfDict>? {
        val out = LinkedHashMap<Int, Entry>()
        while (true) {
            val mark = lexer.pos
            val t = lexer.token()
            if (t is Token.Keyword && t.text == "trailer") break
            val start = (t as? Token.Num)?.text?.toIntOrNull() ?: run { lexer.pos = mark; return null }
            val count = (lexer.token() as? Token.Num)?.text?.toIntOrNull() ?: return null
            for (i in 0 until count) {
                val off = (lexer.token() as? Token.Num)?.text?.toLongOrNull() ?: return null
                lexer.token() // generation
                val kind = lexer.token() as? Token.Keyword ?: return null
                out[start + i] = if (kind.text == "n" && off > 0) Entry.At(off) else Entry.Free
            }
        }
        val trailer = parser.value(lexer) as? PdfDict ?: return null
        // A hybrid file: the table is for old readers, the stream lists what is compressed. The
        // stream's entries fill in what the table marked free or left out.
        val hybrid = (trailer["XRefStm"] as? PdfNumber)?.toLong()?.let { at ->
            (parser.indirect(at)?.second as? PdfStream)?.let { readXrefStream(it) }
        }
        if (hybrid != null) {
            for ((num, entry) in hybrid) if (out[num] == null || out[num] == Entry.Free) out[num] = entry
        }
        return out to trailer
    }

    private fun readXrefStream(stream: PdfStream): Map<Int, Entry> {
        val dict = stream.dict
        val w = (dict["W"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.toInt() ?: 0 } ?: return emptyMap()
        if (w.size < 3) return emptyMap()
        val size = (dict["Size"] as? PdfNumber)?.toInt() ?: 0
        val index = (dict["Index"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.toInt() ?: 0 }
            ?: listOf(0, size)
        val data = decode(stream)
        val rowLen = w[0] + w[1] + w[2]
        val out = LinkedHashMap<Int, Entry>()
        var pos = 0
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i]
            val count = index[i + 1]
            for (k in 0 until count) {
                if (pos + rowLen > data.size) return out
                // A zero-width type field means type 1, by the specification's default.
                val type = if (w[0] == 0) 1L else field(data, pos, w[0])
                val f2 = field(data, pos + w[0], w[1])
                val f3 = field(data, pos + w[0] + w[1], w[2])
                pos += rowLen
                out[start + k] = when (type) {
                    1L -> Entry.At(f2)
                    2L -> Entry.InStream(f2.toInt(), f3.toInt())
                    else -> Entry.Free
                }
            }
            i += 2
        }
        return out
    }

    private fun field(data: ByteArray, at: Int, width: Int): Long {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        return v
    }

    private fun fromObjectStream(streamNum: Int, index: Int, num: Int): PdfObject? {
        val (offsets, data) = objectStreams.getOrPut(streamNum) {
            val stream = getObject(streamNum) as? PdfStream ?: return null
            val n = (stream.dict["N"] as? PdfNumber)?.toInt() ?: return null
            val first = (stream.dict["First"] as? PdfNumber)?.toInt() ?: return null
            val bytes = decode(stream)
            val header = PdfLexer(ByteArraySource(bytes))
            val table = IntArray(n * 2)
            for (i in 0 until n * 2) table[i] = (header.token() as? Token.Num)?.text?.toIntOrNull() ?: return null
            // Offsets are relative to `/First`; store them absolute.
            for (i in 0 until n) table[2 * i + 1] += first
            table to bytes
        }
        var at = -1
        if (index * 2 + 1 < offsets.size && offsets[index * 2] == num) {
            at = offsets[index * 2 + 1]
        } else {
            // The index is a hint; a writer that got it wrong still listed the object.
            for (i in 0 until offsets.size / 2) if (offsets[2 * i] == num) at = offsets[2 * i + 1]
        }
        if (at < 0 || at >= data.size) return null
        return PdfParser(ByteArraySource(data)).value(PdfLexer(ByteArraySource(data), at.toLong()))
    }

    // ---- Recovery --------------------------------------------------------------------------------

    /**
     * Where each object really starts, found by scanning, for the one a cross-reference that is
     * otherwise sound gets wrong. Built the first time it is needed, which in a healthy file is never.
     */
    private val scanned: Map<Int, Long> by lazy { scanObjects() }

    private fun scanObjects(): Map<Int, Long> {
        val objMarker = "obj".toByteArray(Charsets.ISO_8859_1)
        var from = 0L
        val found = LinkedHashMap<Int, Long>()
        while (true) {
            val at = source.indexOf(objMarker, from)
            if (at < 0) break
            from = at + 3
            objectStartBefore(at)?.let { (num, start) -> found[num] = start }
        }
        return found
    }

    /**
     * Finds every `n g obj` in the file, the last of each number winning, and a trailer for them.
     */
    private fun reconstruct() {
        val found = scanned
        for ((num, start) in found) entries[num] = Entry.At(start)

        // Objects inside object streams, unless a direct object already has the number.
        for (num in found.keys.toList()) {
            val stream = getObject(num) as? PdfStream ?: continue
            if (stream.dict.name("Type") != "ObjStm") continue
            val n = (stream.dict["N"] as? PdfNumber)?.toInt() ?: continue
            val bytes = runCatching { decode(stream) }.getOrNull() ?: continue
            val header = PdfLexer(ByteArraySource(bytes))
            for (i in 0 until n) {
                val inner = (header.token() as? Token.Num)?.text?.toIntOrNull() ?: break
                header.token()
                entries.putIfAbsent(inner, Entry.InStream(num, i))
            }
        }

        trailer = lastTrailer() ?: PdfDict.EMPTY
        if (resolve(trailer["Root"]) !is PdfDict) {
            val catalog = entries.keys.sorted().firstOrNull { n ->
                (runCatching { getObject(n) }.getOrNull() as? PdfDict)?.name("Type") == "Catalog"
            }
            if (catalog != null) trailer = trailer.with("Root", PdfRef(catalog))
        }
    }

    /** For an `obj` keyword at [at]: the object number and where `n g obj` starts, if that is what it is. */
    private fun objectStartBefore(at: Long): Pair<Int, Long>? {
        val after = source.readBytes(at + 3, 1).firstOrNull()?.toInt()?.and(0xFF) ?: -1
        if (after >= 0 && isRegular(after)) return null
        val back = minOf(at, 32L)
        val bytes = source.readBytes(at - back, back.toInt())
        var i = bytes.size - 1
        fun skipWhite(): Boolean { val s = i; while (i >= 0 && isWhite(bytes[i].toInt())) i--; return i < s }
        fun digits(): String? {
            val end = i
            while (i >= 0 && bytes[i] in '0'.code.toByte()..'9'.code.toByte()) i--
            return if (i == end) null else String(bytes, i + 1, end - i, Charsets.ISO_8859_1)
        }
        if (!skipWhite()) return null
        digits() ?: return null
        if (!skipWhite()) return null
        val num = digits()?.toIntOrNull() ?: return null
        if (i >= 0 && isRegular(bytes[i].toInt() and 0xFF)) return null
        return num to (at - back + i + 1)
    }

    private fun lastTrailer(): PdfDict? {
        val marker = "trailer".toByteArray(Charsets.ISO_8859_1)
        var at = -1L
        var from = 0L
        while (true) {
            val next = source.indexOf(marker, from)
            if (next < 0) break
            at = next
            from = next + marker.size
        }
        if (at >= 0) {
            val dict = runCatching { parser.value(PdfLexer(source, at + marker.size)) as? PdfDict }.getOrNull()
            if (dict != null && dict["Root"] != null) return dict
        }
        // A file with only cross-reference streams keeps its trailer keys on them.
        return entries.keys.sortedDescending().firstNotNullOfOrNull { n ->
            (runCatching { getObject(n) }.getOrNull() as? PdfStream)?.dict
                ?.takeIf { it.name("Type") == "XRef" && it["Root"] != null }
        }
    }

    // ---- Pages -----------------------------------------------------------------------------------

    private fun readPages(): List<PdfPage> {
        val catalog = resolve(trailer["Root"]) as? PdfDict ?: return emptyList()
        val out = ArrayList<PdfPage>()
        val visited = HashSet<Int>()

        fun walk(node: PdfObject?, resources: PdfObject?, media: PdfBox?, crop: PdfBox?, rotate: Int?, depth: Int) {
            if (depth > 64) return
            if (node is PdfRef && !visited.add(node.num)) return
            val dict = resolve(node) as? PdfDict ?: return
            val res = dict["Resources"] ?: resources
            val mb = PdfBox.of(resolve(dict["MediaBox"])) ?: media
            val cb = PdfBox.of(resolve(dict["CropBox"])) ?: crop
            val rot = (resolve(dict["Rotate"]) as? PdfNumber)?.toInt() ?: rotate
            val kids = resolve(dict["Kids"]) as? PdfArray
            if (kids != null && dict.name("Type") != "Page") {
                for (kid in kids.items) walk(kid, res, mb, cb, rot, depth + 1)
                return
            }
            // An empty branch of the tree, not a page.
            if (dict.name("Type") == "Pages") return
            val mediaBox = mb ?: PdfBox.LETTER
            val unit = (resolve(dict["UserUnit"]) as? PdfNumber)?.toFloat()?.takeIf { it > 0f } ?: 1f
            out += PdfPage(
                index = out.size,
                dict = dict,
                resources = res,
                mediaBox = mediaBox,
                cropBox = cb?.intersect(mediaBox) ?: mediaBox,
                rotate = (((rot ?: 0) % 360) + 360) % 360 / 90 * 90,
                userUnit = unit,
            )
        }

        walk(catalog["Pages"], null, null, null, null, 0)
        return out
    }

    companion object {
        fun open(source: PdfSource): PdfFile = PdfFile(source).also {
            try {
                it.load()
            } catch (e: Exception) {
                it.close()
                throw e as? PdfFormatException ?: PdfFormatException("Not a readable PDF", e)
            }
        }

        fun open(file: File): PdfFile = open(FileSource(file))
        fun open(bytes: ByteArray): PdfFile = open(ByteArraySource(bytes))
    }
}
