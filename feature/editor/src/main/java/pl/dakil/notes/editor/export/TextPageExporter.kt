package pl.dakil.notes.editor.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.toAnnotatedString
import pl.dakil.notes.ui.export.drawIntoCanvas
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a `.md` note to PDF or PNG.
 *
 * Simpler than [InkPageExporter] on purpose, and a first cut rather than a finished renderer: the
 * whole document is laid out as one [Paragraph] and cut into pages by walking its own line heights
 * against a fixed page budget, painted with [drawIntoCanvas] the same way the ink path is. Styled
 * text — bold, italic, heading size, monospace code, from the [androidx.compose.ui.text.AnnotatedString]'s
 * span styles — comes through; fence backgrounds, table gridlines and quote bars, which the live
 * editor draws as separate decorations rather than as spans, do not. That is a real limitation of
 * this first pass, not an oversight — see the module's export summary.
 */
object TextPageExporter {

    // A4-ish, in points — there is no document page size for a `.md` note to inherit.
    private const val PAGE_WIDTH_PT = 595f
    private const val PAGE_HEIGHT_PT = 842f
    private const val MARGIN_PT = 56f
    private const val PNG_DPI = 150f
    private val TEXT_COLOR = Color(0xFF1B1B1F)

    fun exportToPdf(context: Context, markdown: String): File {
        val fontResolver = createFontFamilyResolver(context)
        val ptToPx = 1f
        val paragraph = buildParagraph(markdown, fontResolver, ptToPx)
        val breaks = pageBreaks(paragraph, contentHeightPx(ptToPx))
        val widthPt = PAGE_WIDTH_PT.toInt()
        val heightPt = PAGE_HEIGHT_PT.toInt()

        val document = PdfDocument()
        for (page in breaks.indices) {
            val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, page + 1).create()
            val pdfPage = document.startPage(info)
            drawIntoCanvas(pdfPage.canvas, widthPt, heightPt, Density(ptToPx)) {
                drawPage(paragraph, breaks, page, ptToPx)
            }
            document.finishPage(pdfPage)
        }
        val file = File(exportDir(context), "export.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    fun exportToPngs(context: Context, markdown: String): List<File> {
        val fontResolver = createFontFamilyResolver(context)
        val ptToPx = PNG_DPI / 72f
        val paragraph = buildParagraph(markdown, fontResolver, ptToPx)
        val breaks = pageBreaks(paragraph, contentHeightPx(ptToPx))
        val widthPx = (PAGE_WIDTH_PT * ptToPx).toInt().coerceAtLeast(1)
        val heightPx = (PAGE_HEIGHT_PT * ptToPx).toInt().coerceAtLeast(1)

        val files = ArrayList<File>()
        for (page in breaks.indices) {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawIntoCanvas(canvas, widthPx, heightPx, Density(ptToPx)) {
                drawPage(paragraph, breaks, page, ptToPx)
            }
            val file = File(exportDir(context), "export_${page + 1}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            files += file
        }
        return files
    }

    private fun exportDir(context: Context): File {
        val dir = context.cacheDir.resolve("exports")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun contentHeightPx(ptToPx: Float): Float = (PAGE_HEIGHT_PT - 2 * MARGIN_PT) * ptToPx

    private fun buildParagraph(markdown: String, fontResolver: FontFamily.Resolver, ptToPx: Float): Paragraph {
        val plan = MarkdownRenderer.plan(markdown)
        val annotated = plan.toAnnotatedString(markdown, exportMarkdownStyles())
        val widthPx = ((PAGE_WIDTH_PT - 2 * MARGIN_PT) * ptToPx).toInt().coerceAtLeast(1)
        return Paragraph(
            text = annotated.text,
            style = TextStyle(fontSize = 14.sp, color = TEXT_COLOR),
            constraints = Constraints(maxWidth = widthPx),
            density = Density(ptToPx),
            fontFamilyResolver = fontResolver,
            spanStyles = annotated.spanStyles,
        )
    }

    /**
     * Where each page's content starts, in the paragraph's own pixel coordinates.
     *
     * Cut at a line boundary rather than a blind `height / budget` division, so a page break never
     * falls in the middle of a line of text. Always has at least one entry, so an empty or
     * single-page document still gets the one page it needs.
     */
    private fun pageBreaks(paragraph: Paragraph, budgetPx: Float): List<Float> {
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

    private fun DrawScope.drawPage(paragraph: Paragraph, breaks: List<Float>, pageIndex: Int, ptToPx: Float) {
        val marginPx = MARGIN_PT * ptToPx
        val budgetPx = contentHeightPx(ptToPx)
        val contentTop = breaks[pageIndex]
        clipRect(left = marginPx, top = marginPx, right = size.width - marginPx, bottom = marginPx + budgetPx) {
            translate(left = marginPx, top = marginPx - contentTop) {
                paragraph.paint(drawContext.canvas)
            }
        }
    }
}
