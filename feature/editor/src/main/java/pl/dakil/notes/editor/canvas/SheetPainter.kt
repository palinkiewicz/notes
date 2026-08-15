package pl.dakil.notes.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PatternType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Draws the paper the note is written on: one continuous strip, optionally with the page edges
 * showing.
 *
 * Everything is procedural — no bitmaps to ship or allocate, crisp at any zoom, and changing a
 * colour or spacing costs nothing. The strip is drawn in pixels, with [ptToPx] converting from the
 * document's point coordinates, and only the visible band is emitted so a fifty-page note costs the
 * same as a one-page one.
 */
object SheetPainter {

    /** Below this on-screen spacing the rules alias into a grey wash, so they are skipped. */
    private const val MIN_VISIBLE_SPACING_PX = 4f

    /** Rule thickness in pixels, held constant so zooming does not fatten the paper. */
    private const val LINE_PX = 1f

    /**
     * Gap between pages in paged view, in points.
     *
     * Sized so the page header has somewhere to sit. It is presentation only — [stripPxToDocumentY]
     * subtracts it back out — so widening it moves nothing in the document and changes nothing
     * about what prints.
     */
    const val PAGE_GAP_PT = 40f

    /**
     * @param zoom the scale the sheet will be placed at. The paper is drawn in unzoomed document
     *   pixels and scaled afterwards, so every hairline and every visibility threshold here has to
     *   be divided by it to end up the intended size on the glass. Without that, opening a note at
     *   fit-to-width — around a quarter scale on a phone — would render the rules a quarter of a
     *   pixel wide and the paper would come up blank.
     */
    fun DrawScope.drawSheet(
        format: PageFormat,
        pageCount: Int,
        ptToPx: Float,
        paged: Boolean,
        zoom: Float = 1f,
        visibleTopPx: Float = 0f,
        visibleBottomPx: Float = size.height,
    ) {
        val paper = Color(format.background.color)
        val pageHeightPx = format.height * ptToPx
        val gapPx = if (paged) PAGE_GAP_PT * ptToPx else 0f
        val hairline = LINE_PX / zoom

        for (page in 0 until pageCount) {
            val top = page * (pageHeightPx + gapPx)
            val bottom = top + pageHeightPx
            if (bottom < visibleTopPx || top > visibleBottomPx) continue

            drawRect(color = paper, topLeft = Offset(0f, top), size = Size(size.width, pageHeightPx))

            // The pattern is clipped to its own page so rules never run through the gap between
            // sheets — which is what makes paged view look like paper rather than a striped wall.
            clipRect(top = top, bottom = bottom) {
                drawPattern(format, ptToPx, top, pageHeightPx, zoom, hairline)
            }

            if (paged) {
                drawRect(
                    color = Color(0x22000000),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, pageHeightPx),
                    style = Stroke(width = hairline),
                )
            }
        }
    }

    /** Distance from one page's top to the next, in pixels — the page plus its gap. */
    fun pageStridePx(format: PageFormat, ptToPx: Float, paged: Boolean): Float =
        format.height * ptToPx + if (paged) PAGE_GAP_PT * ptToPx else 0f

    /** Where each page starts, in pixels down the strip. Used to place page-number labels. */
    fun pageTops(format: PageFormat, pageCount: Int, ptToPx: Float, paged: Boolean): FloatArray {
        val pageHeightPx = format.height * ptToPx
        val gapPx = if (paged) PAGE_GAP_PT * ptToPx else 0f
        return FloatArray(pageCount) { it * (pageHeightPx + gapPx) }
    }

    /** Total strip height in pixels, including inter-page gaps when paged. */
    fun stripHeightPx(format: PageFormat, pageCount: Int, ptToPx: Float, paged: Boolean): Float {
        val pageHeightPx = format.height * ptToPx
        val gapPx = if (paged) PAGE_GAP_PT * ptToPx else 0f
        return pageCount * pageHeightPx + (pageCount - 1).coerceAtLeast(0) * gapPx
    }

    /**
     * Converts a y in continuous document points to a y in strip pixels.
     *
     * In continuous view these differ only by scale; in paged view the accumulated inter-page gaps
     * have to be added, which is the one place the two views are not the same geometry.
     */
    fun documentYToStripPx(y: Float, format: PageFormat, ptToPx: Float, paged: Boolean): Float {
        if (!paged) return y * ptToPx
        val page = if (format.height > 0f) floor(y / format.height).toInt().coerceAtLeast(0) else 0
        return y * ptToPx + page * PAGE_GAP_PT * ptToPx
    }

    fun stripPxToDocumentY(py: Float, format: PageFormat, ptToPx: Float, paged: Boolean): Float {
        if (!paged) return py / ptToPx
        val pageHeightPx = format.height * ptToPx
        val gapPx = PAGE_GAP_PT * ptToPx
        val stride = pageHeightPx + gapPx
        if (stride <= 0f) return py / ptToPx
        val page = floor(py / stride).toInt().coerceAtLeast(0)
        return (py - page * gapPx) / ptToPx
    }

    // ---- Patterns --------------------------------------------------------------------------------

    private fun DrawScope.drawPattern(
        format: PageFormat,
        ptToPx: Float,
        pageTopPx: Float,
        pageHeightPx: Float,
        zoom: Float,
        hairline: Float,
    ) {
        val pattern = format.background.pattern
        if (pattern.type == PatternType.NONE || pattern.spacing <= 0f) return

        val spacingPx = pattern.spacing * ptToPx
        // Measured on the glass, not on the paper: what matters is whether the rules would still
        // read as rules once the sheet is scaled, and below a few pixels apart they read as a wash.
        if (spacingPx * zoom < MIN_VISIBLE_SPACING_PX) return

        val alpha = pattern.opacity.coerceIn(0f, 1f)
        val color = Color(pattern.color).copy(alpha = alpha)
        val marginColor = Color(pattern.marginColor).copy(alpha = alpha)

        val width = size.width
        when (pattern.type) {
            PatternType.GRID -> {
                verticals(width, spacingPx) { x -> line(x, pageTopPx, x, pageTopPx + pageHeightPx, color, hairline) }
                horizontals(pageHeightPx, spacingPx) { y -> line(0f, pageTopPx + y, width, pageTopPx + y, color, hairline) }
            }

            PatternType.RULED -> {
                horizontals(pageHeightPx, spacingPx) { y -> line(0f, pageTopPx + y, width, pageTopPx + y, color, hairline) }
                if (pattern.margin > 0f) {
                    val x = pattern.margin * ptToPx
                    line(x, pageTopPx, x, pageTopPx + pageHeightPx, marginColor, hairline)
                }
            }

            PatternType.DOTTED -> {
                val radius = hairline * 1.1f
                verticals(width, spacingPx) { x ->
                    horizontals(pageHeightPx, spacingPx) { y ->
                        drawCircle(color, radius, Offset(x, pageTopPx + y))
                    }
                }
            }

            PatternType.ISOMETRIC -> {
                verticals(width, spacingPx) { x -> line(x, pageTopPx, x, pageTopPx + pageHeightPx, color, hairline) }
                val slope = 1f / sqrt(3f)
                val span = width * slope
                val diagonalSpacing = spacingPx / sqrt(3f) * 2f
                var intercept = -span
                var guard = 0
                while (intercept < pageHeightPx + span && guard++ < MAX_LINES) {
                    line(0f, pageTopPx + intercept, width, pageTopPx + intercept + span, color, hairline)
                    line(0f, pageTopPx + intercept, width, pageTopPx + intercept - span, color, hairline)
                    intercept += diagonalSpacing
                }
            }

            PatternType.STAVES -> {
                val gap = spacingPx
                val staffHeight = gap * 4f
                val systemGap = if (pattern.groupSpacing > 0f) pattern.groupSpacing * ptToPx
                else staffHeight * 1.5f
                val period = staffHeight + systemGap
                if (period <= 0f) return
                var top = 0f
                var guard = 0
                while (top < pageHeightPx && guard++ < MAX_LINES) {
                    for (i in 0 until 5) {
                        val y = top + i * gap
                        if (y <= pageHeightPx) line(0f, pageTopPx + y, width, pageTopPx + y, color, hairline)
                    }
                    top += period
                }
            }

            PatternType.NONE -> Unit
        }
    }

    private fun DrawScope.line(
        x0: Float, y0: Float, x1: Float, y1: Float, color: Color, width: Float,
    ) {
        drawLine(color, Offset(x0, y0), Offset(x1, y1), width)
    }

    private inline fun verticals(width: Float, spacing: Float, block: (Float) -> Unit) {
        if (spacing <= 0f) return
        val count = ceil(width / spacing).toInt()
        if (count > MAX_LINES) return
        for (i in 1..count) block(i * spacing)
    }

    private inline fun horizontals(height: Float, spacing: Float, block: (Float) -> Unit) {
        if (spacing <= 0f) return
        val count = ceil(height / spacing).toInt()
        if (count > MAX_LINES) return
        for (i in 1..count) block(i * spacing)
    }

    private const val MAX_LINES = 4_000
}
