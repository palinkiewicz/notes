package pl.dakil.notes.ink

import pl.dakil.notes.model.Stroke
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A polyline, as interleaved x/y pairs in [points], valid for `2 * count` entries.
 *
 * Reused across calls so tessellating the visible strokes each frame does not allocate. Two
 * producers fill it: [Tessellator], for which it is a closed fillable contour, and `ShapeSpec`
 * outlining, for which it is a centreline. Both want the same thing — a growable primitive buffer
 * that survives between frames — so they share one rather than keeping a copy each.
 */
class StrokeOutline(initialCapacity: Int = 256) {
    var points: FloatArray = FloatArray(initialCapacity * 2)
        private set
    var count: Int = 0
        private set

    fun x(i: Int): Float = points[i * 2]
    fun y(i: Int): Float = points[i * 2 + 1]

    internal fun clear() {
        count = 0
    }

    internal fun add(x: Float, y: Float) {
        if ((count + 1) * 2 > points.size) {
            points = points.copyOf(maxOf((count + 1) * 2, points.size * 2))
        }
        points[count * 2] = x
        points[count * 2 + 1] = y
        count++
    }
}

/**
 * Converts a stroke's centreline into a fillable outline.
 *
 * This exists only for strokes whose width varies. A constant-width stroke should be handed
 * straight to the platform's own path stroker (`DrawScope.drawPath` with a `Stroke` style), which
 * is hardware-accelerated and much faster than anything done here — see [needsTessellation].
 */
object Tessellator {

    /**
     * True when [stroke] has per-point width variation and therefore cannot be drawn by the
     * platform stroker. Checking this first is the single biggest rendering win: highlighter and
     * plain-pen strokes skip tessellation entirely.
     */
    fun needsTessellation(stroke: Stroke): Boolean {
        val factors = stroke.widthFactors ?: return false
        if (factors.isEmpty()) return false
        val first = factors[0]
        for (f in factors) {
            if (kotlin.math.abs(f - first) > FLAT_TOLERANCE) return true
        }
        return false
    }

    /**
     * Builds the outline of [stroke] into [into].
     *
     * Walks one side of the centreline offsetting by half the local width, rounds the far cap,
     * walks back along the other side, and rounds the near cap — producing a single closed
     * contour that can be filled with the non-zero rule.
     */
    fun tessellate(stroke: Stroke, into: StrokeOutline = StrokeOutline()): StrokeOutline {
        into.clear()
        val n = stroke.pointCount
        if (n == 0) return into

        if (n == 1) {
            addCircle(into, stroke.xs[0], stroke.ys[0], stroke.widthAt(0) * 0.5f)
            return into
        }

        // Forward pass: the left offset.
        for (i in 0 until n) {
            val (nx, ny) = normalAt(stroke, i)
            val r = stroke.widthAt(i) * 0.5f
            into.add(stroke.xs[i] + nx * r, stroke.ys[i] + ny * r)
        }

        // Cap at the far end, swinging from the left offset round to the right one.
        addCap(into, stroke, n - 1, forward = true)

        // Return pass: the right offset.
        for (i in n - 1 downTo 0) {
            val (nx, ny) = normalAt(stroke, i)
            val r = stroke.widthAt(i) * 0.5f
            into.add(stroke.xs[i] - nx * r, stroke.ys[i] - ny * r)
        }

        // Cap at the start, closing the contour.
        addCap(into, stroke, 0, forward = false)

        return into
    }

    /**
     * Unit normal at point [i], from the average direction of the adjacent segments.
     *
     * Averaging rather than using a single segment is what keeps the outline from pinching at
     * sharp corners in handwriting.
     */
    private fun normalAt(stroke: Stroke, i: Int): Pair<Float, Float> {
        val n = stroke.pointCount
        var tx: Float
        var ty: Float
        when {
            i == 0 -> {
                tx = stroke.xs[1] - stroke.xs[0]
                ty = stroke.ys[1] - stroke.ys[0]
            }
            i == n - 1 -> {
                tx = stroke.xs[n - 1] - stroke.xs[n - 2]
                ty = stroke.ys[n - 1] - stroke.ys[n - 2]
            }
            else -> {
                tx = stroke.xs[i + 1] - stroke.xs[i - 1]
                ty = stroke.ys[i + 1] - stroke.ys[i - 1]
            }
        }
        val len = sqrt(tx * tx + ty * ty)
        if (len < 1e-6f) return 0f to 1f
        tx /= len
        ty /= len
        return -ty to tx
    }

    private fun addCap(into: StrokeOutline, stroke: Stroke, i: Int, forward: Boolean) {
        val (nx, ny) = normalAt(stroke, i)
        val r = stroke.widthAt(i) * 0.5f
        if (r < MIN_CAP_RADIUS) return

        val cx = stroke.xs[i]
        val cy = stroke.ys[i]
        // The far cap starts at the left offset (+normal); the near cap starts at the right one
        // (-normal), because the return pass has already walked back down that side. Both sweep a
        // half turn in the same direction, so the contour stays consistently wound.
        val startAngle = kotlin.math.atan2(ny, nx) + if (forward) 0f else PI
        val sweep = -PI
        val steps = capSteps(r)
        for (s in 1 until steps) {
            val a = startAngle + sweep * (s.toFloat() / steps)
            into.add(cx + cos(a) * r, cy + sin(a) * r)
        }
    }

    private fun addCircle(into: StrokeOutline, cx: Float, cy: Float, r: Float) {
        val steps = capSteps(r) * 2
        for (s in 0 until steps) {
            val a = TWO_PI * s / steps
            into.add(cx + cos(a) * r, cy + sin(a) * r)
        }
    }

    /** Segment count scaled to radius, so small nibs do not pay for arcs nobody can see. */
    private fun capSteps(radius: Float): Int = when {
        radius < 1.5f -> 4
        radius < 4f -> 6
        radius < 12f -> 10
        else -> 16
    }

    private const val PI = Math.PI.toFloat()
    private const val TWO_PI = (2.0 * Math.PI).toFloat()
    private const val MIN_CAP_RADIUS = 0.2f
    private const val FLAT_TOLERANCE = 1e-3f
}
