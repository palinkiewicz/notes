package pl.dakil.notes.editor.pdf

import pl.dakil.notes.model.Rect
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How sharp a PDF page has to be drawn, and where: plain arithmetic, so it is tested without a
 * renderer.
 *
 * A page is drawn at two levels. The **base** is the whole page, at a resolution that follows the
 * zoom in steps but is capped — at fit-to-width it is already as sharp as the glass, and a page
 * bitmap large enough for a deep zoom would be hundreds of megabytes. Past the cap, a **tile** of
 * just the part on screen is drawn at exactly the glass's resolution once the gesture settles, and
 * laid over the base: an eight-times zoom into a footnote is as crisp as the PDF itself.
 */
object PdfRenderPlan {

    /**
     * The longest side a base is drawn at. A phone at fit-to-width needs about this much, and a few
     * pages of it are what the window holds at once.
     */
    const val MAX_BASE_PX = 1600

    /** A tile is worth drawing once the glass wants this much more than the base has. */
    const val DETAIL_THRESHOLD = 1.2f

    /** The largest tile side: a screen and a margin, never a page. */
    const val MAX_TILE_PX = 4096

    /**
     * [pxPerPt] rounded to the nearest half power of two, so a pinch re-renders at a few distinct
     * scales rather than on every frame.
     */
    fun bucket(pxPerPt: Float): Float {
        if (pxPerPt <= 0f || !pxPerPt.isFinite()) return 1f
        val steps = (ln(pxPerPt.toDouble()) / ln(2.0) * 2).roundToInt()
        return 2.0.pow(steps / 2.0).toFloat()
    }

    /** The base bitmap for a page shown [widthPt] × [heightPt] at [pxPerPt], as `[width, height]`. */
    fun baseSize(widthPt: Float, heightPt: Float, pxPerPt: Float): IntArray {
        val scale = bucket(pxPerPt)
        var w = widthPt * scale
        var h = heightPt * scale
        val longest = max(w, h)
        if (longest > MAX_BASE_PX) {
            w *= MAX_BASE_PX / longest
            h *= MAX_BASE_PX / longest
        }
        return intArrayOf(w.roundToInt().coerceAtLeast(MIN_PX), h.roundToInt().coerceAtLeast(MIN_PX))
    }

    /** Whether a page [widthPt] wide, whose base is [baseWidthPx], needs a tile at [pxPerPt]. */
    fun needsDetail(widthPt: Float, baseWidthPx: Int, pxPerPt: Float): Boolean =
        widthPt * pxPerPt > baseWidthPx * DETAIL_THRESHOLD

    /**
     * A part of a page to draw at the glass's own resolution.
     *
     * [region] is left, top, right, bottom as fractions of the page, which is what the renderer is
     * asked for; [rect] is the same area on the sheet, in points, which is where it is drawn.
     */
    class Tile(val region: FloatArray, val rect: Rect, val widthPx: Int, val heightPx: Int)

    /**
     * The tile covering what of [page] — the PDF page's rect on the sheet — lies in [visible], both
     * in sheet points, at [pxPerPt]; null when none of it is on screen.
     */
    fun tile(page: Rect, visible: Rect, pxPerPt: Float): Tile? {
        val left = max(page.left, visible.left)
        val top = max(page.top, visible.top)
        val right = minOf(page.right, visible.right)
        val bottom = minOf(page.bottom, visible.bottom)
        if (right <= left || bottom <= top || page.width <= 0f || page.height <= 0f) return null
        var w = (right - left) * pxPerPt
        var h = (bottom - top) * pxPerPt
        // Only reached when the window is enormous, which a tile is never asked for at; held anyway,
        // since the alternative is an allocation that kills the app.
        val longest = max(w, h)
        if (longest > MAX_TILE_PX) {
            w *= MAX_TILE_PX / longest
            h *= MAX_TILE_PX / longest
        }
        val region = floatArrayOf(
            (left - page.left) / page.width,
            (top - page.top) / page.height,
            (right - page.left) / page.width,
            (bottom - page.top) / page.height,
        )
        return Tile(region, Rect(left, top, right, bottom), w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    private const val MIN_PX = 16
}
