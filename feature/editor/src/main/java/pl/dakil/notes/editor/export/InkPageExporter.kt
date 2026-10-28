package pl.dakil.notes.editor.export

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.Rect as ModelRect
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.PdfPageBlock
import pl.dakil.notes.pdf.PdfComposer
import pl.dakil.notes.pdf.PdfFile
import pl.dakil.notes.ui.sheet.PdfPages
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.ceil
import pl.dakil.notes.model.RenderLayer
import pl.dakil.notes.model.RenderOrder
import androidx.compose.ui.geometry.Offset
import pl.dakil.notes.model.frame
import pl.dakil.notes.ui.sheet.decodePrintedPicture
import pl.dakil.notes.ui.sheet.imageTintFilter
import pl.dakil.notes.ui.sheet.inBlockFrame
import kotlin.math.roundToInt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.toAnnotatedString
import pl.dakil.notes.model.ExportColorPreset
import pl.dakil.notes.model.ExportOptions
import pl.dakil.notes.model.RasterScale
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.ui.export.drawIntoCanvas
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.ui.sheet.StrokeRenderer
import java.io.File
import java.io.FileOutputStream

/**
 * Renders an ink [Sheet] to PDF or PNG, one page at a time, reusing the on-screen [SheetPainter] and
 * [StrokeRenderer] through [drawIntoCanvas] instead of a second, hand-rolled drawing path.
 *
 * Typed text is laid out separately with a plain [Paragraph] in a fixed near-black colour — never
 * recoloured by [ExportColorPreset], since it has no stored colour of its own to transform; only the
 * page background, pattern and ink strokes do.
 */
object InkPageExporter {

    /** What a PDF export made, and whether any PDF page in it had to be drawn as a picture. */
    class PdfExport(val file: File, val rasterisedPdfPages: Boolean)

    /**
     * [pages] are 0-based and ascending; see [pl.dakil.notes.model.PageSelection].
     *
     * A note with PDF pages is exported in two passes. The note's own content — ink, text,
     * pictures — is drawn through [PdfDocument] as it always has been, minus the paper on the pages
     * a PDF is the paper of. [PdfComposer] then puts each of those PDF pages under it, copied from the
     * original file as vectors: text that was selectable stays selectable, a scan keeps every pixel.
     * A PDF page that cannot be copied — an encrypted file, a filter this cannot undo — is drawn as
     * a picture at [ExportOptions.pdfImages]' resolution instead, and the result says so.
     */
    suspend fun exportToPdf(
        context: Context,
        sheet: Sheet,
        options: ExportOptions,
        pages: List<Int>,
        order: RenderOrder,
        forceRaster: Boolean = false,
    ): PdfExport {
        val format = sheet.format
        val preset = options.preset
        val pdfBlocks = sheet.blocks.filterIsInstance<PdfPageBlock>().groupBy { sheet.pageOf(it) }
        val sources = HashMap<String, PdfFile?>()
        try {
            fun sourceOf(block: PdfPageBlock): PdfFile? = sources.getOrPut(block.src) {
                if (forceRaster) return@getOrPut null
                PdfPages.fileFor(block, context.cacheDir)?.let { runCatching { PdfFile.open(it) }.getOrNull() }
            }
            fun embeddable(block: PdfPageBlock): Boolean =
                sourceOf(block)?.let { PdfComposer.canEmbed(it, block.page) } == true

            val vector = pages.associateWith { page -> pdfBlocks[page].orEmpty().filter(::embeddable) }
            val composing = vector.values.any { it.isNotEmpty() }
            var rasterised = false

            val fontResolver = createFontFamilyResolver(context)
            val strokeRenderer = StrokeRenderer()
            val document = PdfDocument()
            // A PDF page's coordinate space is points, so no density scaling is needed at all here —
            // unlike PNG, which has to pick a resolution first. Whole points, as PdfDocument takes;
            // rounded up when composing, so the fraction the composer adds back is never clipped off.
            val widthPt = (if (composing) ceil(format.width) else format.width).toInt().coerceAtLeast(1)
            val heightPt = (if (composing) ceil(format.height) else format.height).toInt().coerceAtLeast(1)
            for ((index, page) in pages.withIndex()) {
                val embedded = vector.getValue(page)
                val drawn = pdfBlocks[page].orEmpty().filter { it !in embedded }
                if (drawn.isNotEmpty()) rasterised = true
                val backdrops = drawn.map { block ->
                    block to renderBackdrop(context, block, options.pdfImages.decodeWidthPx(block.rect.width))
                }
                // The PDF's own numbering runs 1..n over what was exported; the content is the page asked for.
                val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                val pdfPage = document.startPage(info)
                drawIntoCanvas(pdfPage.canvas, widthPt, heightPt, Density(1f)) {
                    drawPage(
                        sheet, page, format, ptToPx = 1f, preset,
                        // The composer paints the paper under the PDF page; painted here, it would
                        // cover the page it is meant to be under.
                        transparent = embedded.isNotEmpty(), order, strokeRenderer,
                        fontResolver, decodeWidth = options.pdfImages::decodeWidthPx,
                        pdfBackdrops = backdrops, patternless = pdfBlocks[page] != null,
                    )
                }
                document.finishPage(pdfPage)
                backdrops.forEach { it.second?.recycle() }
            }

            if (!composing) {
                val file = File(exportDir(context), "export.pdf")
                FileOutputStream(file).use { document.writeTo(it) }
                document.close()
                return PdfExport(file, rasterised)
            }

            // The overlay goes beside the export folder, not in it: that folder is emptied by
            // [exportDir], and the overlay has to outlive the call that makes it.
            val work = context.cacheDir.resolve("pdf-compose").apply { mkdirs() }
            val overlayFile = File(work, "overlay.pdf")
            FileOutputStream(overlayFile).use { document.writeTo(it) }
            document.close()
            val paper = backgroundOf(format, preset)
            val composed = pages.mapIndexed { index, page ->
                val pageTop = page * format.height
                PdfComposer.Page(
                    width = format.width,
                    height = format.height,
                    overlayPage = index,
                    paper = if (vector.getValue(page).isEmpty()) null else paper,
                    backdrops = vector.getValue(page).map { b ->
                        PdfComposer.Backdrop(sourceOf(b)!!, b.page, b.rect.left, b.rect.top - pageTop, b.rect.width, b.rect.height)
                    },
                )
            }
            val file = File(exportDir(context), "export.pdf")
            val ok = runCatching {
                PdfFile.open(overlayFile).use { overlay ->
                    FileOutputStream(file).use { PdfComposer.compose(overlay, composed, it) }
                }
            }.isSuccess
            overlayFile.delete()
            // A source the reader understood and the composer then could not copy: drawn as
            // pictures rather than not exported at all.
            if (!ok) return exportToPdf(context, sheet, options, pages, order, forceRaster = true)
            return PdfExport(file, rasterised)
        } finally {
            sources.values.forEach { runCatching { it?.close() } }
        }
    }

    /**
     * A PDF page as a picture [widthPx] wide (zero: at 300 dpi of the width it prints at), for a page
     * that cannot be carried across as vectors, or for an export that is pictures anyway.
     */
    private suspend fun renderBackdrop(context: Context, block: PdfPageBlock, widthPx: Int): Bitmap? {
        val file = PdfPages.fileFor(block, context.cacheDir) ?: return null
        val width = (if (widthPx > 0) widthPx else (block.rect.width / 72f * 300f).roundToInt()).coerceIn(16, MAX_BACKDROP_PX)
        val height = (width * block.rect.height / block.rect.width.coerceAtLeast(1f)).roundToInt().coerceAtLeast(16)
        return PdfPages.renderBitmap(file, block.page, width, height)
    }

    /** A PDF page drawn as a picture is held to this, however fine a resolution is asked for. */
    private const val MAX_BACKDROP_PX = 6000

    /** One PNG or JPEG per page, at the size [options] asks for; see [RasterScale]. */
    suspend fun exportToImages(
        context: Context,
        sheet: Sheet,
        options: ExportOptions,
        pages: List<Int>,
        order: RenderOrder,
    ): List<File> {
        val format = sheet.format
        val raster = RasterScale.of(format.width, format.height, options)
        val ptToPx = raster.ptToPx
        val fontResolver = createFontFamilyResolver(context)
        val strokeRenderer = StrokeRenderer()
        val widthPx = raster.widthPx
        val heightPx = raster.heightPx
        // Once, before the loop: it empties the folder, and asked per page it deleted every page
        // but the last before anyone could save or share them.
        val dir = exportDir(context)
        val files = ArrayList<File>()
        val pdfBlocks = sheet.blocks.filterIsInstance<PdfPageBlock>().groupBy { sheet.pageOf(it) }
        for (page in pages) {
            // Rendered at the size it is drawn, like a picture is decoded at it.
            val backdrops = pdfBlocks[page].orEmpty().map { block ->
                block to renderBackdrop(context, block, (block.rect.width * ptToPx).roundToInt())
            }
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawIntoCanvas(canvas, widthPx, heightPx, Density(ptToPx)) {
                drawPage(
                    sheet, page, format, ptToPx, options.preset, options.transparentBackground, order,
                    strokeRenderer, fontResolver,
                    // Decoded at the size it is drawn: a bitmap's pixels are the only ones it has.
                    decodeWidth = { widthPt -> (widthPt * ptToPx).roundToInt() },
                    pdfBackdrops = backdrops, patternless = pdfBlocks[page] != null,
                )
            }
            backdrops.forEach { it.second?.recycle() }
            val file = options.rasterFile(dir, page)
            writeRaster(bitmap, file, options)
            bitmap.recycle()
            files += file
        }
        return files
    }

    /** Cleared on every call so a device's cache does not accumulate every export ever made. */
    private fun exportDir(context: Context): File {
        val dir = context.cacheDir.resolve("exports")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun DrawScope.drawPage(
        sheet: Sheet,
        pageIndex: Int,
        format: PageFormat,
        ptToPx: Float,
        preset: ExportColorPreset,
        /** Leave the paper colour out; the pattern is still drawn, over nothing. */
        transparent: Boolean,
        order: RenderOrder,
        strokeRenderer: StrokeRenderer,
        fontResolver: FontFamily.Resolver,
        decodeWidth: (printWidthPt: Float) -> Int,
        /** PDF pages drawn here as pictures, on white, under everything else on the page. */
        pdfBackdrops: List<Pair<PdfPageBlock, Bitmap?>> = emptyList(),
        /** A PDF page is this page's paper, so no pattern is drawn on it. */
        patternless: Boolean = false,
    ) {
        val backgroundColor = backgroundOf(format, preset)
        if (!transparent) drawRect(color = Color(backgroundColor), size = size)

        val pattern = format.background.pattern
        val transformedFormat = format.copy(
            background = format.background.copy(
                color = backgroundColor,
                pattern = pattern.copy(
                    color = preset.apply(pattern.color),
                    marginColor = preset.apply(pattern.marginColor),
                ),
            ),
        )
        if (!patternless) {
            with(SheetPainter) {
                drawPattern(
                    format = transformedFormat,
                    ptToPx = ptToPx,
                    pageTopPx = 0f,
                    pageHeightPx = format.height * ptToPx,
                    zoom = 1f,
                )
            }
        }

        val pageTop = pageIndex * format.height
        val pageBottom = pageTop + format.height

        for ((block, bitmap) in pdfBackdrops) {
            val r = block.rect
            val dst = android.graphics.RectF(r.left * ptToPx, (r.top - pageTop) * ptToPx, r.right * ptToPx, (r.bottom - pageTop) * ptToPx)
            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawRect(dst, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
            if (bitmap != null) canvas.drawBitmap(bitmap, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }

        // Level by level, in the order the screen paints them, so a page prints the way it looked.
        val paintOrder = sheet.blocksInPaintOrder()
        for ((level, members) in order.levels.withIndex()) {
            when {
                RenderLayer.IMAGES in members -> drawImages(sheet, pageIndex, pageTop, ptToPx, decodeWidth)

                RenderLayer.TEXT in members -> for (block in sheet.textBlocks()) {
                    if (sheet.pageOf(block) != pageIndex) continue
                    drawTextBlock(block, pageTop, ptToPx, fontResolver)
                }

                else -> {
                    val strokes = paintOrder.flatMap { block ->
                        if (block !is InkBlock || !block.visible) emptyList()
                        else block.strokes.filter { order.levelOf(it.tool) == level }.map { it.recolored(preset) }
                    }
                    with(strokeRenderer) {
                        drawStrokes(
                            strokes = strokes,
                            ptToPx = ptToPx,
                            stripOffsetPx = -pageTop * ptToPx,
                            docTop = pageTop,
                            docBottom = pageBottom,
                        )
                    }
                }
            }
        }
    }

    /**
     * The page's pictures, decoded at [decodeWidth] of the width they print at, a page at a time, so
     * a note of photographs does not hold all of them at full resolution at once.
     *
     * One picture placed many times is decoded once for the page. The PDF backend recognises a
     * picture it has already embedded only by the bitmap it is handed, so fifty copies of a
     * photograph decoded fifty times went into the file fifty times.
     *
     * The decoded size and the drawn size are separate on purpose. A PNG has no pixels but its own,
     * so it decodes at the drawn size; a PDF draws in points and keeps every pixel of what it is
     * handed, so the picture's resolution there is whatever the user chose to shrink it to.
     */
    private fun DrawScope.drawImages(
        sheet: Sheet,
        pageIndex: Int,
        pageTop: Float,
        ptToPx: Float,
        decodeWidth: (printWidthPt: Float) -> Int,
    ) {
        val decoded = HashMap<Triple<String, ModelRect, Int>, ImageBitmap?>()
        clipRect(bottom = sheet.format.height * ptToPx) {
            for (block in sheet.blocks) {
                if (block !is ImageBlock || sheet.pageOf(block) != pageIndex) continue
                val r = block.rect
                val at = block.frame()
                val width = (r.width * ptToPx).roundToInt().coerceAtLeast(1)
                val height = (r.height * ptToPx).roundToInt().coerceAtLeast(1)
                // Only the part the crop shows is decoded, and no wider than was asked for.
                val target = decodeWidth(r.width)
                val bitmap = decoded.getOrPut(Triple(block.src, block.crop, target)) {
                    decodePrintedPicture(block.bytes, block.crop, target)
                } ?: continue
                inBlockFrame(at, at.left * ptToPx, (at.top - pageTop) * ptToPx) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(width, height),
                        // The export presets recolour ink, not photographs; a picture keeps
                        // only the filter its author put on it.
                        colorFilter = block.tint?.let(::imageTintFilter),
                    )
                }
            }
        }
        decoded.values.forEach { it?.asAndroidBitmap()?.recycle() }
    }

    private fun DrawScope.drawTextBlock(
        block: TextBlock,
        pageTop: Float,
        ptToPx: Float,
        fontResolver: FontFamily.Resolver,
    ) {
        if (block.markdown.isBlank()) return
        val plan = MarkdownRenderer.plan(block.markdown)
        // A sheet is paper: the sizes and colours its boxes carry are as much a part of the page
        // as the ink beside them, and a printed page that dropped them is not a picture of it.
        val annotated = plan.toAnnotatedString(block.markdown, exportMarkdownStyles(attributes = true))
        val rect = block.rect
        val at = block.frame()
        val widthPx = (rect.width * ptToPx).toInt().coerceAtLeast(1)
        val paragraph = Paragraph(
            text = annotated.text,
            style = TextStyle(fontSize = 14.sp, color = TEXT_COLOR),
            constraints = Constraints(maxWidth = widthPx),
            density = Density(ptToPx),
            fontFamilyResolver = fontResolver,
            spanStyles = annotated.spanStyles,
        )
        // Through its own frame, as on the sheet: turned, stretched or both about its corner.
        inBlockFrame(at, at.left * ptToPx, (at.top - pageTop) * ptToPx) {
            paragraph.paint(drawContext.canvas)
        }
    }

    /**
     * The paper colour as exported. INTELLIGENT's white background is applied here rather than
     * inside ExportColorPreset.apply — the rule does not hold for every colour on the sheet, only
     * this one field.
     */
    private fun backgroundOf(format: PageFormat, preset: ExportColorPreset): Int =
        if (preset == ExportColorPreset.INTELLIGENT) 0xFFFFFFFF.toInt() else preset.apply(format.background.color)

    /** Fixed rather than theme-derived: there is no composition here to read a colour scheme from. */
    private val TEXT_COLOR = Color(0xFF1B1B1F)
}

private fun Stroke.recolored(preset: ExportColorPreset): Stroke =
    Stroke(
        tool, preset.apply(color), width, blend, xs, ys, widthFactors, tilts, times, shape, filled,
        core?.let { it.copy(color = preset.apply(it.color)) },
    )
