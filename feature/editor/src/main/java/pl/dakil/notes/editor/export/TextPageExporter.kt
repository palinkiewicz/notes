package pl.dakil.notes.editor.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.editor.markdown.ImageLoader
import pl.dakil.notes.editor.markdown.ImageSource
import pl.dakil.notes.editor.markdown.MarkdownImages
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdImage
import pl.dakil.notes.editor.markdown.toAnnotatedString
import pl.dakil.notes.ui.export.drawIntoCanvas
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Renders a `.md` note to PDF or PNG.
 *
 * Simpler than [InkPageExporter] on purpose, and a first cut rather than a finished renderer: the
 * whole document is laid out as one [MultiParagraph] and cut into pages by walking its own line
 * heights against a fixed page budget, painted with [drawIntoCanvas] the same way the ink path is.
 * Styled text — bold, italic, heading size, monospace code, from the
 * [androidx.compose.ui.text.AnnotatedString]'s span styles — comes through; fence backgrounds, table
 * gridlines and quote bars, which the live editor draws as separate decorations rather than as
 * spans, do not. That is a real limitation of this first pass, not an oversight — see the module's
 * export summary.
 *
 * Block images do come through, by the same trick the screen uses: [MarkdownImages] makes each
 * image's line as tall as the picture, and the picture is painted over the room that line kept. A
 * [MultiParagraph] rather than a [Paragraph] because that room is a *paragraph* style, which a
 * single [Paragraph] has no way to be given — with one, every picture's line collapsed to nothing.
 */
object TextPageExporter {

    // A4-ish, in points — there is no document page size for a `.md` note to inherit.
    private const val PAGE_WIDTH_PT = 595f
    private const val PAGE_HEIGHT_PT = 842f
    private const val MARGIN_PT = 56f
    private const val PNG_DPI = 150f
    private val TEXT_COLOR = Color(0xFF1B1B1F)

    /** Behind an image that could not be read, and the alt text said in its place. */
    private val MISSING_FILL = Color(0xFFE3E1EC)
    private val MISSING_TEXT = Color(0xFF46464F)

    /** [pages] are 0-based; any past the end of the document are skipped. Null means every page. */
    suspend fun exportToPdf(
        context: Context,
        markdown: String,
        attributes: Boolean,
        pages: List<Int>?,
        source: ImageSource,
    ): File {
        val fontResolver = createFontFamilyResolver(context)
        val ptToPx = 1f
        val layout = layOut(markdown, fontResolver, ptToPx, attributes, source)
        val widthPt = PAGE_WIDTH_PT.toInt()
        val heightPt = PAGE_HEIGHT_PT.toInt()

        val document = PdfDocument()
        for ((index, page) in selected(layout.breaks, pages).withIndex()) {
            // Decoded at the PNG's resolution rather than at one pixel a point: a PDF is for
            // printing, and a photograph at 72 dpi prints as a blur.
            val bitmaps = decodePage(layout, page, PNG_DPI / 72f)
            val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
            val pdfPage = document.startPage(info)
            drawIntoCanvas(pdfPage.canvas, widthPt, heightPt, Density(ptToPx)) {
                drawPage(layout, page, ptToPx, bitmaps, fontResolver)
            }
            document.finishPage(pdfPage)
            bitmaps.values.forEach { it.asAndroidBitmap().recycle() }
        }
        val file = File(exportDir(context), "export.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    suspend fun exportToPngs(
        context: Context,
        markdown: String,
        attributes: Boolean,
        pages: List<Int>?,
        source: ImageSource,
    ): List<File> {
        val fontResolver = createFontFamilyResolver(context)
        val ptToPx = PNG_DPI / 72f
        val layout = layOut(markdown, fontResolver, ptToPx, attributes, source)
        val widthPx = (PAGE_WIDTH_PT * ptToPx).toInt().coerceAtLeast(1)
        val heightPx = (PAGE_HEIGHT_PT * ptToPx).toInt().coerceAtLeast(1)

        // Once, before the loop: it empties the folder, and asked per page it deleted every page
        // but the last before anyone could save or share them.
        val dir = exportDir(context)
        val files = ArrayList<File>()
        for (page in selected(layout.breaks, pages)) {
            val bitmaps = decodePage(layout, page, 1f)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawIntoCanvas(canvas, widthPx, heightPx, Density(ptToPx)) {
                drawPage(layout, page, ptToPx, bitmaps, fontResolver)
            }
            val file = File(dir, "export_${page + 1}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            bitmaps.values.forEach { it.asAndroidBitmap().recycle() }
            files += file
        }
        return files
    }

    /**
     * How many pages the note comes to, for the export sheet to check a page selection against.
     *
     * Laid out at the PDF's scale. The PNG path lays out the same text at a higher resolution,
     * which scales every line height alike, so it cuts at the same lines. Reads every image's size,
     * because a picture is as much a part of the page budget as the lines around it.
     */
    suspend fun pageCount(context: Context, markdown: String, attributes: Boolean, source: ImageSource): Int =
        layOut(markdown, createFontFamilyResolver(context), 1f, attributes, source).breaks.size

    private fun selected(breaks: List<Float>, pages: List<Int>?): List<Int> =
        pages?.filter { it in breaks.indices } ?: breaks.indices.toList()

    private fun exportDir(context: Context): File {
        val dir = context.cacheDir.resolve("exports")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun contentHeightPx(ptToPx: Float): Float = (PAGE_HEIGHT_PT - 2 * MARGIN_PT) * ptToPx

    /** The note laid out once, with what each page needs to find and draw its pictures. */
    private class Layout(
        val paragraph: MultiParagraph,
        val images: MarkdownImages,
        val pictures: List<MdImage>,
        val breaks: List<Float>,
    ) {
        /** The pictures whose line starts on [page], which is the page a line is cut onto. */
        fun picturesOn(page: Int): List<MdImage> {
            val top = breaks[page]
            val bottom = breaks.getOrElse(page + 1) { Float.POSITIVE_INFINITY }
            return pictures.filter { lineTopOf(it) in top..<bottom }
        }

        fun lineOf(image: MdImage): Int =
            paragraph.getLineForOffset(image.offset.coerceIn(0, paragraph.intrinsics.annotatedString.length))

        private fun lineTopOf(image: MdImage): Float = paragraph.getLineTop(lineOf(image))
    }

    private suspend fun layOut(
        markdown: String,
        fontResolver: FontFamily.Resolver,
        ptToPx: Float,
        attributes: Boolean,
        source: ImageSource,
    ): Layout {
        val density = Density(ptToPx)
        val plan = MarkdownRenderer.plan(markdown)
        val widthPx = ((PAGE_WIDTH_PT - 2 * MARGIN_PT) * ptToPx).toInt().coerceAtLeast(1)
        // The same sizing the screen uses, against the page's column: a picture comes out of the
        // printer the shape and the share of the line it had in the note.
        val images = MarkdownImages(source, density).apply { columnPx = widthPx }
        val pictures = plan.decorations.filterIsInstance<MdImage>()
        for (url in pictures.mapTo(LinkedHashSet()) { it.url }) images.measure(url)
        val annotated = plan.toAnnotatedString(markdown, exportMarkdownStyles(attributes), images)
        val paragraph = MultiParagraph(
            annotatedString = annotated,
            style = TextStyle(fontSize = 14.sp, color = TEXT_COLOR),
            constraints = Constraints(maxWidth = widthPx),
            density = density,
            fontFamilyResolver = fontResolver,
        )
        return Layout(paragraph, images, pictures, pageBreaks(paragraph, contentHeightPx(ptToPx)))
    }

    /**
     * Where each page's content starts, in the paragraph's own pixel coordinates.
     *
     * Cut at a line boundary rather than a blind `height / budget` division, so a page break never
     * falls in the middle of a line of text — or of a picture, which is one line. Always has at
     * least one entry, so an empty or single-page document still gets the one page it needs.
     */
    private fun pageBreaks(paragraph: MultiParagraph, budgetPx: Float): List<Float> {
        val breaks = mutableListOf(0f)
        if (budgetPx <= 0f || paragraph.lineCount == 0) return breaks
        var pageStart = 0f
        for (line in 0 until paragraph.lineCount) {
            val bottom = paragraph.getLineBottom(line)
            val top = paragraph.getLineTop(line)
            if (bottom - pageStart > budgetPx && top > pageStart) {
                breaks += top
                pageStart = top
            }
        }
        return breaks
    }

    /**
     * The pictures on [page], decoded at [oversample] times the size they are drawn.
     *
     * One page at a time and recycled after it, the way [InkPageExporter] treats a sheet's images,
     * so a note of photographs never holds all of them at once.
     */
    private suspend fun decodePage(layout: Layout, page: Int, oversample: Float): Map<String, ImageBitmap> {
        val bitmaps = HashMap<String, ImageBitmap>()
        for (image in layout.picturesOn(page)) {
            if (image.url in bitmaps || layout.images.sizeOf(image.url) == null) continue
            val width = (layout.images.displaySize(image.url).width * oversample).roundToInt()
            val bytes = layout.images.source.bytes(image.url) ?: continue
            val decoded = withContext(Dispatchers.Default) { ImageLoader.decode(bytes, width) } ?: continue
            bitmaps[image.url] = decoded
        }
        return bitmaps
    }

    private fun DrawScope.drawPage(
        layout: Layout,
        pageIndex: Int,
        ptToPx: Float,
        bitmaps: Map<String, ImageBitmap>,
        fontResolver: FontFamily.Resolver,
    ) {
        val marginPx = MARGIN_PT * ptToPx
        val budgetPx = contentHeightPx(ptToPx)
        val contentTop = layout.breaks[pageIndex]
        clipRect(left = marginPx, top = marginPx, right = size.width - marginPx, bottom = marginPx + budgetPx) {
            translate(left = marginPx, top = marginPx - contentTop) {
                layout.paragraph.paint(drawContext.canvas)
                for (image in layout.picturesOn(pageIndex)) {
                    drawPicture(layout, image, bitmaps[image.url], ptToPx, fontResolver)
                }
            }
        }
    }

    /** One block image, centred in the room its line kept and drawn as the screen draws it. */
    private fun DrawScope.drawPicture(
        layout: Layout,
        image: MdImage,
        bitmap: ImageBitmap?,
        ptToPx: Float,
        fontResolver: FontFamily.Resolver,
    ) {
        val size = layout.images.displaySize(image.url)
        val line = layout.lineOf(image)
        val top = layout.paragraph.getLineTop(line)
        val bottom = layout.paragraph.getLineBottom(line)
        val y = top + ((bottom - top) - size.height) / 2f
        val corner = 8f * ptToPx
        val frame = Path().apply {
            addRoundRect(RoundRect(0f, y, size.width.toFloat(), y + size.height, CornerRadius(corner)))
        }
        clipPath(frame) {
            if (bitmap != null) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(0, y.roundToInt()),
                    dstSize = size,
                    filterQuality = FilterQuality.High,
                )
                return@clipPath
            }
            // Said in words, as on screen: the alt text is what the author wrote to stand in for
            // the image, and it is the one thing that says which image is gone.
            drawRect(MISSING_FILL, topLeft = Offset(0f, y), size = Size(size.width.toFloat(), size.height.toFloat()))
            val label = image.alt.ifBlank { image.url.substringAfterLast('/').take(80) }
            val pad = 12f * ptToPx
            val text = Paragraph(
                text = label,
                style = TextStyle(fontSize = 12.sp, color = MISSING_TEXT),
                constraints = Constraints(maxWidth = (size.width - 2 * pad).toInt().coerceAtLeast(1)),
                density = Density(ptToPx),
                fontFamilyResolver = fontResolver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            translate(left = pad, top = y + (size.height - text.height) / 2f) {
                text.paint(drawContext.canvas)
            }
        }
    }
}
