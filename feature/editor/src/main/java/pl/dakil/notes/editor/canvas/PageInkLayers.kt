package pl.dakil.notes.editor.canvas

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import pl.dakil.notes.model.Stroke

/**
 * One recorded display list of committed ink per page, replayed until that page's ink changes.
 *
 * The path cache in `StrokeRenderer` made a redraw cheap to *prepare*, but a redraw still issued
 * one draw call per stroke, and every page in view was redrawn whenever anything on the sheet
 * moved — a pan, a sample of the pen. On a page of handwriting that is thousands of calls a frame,
 * which is what made a dense note stutter at the zoom it is read at.
 *
 * Recorded here, a page costs one draw call to put on screen. It is recorded again only when the
 * strokes it was recorded from are no longer the strokes on that page — compared by identity, which
 * is exact because a committed stroke is immutable — so writing on one page leaves every other
 * page's recording alone, and zoom never touches any of them: the layer is in unzoomed pixels and
 * the zoom is applied outside it.
 *
 * Nothing goes offscreen. A layer with no alpha or effect is replayed straight into the canvas it is
 * drawn into, so a multiplying highlighter still blends with the paper and text underneath it.
 */
internal class PageInkLayers(private val context: GraphicsContext) {

    private class Entry(val layer: GraphicsLayer) {
        var strokes: List<Stroke>? = null
        var size = IntSize.Zero
        var ptToPx = Float.NaN
    }

    private val entries = HashMap<Int, Entry>()

    /**
     * Draws [page]'s recording with its top at [topPx], re-recording it through [record] first if
     * it was recorded from anything other than [strokes] at this [size] and [ptToPx].
     */
    fun DrawScope.drawPage(
        page: Int,
        strokes: List<Stroke>,
        topPx: Float,
        size: IntSize,
        ptToPx: Float,
        record: DrawScope.() -> Unit,
    ) {
        val entry = entries.getOrPut(page) { Entry(context.createGraphicsLayer()) }
        val recorded = entry.strokes
        if (recorded == null || entry.size != size || entry.ptToPx != ptToPx || !sameStrokes(recorded, strokes)) {
            entry.layer.record(size) { record() }
            entry.strokes = strokes
            entry.size = size
            entry.ptToPx = ptToPx
        }
        translate(0f, topPx) { drawLayer(entry.layer) }
    }

    /**
     * Lets go of the recordings of pages well outside [keep], so a long note scrolled end to end
     * holds a window's worth of display lists rather than the whole document's. The margin keeps a
     * page that has just slid out from being recorded again the moment it slides back.
     */
    fun retain(keep: IntRange, margin: Int = RETAIN_MARGIN) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (page, entry) = iterator.next()
            if (page < keep.first - margin || page > keep.last + margin) {
                context.releaseGraphicsLayer(entry.layer)
                iterator.remove()
            }
        }
    }

    fun releaseAll() {
        for (entry in entries.values) context.releaseGraphicsLayer(entry.layer)
        entries.clear()
    }

    private companion object {
        const val RETAIN_MARGIN = 2
    }
}

/**
 * Whether two stroke lists hold the same instances in the same order.
 *
 * Identity rather than equality: `Stroke` declares no `equals`, and it does not need one — an edit
 * replaces the strokes it touches with new instances, so an unchanged page is made of the very
 * objects it was recorded from. Order counts because it is paint order.
 */
internal fun sameStrokes(a: List<Stroke>, b: List<Stroke>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false
    for (i in a.indices) if (a[i] !== b[i]) return false
    return true
}
