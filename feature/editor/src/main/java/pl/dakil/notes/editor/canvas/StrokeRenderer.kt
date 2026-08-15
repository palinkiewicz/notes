package pl.dakil.notes.editor.canvas

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.ink.StrokeOutline
import pl.dakil.notes.ink.Tessellator
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
 *   can taper.
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

    /** Draws the stroke currently under the pen, straight from the builder's live arrays. */
    fun DrawScope.drawWetStroke(builder: StrokeBuilder, ptToPx: Float, toStripPx: (Float) -> Float) {
        val n = builder.pointCount
        if (n == 0) return

        val spec = builder.toolSpec
        val color = Color(builder.color)
        val blend = spec.blend.toBlendMode()

        path.reset()
        path.moveTo(builder.x(0) * ptToPx, toStripPx(builder.y(0)))
        if (n == 1) {
            drawCircle(
                color = color,
                radius = builder.widthAt(0) * 0.5f * ptToPx,
                center = androidx.compose.ui.geometry.Offset(builder.x(0) * ptToPx, toStripPx(builder.y(0))),
                blendMode = blend,
            )
            return
        }

        // The wet stroke always uses the cheap constant-width path. Tessellating on every input
        // sample would be wasted work: the stroke is re-rendered properly the moment it is
        // committed, and at 240 Hz the difference in taper is imperceptible anyway.
        appendSmoothed(n, { builder.x(it) }, { builder.y(it) }, ptToPx, toStripPx)
        drawPath(
            path = path,
            color = color,
            style = DrawStroke(
                width = spec.width * ptToPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = blend,
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

    private fun buildOutlinePath(stroke: Stroke, ptToPx: Float, toStripPx: (Float) -> Float) {
        Tessellator.tessellate(stroke, outline)
        path.reset()
        if (outline.count == 0) return
        path.moveTo(outline.x(0) * ptToPx, toStripPx(outline.y(0)))
        for (i in 1 until outline.count) {
            path.lineTo(outline.x(i) * ptToPx, toStripPx(outline.y(i)))
        }
        path.close()
    }

    private fun BlendId.toBlendMode(): BlendMode = when (this) {
        BlendId.NORMAL -> BlendMode.SrcOver
        // Multiply is what stops overlapping highlighter passes compounding into a dark smear.
        BlendId.MULTIPLY -> BlendMode.Multiply
    }
}
