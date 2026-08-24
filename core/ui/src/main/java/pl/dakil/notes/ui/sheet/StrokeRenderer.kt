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
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.ink.StrokeOutline
import pl.dakil.notes.ink.Tessellator
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.WidthedPath
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
 * Every `Path` is reused between calls: rebuilding paths is the dominant allocation in a canvas
 * app, and a page of handwriting redrawn per frame would otherwise churn the heap continuously.
 */
class StrokeRenderer {

    private val path = Path()
    private val outline = StrokeOutline()

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
    fun DrawScope.drawStrokes(
        strokes: List<Stroke>,
        ptToPx: Float,
        toStripPx: (Float) -> Float,
        opacity: Float = 1f,
        docTop: Float = -Float.MAX_VALUE,
        docBottom: Float = Float.MAX_VALUE,
    ) {
        for (stroke in strokes) {
            // Assigned to pages by the centreline, not the inked extent. A stroke cut at a page
            // boundary ends exactly on it, and judging by the inked extent would place it on both
            // pages — painting the lower half of its end cap as a dot at the top of the page below.
            val core = stroke.coreBounds
            if (core.bottom <= docTop || core.top >= docBottom) continue
            drawStroke(stroke, ptToPx, toStripPx, opacity)
        }
    }

    fun DrawScope.drawStroke(
        stroke: Stroke,
        ptToPx: Float,
        toStripPx: (Float) -> Float,
        opacity: Float = 1f,
    ) {
        if (stroke.pointCount == 0) return

        val color = Color(stroke.color).let {
            if (opacity >= 1f) it else it.copy(alpha = it.alpha * opacity)
        }
        val blend = stroke.blend.toBlendMode()

        if (Tessellator.needsTessellation(stroke)) {
            buildOutlinePath(stroke, ptToPx, toStripPx)
            drawPath(path, color, style = Fill, blendMode = blend)
        } else {
            buildCenterlinePath(stroke, ptToPx, toStripPx)
            drawPath(
                path = path,
                color = color,
                style = DrawStroke(
                    width = (stroke.width * (stroke.widthFactors?.firstOrNull() ?: 1f)) * ptToPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
                blendMode = blend,
            )
        }
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

        if (builder.hasWidthVariation) {
            buildOutlinePath(builder, ptToPx, toStripPx)
            drawPath(path, color, style = Fill, blendMode = blend)
            return
        }

        path.reset()
        path.moveTo(builder.x(0) * ptToPx, toStripPx(builder.y(0)))
        appendSmoothed(n, { builder.x(it) }, { builder.y(it) }, ptToPx, toStripPx)
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
    ) {
        val n = outline.count
        if (n < 2) return
        path.reset()
        path.moveTo(outline.x(0) * ptToPx, toStripPx(outline.y(0)))
        appendSmoothed(n, { outline.x(it) }, { outline.y(it) }, ptToPx, toStripPx)
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

    private fun buildCenterlinePath(stroke: Stroke, ptToPx: Float, toStripPx: (Float) -> Float) {
        path.reset()
        path.moveTo(stroke.xs[0] * ptToPx, toStripPx(stroke.ys[0]))
        if (stroke.pointCount == 1) {
            // A dot: a zero-length segment with a round cap renders as a circle.
            path.lineTo(stroke.xs[0] * ptToPx, toStripPx(stroke.ys[0]))
            return
        }
        appendSmoothed(stroke.pointCount, { stroke.xs[it] }, { stroke.ys[it] }, ptToPx, toStripPx)
    }

    /**
     * Connects samples with quadratic segments through their midpoints.
     *
     * Straight `lineTo` between digitiser samples leaves visible faceting on curves at high zoom.
     * Passing the control point through each sample and anchoring at midpoints gives a C1-continuous
     * curve for one extra float per point and no curve-fitting pass.
     */
    private inline fun appendSmoothed(
        n: Int,
        x: (Int) -> Float,
        y: (Int) -> Float,
        ptToPx: Float,
        toStripPx: (Float) -> Float,
    ) {
        var previousX = x(0) * ptToPx
        var previousY = toStripPx(y(0))
        for (i in 1 until n - 1) {
            val cx = x(i) * ptToPx
            val cy = toStripPx(y(i))
            val midX = (previousX + cx) * 0.5f
            val midY = (previousY + cy) * 0.5f
            path.quadraticTo(previousX, previousY, midX, midY)
            previousX = cx
            previousY = cy
        }
        path.quadraticTo(previousX, previousY, x(n - 1) * ptToPx, toStripPx(y(n - 1)))
    }

    /**
     * Tessellates [path] and lays its contours into the reusable `Path`.
     *
     * All of them go into **one** `Path`, filled once. That is what makes the tessellator's
     * overlapping pieces read as a single shape: the non-zero rule resolves them into one coverage
     * mask before anything is blended, so a translucent pencil or a MULTIPLY highlighter is
     * composited exactly once and the pieces leave no seams where they meet.
     */
    private fun buildOutlinePath(source: WidthedPath, ptToPx: Float, toStripPx: (Float) -> Float) {
        Tessellator.tessellate(source, outline)
        path.reset()
        path.fillType = PathFillType.NonZero
        for (contour in 0 until outline.contourCount) {
            val start = outline.contourStart(contour)
            val end = outline.contourEnd(contour)
            if (end - start < 3) continue
            path.moveTo(outline.x(start) * ptToPx, toStripPx(outline.y(start)))
            for (i in start + 1 until end) {
                path.lineTo(outline.x(i) * ptToPx, toStripPx(outline.y(i)))
            }
            path.close()
        }
    }

    private fun BlendId.toBlendMode(): BlendMode = when (this) {
        BlendId.NORMAL -> BlendMode.SrcOver
        // Multiply is what stops overlapping highlighter passes compounding into a dark smear.
        BlendId.MULTIPLY -> BlendMode.Multiply
    }
}
