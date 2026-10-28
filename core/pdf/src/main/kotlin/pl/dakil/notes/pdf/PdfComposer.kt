package pl.dakil.notes.pdf

import java.io.OutputStream
import java.util.IdentityHashMap

/**
 * Copies objects out of [source] into [writer], renumbering as it goes.
 *
 * Each source object is copied once however many times it is referred to, so a font every page of a
 * lecture uses arrives once, and a picture's stream is copied byte for byte — its filters with it —
 * so nothing is ever re-encoded. A reference back up the page tree is cut: following `/Parent`, or
 * anything else that leads to a page, would copy the whole of the source document to bring one page
 * across.
 */
internal class PdfCopier(private val source: PdfFile, private val writer: PdfWriter) {

    private val mapped = HashMap<Int, PdfRef>()
    private val queue = ArrayDeque<Int>()

    fun copy(obj: PdfObject?): PdfObject = when (obj) {
        null -> PdfNull
        is PdfRef -> map(obj)
        is PdfArray -> PdfArray(obj.items.map { copy(it) })
        is PdfDict -> copyDict(obj)
        is PdfStream -> PdfStream(copyDict(obj.dict.without("Length")), obj::raw)
        else -> obj
    }

    fun copyDict(dict: PdfDict): PdfDict {
        val out = LinkedHashMap<String, PdfObject>()
        for ((k, v) in dict.entries) {
            if (k == "Parent") continue
            out[k] = copy(v)
        }
        return PdfDict(out)
    }

    private fun map(ref: PdfRef): PdfObject {
        mapped[ref.num]?.let { return it }
        val target = source.getObject(ref.num) ?: return PdfNull
        if (target is PdfDict && target.name("Type").let { it == "Page" || it == "Pages" }) return PdfNull
        val fresh = writer.allocate()
        mapped[ref.num] = fresh
        queue += ref.num
        return fresh
    }

    /** Writes every object referred to so far, and everything they refer to in turn. */
    fun drain() {
        while (queue.isNotEmpty()) {
            val num = queue.removeFirst()
            val obj = source.getObject(num) ?: PdfNull
            writer.write(mapped.getValue(num), copy(obj))
        }
    }
}

/**
 * Builds a PDF whose pages are drawn over pages of other PDFs.
 *
 * The overlay is what the note's own exporter made — ink, text, pictures, all vector — and each
 * backdrop is a page of an original PDF, brought across as a Form XObject: its content and resources
 * copied unchanged, drawn underneath. A PDF page in the export is therefore the page that was
 * imported, text still selectable and searchable, scans at their original resolution.
 */
object PdfComposer {

    /**
     * One page of a source PDF shown at [left], [top], [width] × [height] on the composed page,
     * measured from the top-left corner in points, as the sheet measures.
     */
    class Backdrop(
        val source: PdfFile,
        val page: Int,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * One page of the output, [width] × [height] points.
     *
     * [overlayPage] is the overlay's page drawn on top, or null for none. [paper] is the colour the
     * page is filled with first, as ARGB, or null to leave it unpainted.
     */
    class Page(
        val width: Float,
        val height: Float,
        val overlayPage: Int?,
        val paper: Int?,
        val backdrops: List<Backdrop>,
    )

    /**
     * Whether page [index] of [source] can be carried across as vectors.
     *
     * An encrypted file cannot — its streams would arrive still encrypted, with no key — and a page
     * whose content has to be decoded to be joined, or to have its annotations drawn after it, needs
     * filters this can undo. Anything that is not embeddable is drawn as a picture instead.
     */
    fun canEmbed(source: PdfFile, index: Int): Boolean = runCatching {
        if (source.isEncrypted) return false
        val page = source.pages.getOrNull(index) ?: return false
        val contents = contentStreams(source, page)
        val decodeNeeded = contents.size > 1 || appearances(source, page).isNotEmpty()
        !decodeNeeded || contents.all { source.canDecode(it) }
    }.getOrDefault(false)

    fun compose(overlay: PdfFile?, pages: List<Page>, out: OutputStream) {
        val writer = PdfWriter(out)
        val copiers = IdentityHashMap<PdfFile, PdfCopier>()
        fun copier(file: PdfFile) = copiers.getOrPut(file) { PdfCopier(file, writer) }
        val forms = IdentityHashMap<PdfFile, HashMap<Int, PdfRef>>()

        val tree = writer.allocate()
        val kids = ArrayList<PdfObject>(pages.size)

        for (page in pages) {
            val overlayInfo = page.overlayPage?.let { overlay?.pages?.getOrNull(it) }
            val resources = overlayInfo?.let { resourcesOf(overlay!!, it.resources, copier(overlay)) }
                ?: LinkedHashMap()
            val xobjects = (resources["XObject"] as? PdfDict)?.entries?.let { LinkedHashMap(it) } ?: LinkedHashMap()

            val prefix = StringBuilder()
            page.paper?.let { argb ->
                if ((argb ushr 24) != 0) {
                    prefix.append("q ").append(rgb(argb)).append(" rg 0 0 ")
                        .append(formatNumber(page.width.exact())).append(' ')
                        .append(formatNumber(page.height.exact())).append(" re f Q\n")
                }
            }
            for (b in page.backdrops) {
                val src = b.source.pages.getOrNull(b.page) ?: continue
                val form = forms.getOrPut(b.source) { HashMap() }.getOrPut(b.page) {
                    writer.add(formOf(b.source, src, copier(b.source), writer))
                }
                val name = freeName(xobjects, "DkPdf")
                xobjects[name] = form
                val x0 = b.left.exact()
                val y0 = page.height.exact() - b.top.exact() - b.height.exact()
                // The page is paper: white where it stands, as every viewer draws it.
                prefix.append("q 1 1 1 rg ").append(numbers(x0, y0, b.width.exact(), b.height.exact()))
                    .append(" re f Q\n")
                prefix.append("q ").append(numbers(*placement(src, x0, y0, b.width.exact(), b.height.exact())))
                    .append(" cm /").append(name).append(" Do Q\n")
            }

            val contents = ArrayList<PdfObject>()
            if (overlayInfo != null) {
                // The overlay's own origin carried to this page's top-left: its height can differ
                // from this page's by the rounding the overlay's writer gave its page size.
                val box = overlayInfo.mediaBox
                prefix.append("q 1 0 0 1 ").append(numbers(-box.x0.exact(), page.height.exact() - box.y1.exact()))
                    .append(" cm\n")
                contents += writer.add(PdfStream(PdfDict.EMPTY, prefix.toString().toByteArray(Charsets.ISO_8859_1)))
                val c = copier(overlay!!)
                for (stream in contentRefs(overlay, overlayInfo)) contents += c.copy(stream)
                contents += writer.add(PdfStream(PdfDict.EMPTY, "\nQ\n".toByteArray(Charsets.ISO_8859_1)))
            } else if (prefix.isNotEmpty()) {
                contents += writer.add(PdfStream(PdfDict.EMPTY, prefix.toString().toByteArray(Charsets.ISO_8859_1)))
            }

            if (xobjects.isNotEmpty()) resources["XObject"] = PdfDict(xobjects)
            val dict = LinkedHashMap<String, PdfObject>()
            dict["Type"] = PdfName("Page")
            dict["Parent"] = tree
            dict["MediaBox"] = PdfBox(0f, 0f, page.width, page.height).toArray()
            dict["Resources"] = PdfDict(resources)
            dict["Contents"] = PdfArray(contents)
            // The overlay's blending space, so a highlighter's multiply means what it meant there.
            overlayInfo?.dict?.get("Group")?.let { dict["Group"] = copier(overlay!!).copy(it) }
            kids += writer.add(PdfDict(dict))
        }

        for (c in copiers.values) c.drain()

        writer.write(tree, PdfDict.of("Type" to PdfName("Pages"), "Kids" to PdfArray(kids), "Count" to PdfNumber.of(kids.size)))
        val catalog = writer.add(PdfDict.of("Type" to PdfName("Catalog"), "Pages" to tree))
        val info = writer.add(PdfDict.of("Producer" to PdfString("DakNote".toByteArray(Charsets.ISO_8859_1))))
        writer.finish(catalog, info)
    }

    /**
     * The matrix that draws a source page's crop box into the rect [x0], [y0], [w] × [h] of the
     * output, y up: crop corner to the origin, the page's own `/Rotate` applied as a viewer would,
     * scaled to fit, and moved into place.
     */
    internal fun placement(page: PdfPage, x0: Double, y0: Double, w: Double, h: Double): DoubleArray {
        val crop = page.cropBox
        val cw = crop.x1.exact() - crop.x0.exact()
        val ch = crop.y1.exact() - crop.y0.exact()
        val rotate = when (page.rotate) {
            90 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0, 0.0, cw)
            180 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0, cw, ch)
            270 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0, ch, 0.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
        val (dw, dh) = if (page.rotate % 180 == 0) cw to ch else ch to cw
        var m = doubleArrayOf(1.0, 0.0, 0.0, 1.0, -crop.x0.exact(), -crop.y0.exact())
        m = times(m, rotate)
        m = times(m, doubleArrayOf(w / dw, 0.0, 0.0, h / dh, 0.0, 0.0))
        m = times(m, doubleArrayOf(1.0, 0.0, 0.0, 1.0, x0, y0))
        return m
    }

    /** [m1] then [m2], in PDF's row-vector convention. */
    private fun times(m1: DoubleArray, m2: DoubleArray): DoubleArray = doubleArrayOf(
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    )

    /**
     * A source page as a Form XObject.
     *
     * One content stream with nothing drawn after it is copied as it is, filter and all. Otherwise
     * the streams are decoded and joined — they may split a text object between them, so they cannot
     * become separate forms — and the page's annotations are drawn after them from their appearance
     * streams: a highlight already in the PDF is part of the page as its reader saw it.
     */
    private fun formOf(file: PdfFile, page: PdfPage, copier: PdfCopier, writer: PdfWriter): PdfStream {
        val contents = contentStreams(file, page)
        val annots = appearances(file, page)
        val resources = resourcesOf(file, page.resources, copier)

        val dict = LinkedHashMap<String, PdfObject>()
        dict["Type"] = PdfName("XObject")
        dict["Subtype"] = PdfName("Form")
        dict["BBox"] = page.cropBox.toArray()
        page.dict["Group"]?.let { dict["Group"] = copier.copy(it) }

        if (contents.size == 1 && annots.isEmpty()) {
            val only = contents.single()
            only.dict["Filter"]?.let { dict["Filter"] = copier.copy(it) }
            only.dict["DecodeParms"]?.let { dict["DecodeParms"] = copier.copy(it) }
            dict["Resources"] = PdfDict(resources)
            return PdfStream(PdfDict(dict), only::raw)
        }

        val body = java.io.ByteArrayOutputStream()
        body.write("q\n".toByteArray(Charsets.ISO_8859_1))
        for (stream in contents) {
            body.write(file.decode(stream))
            body.write('\n'.code)
        }
        body.write("Q\n".toByteArray(Charsets.ISO_8859_1))
        if (annots.isNotEmpty()) {
            val xobjects = (resources["XObject"] as? PdfDict)?.entries?.let { LinkedHashMap(it) } ?: LinkedHashMap()
            for (a in annots) {
                val ap = a.appearance
                val apDict = copier.copyDict(ap.dict.without("Length"))
                    .with("Type", PdfName("XObject")).with("Subtype", PdfName("Form"))
                val ref = writer.add(PdfStream(apDict, ap::raw))
                val name = freeName(xobjects, "DkAnn")
                xobjects[name] = ref
                body.write("q ${numbers(*a.matrix)} cm /$name Do Q\n".toByteArray(Charsets.ISO_8859_1))
            }
            resources["XObject"] = PdfDict(xobjects)
        }
        dict["Resources"] = PdfDict(resources)
        dict["Filter"] = PdfName("FlateDecode")
        val deflated = PdfFilters.deflate(body.toByteArray())
        return PdfStream(PdfDict(dict), deflated)
    }

    /** A page's resources, copied into a dictionary of its own that names can be added to. */
    private fun resourcesOf(file: PdfFile, resources: PdfObject?, copier: PdfCopier): LinkedHashMap<String, PdfObject> {
        val dict = file.resolve(resources) as? PdfDict ?: return LinkedHashMap()
        val out = LinkedHashMap<String, PdfObject>()
        for ((k, v) in dict.entries) {
            out[k] = if (k == "XObject") {
                // Resolved and copied entry by entry, so names can be added beside them.
                val x = file.resolve(v) as? PdfDict
                if (x == null) copier.copy(v) else PdfDict(x.entries.mapValuesTo(LinkedHashMap()) { copier.copy(it.value) })
            } else {
                copier.copy(v)
            }
        }
        return out
    }

    private fun contentRefs(file: PdfFile, page: PdfPage): List<PdfObject> =
        when (val c = page.dict["Contents"]) {
            is PdfRef -> when (val r = file.resolve(c)) {
                is PdfArray -> r.items
                is PdfStream -> listOf(c)
                else -> emptyList()
            }
            is PdfArray -> c.items
            else -> emptyList()
        }

    private fun contentStreams(file: PdfFile, page: PdfPage): List<PdfStream> =
        contentRefs(file, page).mapNotNull { file.resolve(it) as? PdfStream }

    private class Appearance(val appearance: PdfStream, val matrix: DoubleArray)

    /**
     * The annotations a viewer would draw on [page], each with the matrix that puts its appearance
     * stream onto its rect, as the specification lays out: the stream's bounding box, through its own
     * matrix, scaled and moved onto `/Rect`.
     */
    private fun appearances(file: PdfFile, page: PdfPage): List<Appearance> {
        val annots = file.resolve(page.dict["Annots"]) as? PdfArray ?: return emptyList()
        val out = ArrayList<Appearance>()
        for (item in annots.items) {
            val a = file.resolve(item) as? PdfDict ?: continue
            val subtype = a.name("Subtype")
            if (subtype == "Popup" || subtype == "Link") continue
            val flags = (file.resolve(a["F"]) as? PdfNumber)?.toInt() ?: 0
            if (flags and (HIDDEN or NO_VIEW) != 0) continue
            val ap = file.resolve(a["AP"]) as? PdfDict ?: continue
            val normal = when (val n = file.resolve(ap["N"])) {
                is PdfStream -> n
                is PdfDict -> a.name("AS")?.let { file.resolve(n[it]) as? PdfStream }
                else -> null
            } ?: continue
            val rect = PdfBox.of(file.resolve(a["Rect"])) ?: continue
            val bbox = PdfBox.of(file.resolve(normal.dict["BBox"])) ?: continue
            val m = (file.resolve(normal.dict["Matrix"]) as? PdfArray)?.items
                ?.map { (file.resolve(it) as? PdfNumber)?.toDouble() ?: 0.0 }
                ?.takeIf { it.size == 6 }?.toDoubleArray()
                ?: doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val xs = DoubleArray(4)
            val ys = DoubleArray(4)
            val corners = listOf(bbox.x0 to bbox.y0, bbox.x1 to bbox.y0, bbox.x0 to bbox.y1, bbox.x1 to bbox.y1)
            for ((i, c) in corners.withIndex()) {
                xs[i] = m[0] * c.first.exact() + m[2] * c.second.exact() + m[4]
                ys[i] = m[1] * c.first.exact() + m[3] * c.second.exact() + m[5]
            }
            val tx0 = xs.min()
            val ty0 = ys.min()
            val tw = xs.max() - tx0
            val th = ys.max() - ty0
            if (tw <= 0.0 || th <= 0.0) continue
            val sx = (rect.x1.exact() - rect.x0.exact()) / tw
            val sy = (rect.y1.exact() - rect.y0.exact()) / th
            out += Appearance(normal, doubleArrayOf(sx, 0.0, 0.0, sy, rect.x0.exact() - tx0 * sx, rect.y0.exact() - ty0 * sy))
        }
        return out
    }

    private const val HIDDEN = 2
    private const val NO_VIEW = 32

    private fun freeName(taken: Map<String, *>, prefix: String): String {
        var n = 0
        while ("$prefix$n" in taken) n++
        return "$prefix$n"
    }

    private fun rgb(argb: Int): String = numbers(
        ((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0,
    )

    private fun numbers(vararg values: Double): String = values.joinToString(" ") { formatNumber(it) }
}
