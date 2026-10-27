package pl.dakil.notes.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.model.Block
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.RenderOrder
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.ui.sheet.StrokeRenderer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Everything about the hand that changes per input sample: the wet stroke, the shape being held,
 * and the counter whose read inside a draw lambda is what repaints them.
 *
 * Written by [InkOverlay], which owns the input, and painted by the [InkLevelLayer] of whichever
 * level the tool in hand draws into — so a highlighter stroke is already under the text while it is
 * being drawn, rather than jumping beneath it on pen-up. Plain objects and one `Int` of snapshot
 * state, for the reason the overlay is built that way: a sample must invalidate the draw phase and
 * nothing else.
 */
class LiveInk {
    val builder = StrokeBuilder()
    val shape = ShapeController()
    val version = mutableIntStateOf(0)
}

/**
 * One level of the drawing order's committed ink, grouped once per edit.
 *
 * [selected] is this level's share of the selection, in paint order. It is left out of [buckets]
 * and painted live instead, under the selection's transform, so a stroke being dragged is never
 * drawn twice — once where it is going and once where it was.
 */
internal class InkLevel(val buckets: PageBuckets, val selected: List<Stroke>)

/**
 * The note's strokes split by the level of [order] each one is painted in, keyed by level.
 *
 * Every level of ink in [order] has an entry, empty or not, so a level can be composed before
 * anything has been drawn in it. Inside a level the strokes keep [paintOrder] — block, then the
 * order they were drawn in — which is what makes tools sharing a level mix the way ink always has.
 */
internal fun inkLevels(
    paintOrder: List<Block>,
    order: RenderOrder,
    pageCount: Int,
    pageHeight: Float,
    paged: Boolean,
    selected: Set<Stroke>,
): Map<Int, InkLevel> {
    val out = HashMap<Int, InkLevel>()
    for ((level, members) in order.levels.withIndex()) {
        if (!members.all { it.isInk }) continue
        val include = { stroke: Stroke -> order.levelOf(stroke.tool) == level }
        val buckets =
            if (paged) clippedBuckets(strokesByPage(paintOrder, pageCount, pageHeight, selected, include))
            else strokesByTopPage(paintOrder, pageCount, pageHeight, selected, include)
        val chosen = if (selected.isEmpty()) emptyList() else paintOrder.filterIsInstance<InkBlock>()
            .filter { it.visible }
            .flatMap { block -> block.strokes.filter { it in selected && include(it) } }
        out[level] = InkLevel(buckets, chosen)
    }
    return out
}

/**
 * One level of ink: its committed strokes, and whatever of it is moving with the hand.
 *
 * Draw-only — no pointer input — so it can sit anywhere in the stack, above the text boxes or below
 * them, without taking a single tap from them. All input is [InkOverlay]'s, which stays on top.
 */
@Composable
internal fun InkLevelLayer(
    ink: InkLevel,
    live: LiveInk,
    /** Whether the tool in hand draws into this level, so its wet stroke and shapes are painted here. */
    drawsHere: Boolean,
    controller: SelectionController,
    format: PageFormat,
    pageCount: Int,
    /** Pixels per document point, including zoom. */
    ptToPx: Float,
    paged: Boolean,
    /** The pages to draw; see [InkOverlay]'s parameter of the same name. */
    pages: () -> IntRange,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { StrokeRenderer() }
    val graphicsContext = LocalGraphicsContext.current
    val pageLayers = remember(graphicsContext) { PageInkLayers(graphicsContext) }
    DisposableEffect(pageLayers) { onDispose { pageLayers.releaseAll() } }
    val buckets = ink.buckets
    val selected = ink.selected

    Box(modifier) {
        // The committed ink, a recorded layer per page. Its own render node, and reading nothing
        // that changes per sample or per frame of a pan: the pen moving repaints the live layer
        // above it, a pan moves the sheet, and neither replays a stroke into a display list. It is
        // repainted when a page comes into the window or the document changes, and then each page
        // whose ink is unchanged is a single call. See PageInkLayers.
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val window = pages()
            val pageHeightPx = format.height * ptToPx
            val stride = SheetPainter.pageStridePx(format, ptToPx, paged)
            val layerSize = IntSize(ceil(size.width).toInt(), ceil(pageHeightPx).toInt())
            var firstDrawn = window.first
            for (page in 0..min(window.last, buckets.pages.size - 1)) {
                // A page above the window is still drawn while ink recorded on it reaches down into
                // the window — which only continuous view's buckets ever do.
                if (buckets.lastPage[page] < window.first) continue
                val strokes = buckets.pages[page]
                if (strokes.isEmpty()) continue
                if (page < firstDrawn) firstDrawn = page
                val top = page * stride
                with(pageLayers) {
                    // Recorded in the page's own pixels, so it is the same recording wherever the
                    // page sits in the strip — and moving a page leaves it to be recorded only
                    // because its strokes have become new ones.
                    val draw: DrawScope.() -> Unit = {
                        drawPage(page, strokes, top, layerSize, ptToPx) {
                            with(renderer) { drawStrokes(strokes, ptToPx, -page * pageHeightPx) }
                        }
                    }
                    // A stroke drawn across a page break is a single continuous stroke in the
                    // document — that is the right model, since the break is presentation — but in
                    // paged view it must not be *painted* across the join, or the ink appears to
                    // run down the desk between two sheets of paper.
                    if (paged) clipRect(top = top, bottom = top + pageHeightPx) { draw() } else draw()
                }
            }
            pageLayers.retain(firstDrawn..window.last)
        }

        // Everything of this level that moves with the hand: the wet stroke, a shape being held, the
        // selection under its live transform. Redrawn per input sample — but only a level with
        // something live reads the counters at all, so the levels the pen is not drawing into are
        // not repainted by it.
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            if (drawsHere) {
                @Suppress("UNUSED_EXPRESSION")
                live.version.intValue
            }
            if (selected.isNotEmpty()) {
                // Read for its side effect, exactly like the counter above it: a selection being
                // dragged bumps this per input sample, and the read is what makes the canvas repaint
                // without anything recomposing.
                @Suppress("UNUSED_EXPRESSION")
                controller.version
            }
            drawLive(live, drawsHere, selected, controller, renderer, format, pageCount, ptToPx, paged, pages())
        }
    }
}

private fun DrawScope.drawLive(
    live: LiveInk,
    drawsHere: Boolean,
    selected: List<Stroke>,
    controller: SelectionController,
    renderer: StrokeRenderer,
    format: PageFormat,
    pageCount: Int,
    ptToPx: Float,
    paged: Boolean,
    window: IntRange,
) {
    val builder = live.builder
    val shape = live.shape
    val toStripPx = { y: Float -> SheetPainter.documentYToStripPx(y, format, ptToPx, paged) }
    // While a shape is live it *is* the stroke under the pen; the builder still holds the
    // free-hand path it replaced, which must not be drawn underneath it.
    val held = if (drawsHere) shape.spec else null
    val wet = drawsHere && held == null && !builder.isEmpty

    // The one stroke whose apex is being dragged, if any. The preview takes its weight and
    // colour from the ink it is about to replace, so what is under the grip while it is
    // being pulled looks like what will be committed when it is let go.
    val reshaping = if (controller.liveShape != null) selected.firstOrNull() else null
    val moving = reshaping == null && selected.isNotEmpty()

    val windowTop = window.first * format.height
    val windowBottom = (window.last + 1f) * format.height

    with(renderer) {
        if (!paged) {
            // Continuous view has no gaps, so the pages are one uninterrupted surface and a
            // single pass is both correct and cheapest. Document y and strip y are the same
            // measure there, so the pass needs no offset.
            if (moving) {
                drawStrokes(selected, ptToPx, 0f, 1f, windowTop, windowBottom, controller.live)
            }
            if (reshaping != null) drawShapePreview(
                controller.outline, ptToPx, toStripPx,
                reshaping.width, reshaping.color, reshaping.blend, reshaping.filled, reshaping.core,
            )
            if (wet) drawWetStroke(builder, ptToPx, toStripPx)
            if (held != null) drawShapePreview(
                shape.outline, ptToPx, toStripPx,
                shape.previewWidth, shape.previewColor, shape.previewBlend, shape.previewFilled,
                shape.previewCore,
            )
        } else if (moving || reshaping != null || wet || held != null) {
            // One clipped pass per page, for the reason the committed ink has one. Skipped
            // outright when there is nothing live: a clipRect still costs a save and a
            // restore, and there is no point paying it per page per frame to draw nothing.
            val pageHeightPx = format.height * ptToPx
            val gapPx = SheetPainter.PAGE_GAP_PT * ptToPx
            for (page in window.first..min(window.last, pageCount - 1)) {
                val top = page * (pageHeightPx + gapPx)
                val docTop = page * format.height
                val docBottom = docTop + format.height
                clipRect(top = top, bottom = top + pageHeightPx) {
                    // The selection is drawn over the ink it was lifted out of, under the
                    // live transform, and culled by where it is *going* rather than where
                    // the document still says it is — see `drawStrokes`.
                    if (moving) {
                        drawStrokes(selected, ptToPx, page * gapPx, 1f, docTop, docBottom, controller.live)
                    }
                    if (reshaping != null) drawShapePreview(
                        controller.outline, ptToPx, toStripPx,
                        reshaping.width, reshaping.color, reshaping.blend, reshaping.filled,
                        reshaping.core,
                    )
                    // Culled by page for a sharper reason: the wet stroke is tessellated on
                    // every frame, so drawing it once per page would multiply that by the
                    // number of pages in the window.
                    if (wet && builder.maxY >= docTop && builder.minY < docBottom) {
                        drawWetStroke(builder, ptToPx, toStripPx)
                    }
                    if (held != null) drawShapePreview(
                        shape.outline, ptToPx, toStripPx,
                        shape.previewWidth, shape.previewColor, shape.previewBlend,
                        shape.previewFilled, shape.previewCore,
                    )
                }
            }
        }
    }
}

/**
 * Groups committed strokes by the page they are painted on, in paint order.
 *
 * This is the same question the per-stroke cull in `drawStrokes` answers, asked once per edit
 * instead of once per page per frame. The predicate is deliberately identical: a stroke is on page
 * *k* when its **centreline** overlaps that page's band. Judging by the inflated [Stroke.bounds]
 * would put a stroke that ends exactly on a boundary onto the page below as well, and paint the
 * lower half of its end cap there as a stray dot.
 *
 * A stroke drawn across a page break appears in **both** buckets, which is what lets each clipped
 * pass paint its own side of the join.
 */
internal fun strokesByPage(
    blocks: List<Block>,
    pageCount: Int,
    pageHeight: Float,
    /**
     * Strokes to leave out — the current selection, which the overlay redraws itself under the
     * live transform. Kept out of the buckets rather than skipped at draw time so a selection
     * being dragged is never painted twice, once where it is and once where it was.
     */
    exclude: Set<Stroke> = emptySet(),
    /** Which strokes to bucket at all: those of one level of the drawing order. */
    include: (Stroke) -> Boolean = { true },
): List<List<Stroke>> {
    val pages = List(pageCount) { ArrayList<Stroke>() }
    if (pageCount == 0 || pageHeight <= 0f) return pages

    for (block in blocks) {
        if (block !is InkBlock || !block.visible) continue
        for (stroke in block.strokes) {
            if (stroke in exclude || !include(stroke)) continue
            val core = stroke.coreBounds
            var page = floor(core.top / pageHeight).toInt().coerceAtLeast(0)
            // `page * pageHeight < core.bottom` is the surviving half of the cull's test; the other
            // half is what `page` was seeded with.
            while (page < pageCount && page * pageHeight < core.bottom) {
                pages[page].add(stroke)
                page++
            }
        }
    }
    return pages
}

/**
 * Committed strokes bucketed by page for recording, with how far down each bucket's ink reaches.
 *
 * [lastPage] is the last page any stroke in a bucket touches: a page has to be drawn while *that*
 * is in view, not only while it is itself.
 */
internal class PageBuckets(val pages: List<List<Stroke>>, val lastPage: IntArray)

/** [strokesByPage] for paged view, where every stroke in a bucket is clipped to that one page. */
internal fun clippedBuckets(pages: List<List<Stroke>>): PageBuckets =
    PageBuckets(pages, IntArray(pages.size) { it })

/**
 * Groups committed strokes for continuous view, where each one goes on **one** page only: the page
 * its centreline starts on.
 *
 * Paged view can split a stroke at a break because the break is a gap the clip falls in. In
 * continuous view the two pages meet, and a stroke clipped at the join on both sides gets the
 * anti-aliased edge of two clips along one line — a hairline seam through every stroke written
 * across it. Recorded whole on the page where it starts, the stroke is drawn exactly as the single
 * unclipped pass used to draw it, and [PageBuckets.lastPage] keeps that page on screen for as long
 * as the stroke's tail is.
 *
 * The one thing given up is paint order *across* buckets: a stroke that runs down onto the next
 * page is painted under that page's strokes even where one of them is older. That takes a stroke
 * crossing a page edge to be overlapped by an earlier one on the page below.
 */
internal fun strokesByTopPage(
    blocks: List<Block>,
    pageCount: Int,
    pageHeight: Float,
    /** Strokes to leave out — the selection, as for [strokesByPage]. */
    exclude: Set<Stroke> = emptySet(),
    /** Which strokes to bucket at all, as for [strokesByPage]. */
    include: (Stroke) -> Boolean = { true },
): PageBuckets {
    val pages = List(pageCount) { ArrayList<Stroke>() }
    val lastPage = IntArray(pageCount) { it }
    if (pageCount == 0 || pageHeight <= 0f) return PageBuckets(pages, lastPage)

    for (block in blocks) {
        if (block !is InkBlock || !block.visible) continue
        for (stroke in block.strokes) {
            if (stroke in exclude || !include(stroke)) continue
            val core = stroke.coreBounds
            val page = floor(core.top / pageHeight).toInt().coerceIn(0, pageCount - 1)
            pages[page].add(stroke)
            // The page the stroke ends on, by the same test the clipped buckets use: a stroke that
            // stops exactly on a page edge does not reach the page below it.
            val reach = (ceil(core.bottom / pageHeight).toInt() - 1).coerceIn(page, pageCount - 1)
            if (reach > lastPage[page]) lastPage[page] = reach
        }
    }
    return PageBuckets(pages, lastPage)
}
