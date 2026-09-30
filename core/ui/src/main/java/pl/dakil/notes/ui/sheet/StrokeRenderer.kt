package pl.dakil.notes.ui.sheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.withTransform
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.ink.StrokeOutline
import pl.dakil.notes.ink.Tessellator
import androidx.compose.ui.graphics.Matrix
import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.Stroke
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

/**
 * Draws vector strokes into a [DrawScope].
 *
 * Two paths on purpose:
 *
 * - **Constant width** strokes go straight to the platform's own path stroker, which is
 *   hardware-accelerated. A plain pen or highlighter never pays for tessellation.
 * - **Variable width** strokes are tessellated into a filled outline, because no platform stroker
 *   can taper. The wet stroke under the pen takes the same route as the ink already committed, so
 *   the width a pen is pressing out is on the page as it is drawn rather than appearing at pen-up.
 *
 * A [Stroke.filled] stroke adds a third path underneath either of those: its centreline closed back
 * to its start, filled by the even-odd rule. Even-odd rather than non-zero because that is the rule
 * `HitTester.pointInPolygon` decides a lasso by, so the region painted in is the region the app
 * already calls the inside of that loop — a doodle that crosses itself fills the way selecting it
 * would. It is drawn first and the ink over it, so the outline keeps its own weight at the edge.
 *
 * ### Why committed strokes are cached
 *
 * A committed [Stroke] is immutable, so its geometry can only change by becoming a different
 * instance. Its `Path` is therefore built once, in **document points**, and kept — identity is the
 * cache key, which is exact and needs no versioning. Only the wet stroke and the live shape are
 * rebuilt per frame, because only they actually change per frame.
 *
 * This used to rebuild every path on every draw, and re-run the tessellator for every
 * pressure-varying stroke with it. A note is redrawn whenever anything on it moves, so that made
 * the cost of one frame the cost of the whole document — which is what made a long note stutter.
 *
 * Caching in document points rather than pixels is what makes the cache survive zooming and page
 * offsets: both are applied as a transform at draw time, so neither invalidates a single entry.
 */
class StrokeRenderer {

    /** Scratch geometry for the wet stroke and the live shape, which are rebuilt every frame. */
    private val path = Path()

    /**
     * Scratch for the interior of a filled wet stroke or live shape.
     *
     * Its own path rather than the one above reused: the fill is the centreline and the ink over it
     * may be a tessellated outline, so the two are different geometry and both are wanted at once.
     */
    private val fillPath = Path()

    /** Scratch for the live selection transform, so a drag allocates nothing per frame. */
    private val scratchMatrix = Matrix()
    private val outline = StrokeOutline()

    /**
     * Built paths for committed strokes, in document points, keyed by stroke identity.
     *
     * Access-ordered and capped, so a note far longer than the screen cannot grow it without
     * bound: the strokes actually being drawn stay resident and the rest fall out.
     */
    private val cache = object : LinkedHashMap<Stroke, StrokePaths>(CACHE_INITIAL, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stroke, StrokePaths>): Boolean =
            size > CACHE_MAX
    }

    /**
     * One stroke's geometry: the ink itself, and the interior it encloses when it is filled.
     *
     * Held together rather than in two caches so that one eviction policy governs both and a
     * stroke can never be left holding half of what it needs to be drawn.
     */
    private class StrokePaths(val ink: Path, val fill: Path?)

    /** Drops every cached path. For switching documents, where none of them can be reused. */
    fun clearCache() = cache.clear()

    /**
     * Draws committed strokes, skipping any that fall outside [visible].
     *
     * Culling by the stroke's cached bounds is what makes a zoomed-in view of a dense page cost the
     * same as an empty one.
     */
    /**
     * @param docTop,docBottom the band of document y this pass is allowed to draw, in points. A
     *   stroke outside it is skipped entirely rather than clipped, which is what keeps a page's
     *   worth of drawing costing one page's worth of path building however long the note is.
     */
    /**
     * @param stripOffsetPx the strip y this pass's page starts at, in pixels. Paged view lays each
     *   page out a gap further down than the document says; because a pass only ever paints one
     *   page, that is a single translation rather than something to work out per point — which is
     *   what lets one cached path serve every page, zoom and view mode.
     */
    /**
     * @param matrix an extra transform in **document points**, applied inside the scale to pixels
     *   so the cached paths — which are in document points — are reused untouched. This is what
     *   lets a selection be dragged, scaled and turned at input rate without rebuilding a single
     *   path: the strokes are the same instances throughout and only the canvas moves.
     */
    fun DrawScope.drawStrokes(
        strokes: List<Stroke>,
        ptToPx: Float,
        stripOffsetPx: Float = 0f,
        opacity: Float = 1f,
        docTop: Float = -Float.MAX_VALUE,
        docBottom: Float = Float.MAX_VALUE,
        matrix: Affine? = null,
    ) {
        if (strokes.isEmpty()) return
        withTransform({
            translate(0f, stripOffsetPx)
            scale(ptToPx, ptToPx, Offset.Zero)
            if (matrix != null && !matrix.isIdentity) transform(matrix.toMatrix())
        }) {
            for (stroke in strokes) {
                // Assigned to pages by the centreline, not the inked extent. A stroke cut at a page
                // boundary ends exactly on it, and judging by the inked extent would place it on
                // both pages — painting the lower half of its end cap as a dot at the top of the
                // page below.
                //
                // Where the pass carries a transform the band has to be tested against where the
                // stroke is *going*, not where the document still says it is — a selection dragged
                // onto the next page would otherwise be culled off the page it is now on.
                val core = if (matrix == null) stroke.coreBounds else matrix.mapBounds(stroke.coreBounds)
                if (core.bottom <= docTop || core.top >= docBottom) continue
                drawCachedStroke(stroke, opacity)
            }
        }
    }

    /**
     * Draws one committed stroke, in a scope already scaled to document points.
     *
     * Widths are in points for the same reason the path is: the scale in force carries them to
     * pixels, so nothing here has to know what the zoom is.
     */
    private fun DrawScope.drawCachedStroke(stroke: Stroke, opacity: Float) {
        if (stroke.pointCount == 0) return

        val color = Color(stroke.color).let {
            if (opacity >= 1f) it else it.copy(alpha = it.alpha * opacity)
        }
        val blend = stroke.blend.toBlendMode()
        val cached = cachedPaths(stroke)

        // Under the ink, so the outline is the crisp edge of the mark and the fill merely reaches
        // to the middle of it. Painting it over the ink would thin every stroke by half its width.
        cached.fill?.let { drawPath(it, color, style = Fill, blendMode = blend) }

        if (stroke.hasWidthVariation) {
            drawPath(cached.ink, color, style = Fill, blendMode = blend)
        } else {
            drawPath(
                path = cached.ink,
                color = color,
                style = DrawStroke(
                    width = stroke.width * (stroke.widthFactors?.firstOrNull() ?: 1f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
                blendMode = blend,
            )
        }
    }

    /** The stroke's geometry in document points, built on first sight and kept. */
    private fun cachedPaths(stroke: Stroke): StrokePaths {
        cache[stroke]?.let { return it }
        val built = Path()
        if (stroke.hasWidthVariation) {
            Tessellator.tessellate(stroke, outline)
            layOutline(built)
        } else {
            built.moveTo(stroke.xs[0], stroke.ys[0])
            if (stroke.pointCount == 1) {
                // A dot: a zero-length segment with a round cap renders as a circle.
                built.lineTo(stroke.xs[0], stroke.ys[0])
            } else {
                built.appendSmoothed(stroke.pointCount, { stroke.xs[it] }, { stroke.ys[it] })
            }
        }
        val fill = if (!stroke.filled || stroke.pointCount < MIN_FILL_POINTS) null else {
            Path().also { layInterior(it, stroke.pointCount, { i -> stroke.xs[i] }, { i -> stroke.ys[i] }) }
        }
        val paths = StrokePaths(built, fill)
        cache[stroke] = paths
        return paths
    }

    /**
     * Draws the stroke currently under the pen, straight from the builder's live arrays.
     *
     * ### Why this tessellates
     *
     * It used to draw the wet stroke at the tool's nominal width and leave the taper to appear when
     * the stroke was committed, on the reasoning that the difference was imperceptible. It is not:
     * with pressure spanning a tenth of the width to all of it, a uniform ribbon is the wrong
     * picture, and the line visibly changes weight under the hand at pen-up. Handwriting is a
     * closed loop — people press harder because the line looked thin — so the width has to be
     * honest while the pen is still down or the feedback is a frame too late to act on.
     *
     * The cost is one tessellation per *frame*, not per sample: input at 240 Hz coalesces into one
     * redraw, and it is the same work a committed stroke of the same length already does.
     */
    fun DrawScope.drawWetStroke(builder: StrokeBuilder, ptToPx: Float, toStripPx: (Float) -> Float) {
        val n = builder.pointCount
        if (n == 0) return

        val color = Color(builder.color)
        val blend = builder.blend.toBlendMode()

        if (n == 1) {
            drawCircle(
                color = color,
                radius = builder.widthAt(0) * 0.5f * ptToPx,
                center = Offset(builder.x(0) * ptToPx, toStripPx(builder.y(0))),
                blendMode = blend,
            )
            return
        }

        // The interior a filled pen is laying down, closed back to where the stroke began. It grows
        // with the hand rather than appearing at pen-up, for the same reason the taper does: what
        // the mark is going to be has to be visible while there is still time to change it.
        if (builder.toolSpec.fill && n >= MIN_FILL_POINTS) {
            fillPath.reset()
            layInterior(fillPath, n, { builder.x(it) * ptToPx }, { toStripPx(builder.y(it)) })
            drawPath(fillPath, color, style = Fill, blendMode = blend)
        }

        if (builder.hasWidthVariation) {
            Tessellator.tessellate(builder, outline)
            path.reset()
            layOutline(path, ptToPx, toStripPx)
            drawPath(path, color, style = Fill, blendMode = blend)
            return
        }

        path.reset()
        path.moveTo(builder.x(0) * ptToPx, toStripPx(builder.y(0)))
        path.appendSmoothed(n, { builder.x(it) * ptToPx }, { toStripPx(builder.y(it)) })
        drawPath(
            path = path,
            color = color,
            style = DrawStroke(
                // The flat factor, not the nominal width: a tool that thins by speed alone holds
                // one width for the whole stroke, and it is not necessarily the one on the slider.
                width = builder.widthAt(0) * ptToPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = blend,
        )
    }

    /**
     * Draws the shape currently under the pen, straight from the controller's outline buffer.
     *
     * A recognised shape has one weight throughout, so this is the same cheap constant-width path
     * the wet stroke uses — and unlike the wet stroke, it is exactly how the shape will be rendered
     * once committed, because the committed stroke carries no width variation either.
     */
    @Suppress("LongParameterList")
    fun DrawScope.drawShapePreview(
        outline: StrokeOutline,
        ptToPx: Float,
        toStripPx: (Float) -> Float,
        width: Float,
        color: Int,
        blend: BlendId,
        filled: Boolean = false,
    ) {
        val n = outline.count
        if (n < 2) return
        if (filled && n >= MIN_FILL_POINTS) {
            fillPath.reset()
            layInterior(fillPath, n, { outline.x(it) * ptToPx }, { toStripPx(outline.y(it)) })
            drawPath(fillPath, Color(color), style = Fill, blendMode = blend.toBlendMode())
        }
        path.reset()
        path.moveTo(outline.x(0) * ptToPx, toStripPx(outline.y(0)))
        path.appendSmoothed(n, { outline.x(it) * ptToPx }, { toStripPx(outline.y(it)) })
        drawPath(
            path = path,
            color = Color(color),
            style = DrawStroke(
                width = width * ptToPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = blend.toBlendMode(),
        )
    }

    /**
     * Connects samples with quadratic segments through their midpoints.
     *
     * Straight `lineTo` between digitiser samples leaves visible faceting on curves at high zoom.
     * Passing the control point through each sample and anchoring at midpoints gives a C1-continuous
     * curve for one extra float per point and no curve-fitting pass.
     *
     * [x] and [y] hand back coordinates already in the destination space, so the same walk serves a
     * cached path in document points and a wet stroke mapped straight to the glass.
     */
    private inline fun Path.appendSmoothed(n: Int, x: (Int) -> Float, y: (Int) -> Float) {
        var previousX = x(0)
        var previousY = y(0)
        for (i in 1 until n - 1) {
            val cx = x(i)
            val cy = y(i)
            quadraticTo(previousX, previousY, (previousX + cx) * 0.5f, (previousY + cy) * 0.5f)
            previousX = cx
            previousY = cy
        }
        quadraticTo(previousX, previousY, x(n - 1), y(n - 1))
    }

    /**
     * Lays the region a stroke encloses into [into]: its centreline, closed, filled even-odd.
     *
     * Even-odd is the whole point. A stroke that crosses itself has an inside only by convention,
     * and the convention this app has already committed to is the one `HitTester.pointInPolygon`
     * uses to answer what a lasso caught — so a figure of eight fills the two lobes it looks like,
     * and what is painted in is what the selection would have taken.
     *
     * The same smoothing the ink gets, so the edge of the fill lies under the middle of the line
     * rather than cutting corners the drawn stroke rounds off.
     */
    private inline fun layInterior(into: Path, n: Int, x: (Int) -> Float, y: (Int) -> Float) {
        into.fillType = PathFillType.EvenOdd
        into.moveTo(x(0), y(0))
        into.appendSmoothed(n, x, y)
        into.close()
    }

    /**
     * Lays the tessellated contours in [outline] into [into].
     *
     * All of them go into **one** `Path`, filled once. That is what makes the tessellator's
     * overlapping pieces read as a single shape: the non-zero rule resolves them into one coverage
     * mask before anything is blended, so a translucent pencil or a MULTIPLY highlighter is
     * composited exactly once and the pieces leave no seams where they meet.
     */
    private inline fun layOutline(
        into: Path,
        ptToPx: Float = 1f,
        toStripPx: (Float) -> Float = { it },
    ) {
        into.fillType = PathFillType.NonZero
        for (contour in 0 until outline.contourCount) {
            val start = outline.contourStart(contour)
            val end = outline.contourEnd(contour)
            if (end - start < 3) continue
            into.moveTo(outline.x(start) * ptToPx, toStripPx(outline.y(start)))
            for (i in start + 1 until end) {
                into.lineTo(outline.x(i) * ptToPx, toStripPx(outline.y(i)))
            }
            into.close()
        }
    }

    private companion object {
        /**
         * Below this there is no region to speak of: two points enclose nothing, and a fill drawn
         * from them is a hairline along a line that is already being drawn.
         */
        const val MIN_FILL_POINTS = 3

        const val CACHE_INITIAL = 256

        /**
         * How many built paths to keep. Comfortably more than a screenful of dense handwriting at
         * any zoom, which is all that has to stay resident for a pan to cost nothing.
         */
        const val CACHE_MAX = 2048
    }

    /**
     * The model's six-number transform as the 4x4 the canvas wants.
     *
     * `Affine` is `matrix(a, b, c, d, tx, ty)` in SVG order — column-major 2x3 — so `a` and `b` are
     * the first *column*, not the first row. Compose's [Matrix] is column-major too, which is what
     * makes this the plain index mapping it looks like rather than a transpose.
     */
    private fun Affine.toMatrix(): Matrix = scratchMatrix.also {
        it.reset()
        it[0, 0] = a
        it[0, 1] = b
        it[1, 0] = c
        it[1, 1] = d
        it[3, 0] = tx
        it[3, 1] = ty
    }

    private fun BlendId.toBlendMode(): BlendMode = when (this) {
        BlendId.NORMAL -> BlendMode.SrcOver
        // Multiply is what stops overlapping highlighter passes compounding into a dark smear.
        BlendId.MULTIPLY -> BlendMode.Multiply
    }
}
