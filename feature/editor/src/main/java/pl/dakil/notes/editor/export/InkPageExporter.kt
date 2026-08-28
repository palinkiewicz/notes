package pl.dakil.notes.editor.export

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

    /** PNG export has no fixed coordinate space of its own, unlike a PDF page's points; pick one DPI. */
    private const val PNG_DPI = 150f

    fun exportToPdf(context: Context, sheet: Sheet, preset: ExportColorPreset): File {
        val format = sheet.format
        val fontResolver = createFontFamilyResolver(context)
        val strokeRenderer = StrokeRenderer()
        val document = PdfDocument()
        // A PDF page's coordinate space is points, so no density scaling is needed at all here —
        // unlike PNG, which has to pick a resolution first.
        val widthPt = format.width.toInt().coerceAtLeast(1)
        val heightPt = format.height.toInt().coerceAtLeast(1)
        for (page in 0 until sheet.pageCount()) {
            val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, page + 1).create()
            val pdfPage = document.startPage(info)
            drawIntoCanvas(pdfPage.canvas, widthPt, heightPt, Density(1f)) {
                drawPage(sheet, page, format, ptToPx = 1f, preset, strokeRenderer, fontResolver)
            }
            document.finishPage(pdfPage)
        }
        val file = File(exportDir(context), "export.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    fun exportToPngs(context: Context, sheet: Sheet, preset: ExportColorPreset): List<File> {
        val format = sheet.format
        val ptToPx = PNG_DPI / 72f
        val fontResolver = createFontFamilyResolver(context)
        val strokeRenderer = StrokeRenderer()
        val widthPx = (format.width * ptToPx).toInt().coerceAtLeast(1)
        val heightPx = (format.height * ptToPx).toInt().coerceAtLeast(1)
        val files = ArrayList<File>()
        for (page in 0 until sheet.pageCount()) {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawIntoCanvas(canvas, widthPx, heightPx, Density(ptToPx)) {
                drawPage(sheet, page, format, ptToPx, preset, strokeRenderer, fontResolver)
            }
            val file = File(exportDir(context), "export_${page + 1}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        strokeRenderer: StrokeRenderer,
        fontResolver: FontFamily.Resolver,
    ) {
        // INTELLIGENT's white background is applied here, by the caller, rather than inside
        // ExportColorPreset.apply — the rule does not hold for every colour on the sheet, only this
        // one field.
        val backgroundColor = if (preset == ExportColorPreset.INTELLIGENT) {
            0xFFFFFFFF.toInt()
        } else {
            preset.apply(format.background.color)
        }
        drawRect(color = Color(backgroundColor), size = size)

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
        with(SheetPainter) {
            drawPattern(
                format = transformedFormat,
                ptToPx = ptToPx,
                pageTopPx = 0f,
                pageHeightPx = format.height * ptToPx,
                zoom = 1f,
            )
        }

        val pageTop = pageIndex * format.height
        val pageBottom = pageTop + format.height
        for (layer in sheet.inkLayers()) {
            if (!layer.visible) continue
            val recolored = layer.strokes.map { it.recolored(preset) }
            with(strokeRenderer) {
                drawStrokes(
                    strokes = recolored,
                    ptToPx = ptToPx,
                    stripOffsetPx = -pageTop * ptToPx,
                    docTop = pageTop,
                    docBottom = pageBottom,
                )
            }
        }

        for (block in sheet.textBlocks()) {
            if (sheet.pageOf(block) != pageIndex) continue
            drawTextBlock(block, pageTop, ptToPx, fontResolver)
        }
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
        val rect = block.worldBounds()
        val widthPx = (rect.width * ptToPx).toInt().coerceAtLeast(1)
        val paragraph = Paragraph(
            text = annotated.text,
            style = TextStyle(fontSize = 14.sp, color = TEXT_COLOR),
            constraints = Constraints(maxWidth = widthPx),
            density = Density(ptToPx),
            fontFamilyResolver = fontResolver,
            spanStyles = annotated.spanStyles,
        )
        val left = rect.left * ptToPx
        val top = (rect.top - pageTop) * ptToPx
        val canvas = drawContext.canvas
        canvas.save()
        canvas.translate(left, top)
        paragraph.paint(canvas)
        canvas.restore()
    }

    /** Fixed rather than theme-derived: there is no composition here to read a colour scheme from. */
    private val TEXT_COLOR = Color(0xFF1B1B1F)
}

private fun Stroke.recolored(preset: ExportColorPreset): Stroke =
    Stroke(tool, preset.apply(color), width, blend, xs, ys, widthFactors, tilts, times, shape)
