package pl.dakil.notes.ink

import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Stroke

/**
 * Geometric queries against committed strokes: what the eraser touched, what the lasso caught.
 *
 * Every query does a cheap [Stroke.bounds] rejection before touching point data. On a page with
 * thousands of strokes that is the difference between a responsive eraser and a stuttering one,
 * and it is why [Stroke.bounds] is cached.
 */
object HitTester {

    /** Indices of strokes whose outline comes within [radius] of the segment [x0,y0]–[x1,y1]. */
    fun strokesTouchedBySegment(
        strokes: List<Stroke>,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        radius: Float,
    ): List<Int> {
        val sweep = Rect.of(x0, y0, x1, y1).inflate(radius)
        var out: MutableList<Int>? = null
        for (i in strokes.indices) {
            val stroke = strokes[i]
            if (!stroke.bounds.intersects(sweep)) continue
            if (strokeIntersectsSegment(stroke, x0, y0, x1, y1, radius)) {
                (out ?: ArrayList<Int>(4).also { out = it }).add(i)
            }
        }
        return out ?: emptyList()
    }

    fun strokeIntersectsSegment(
        stroke: Stroke,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        radius: Float,
    ): Boolean {
        val n = stroke.pointCount
        if (n == 0) return false
        if (n == 1) {
            val r = radius + stroke.widthAt(0) * 0.5f
            return pointToSegmentDistanceSq(stroke.xs[0], stroke.ys[0], x0, y0, x1, y1) <= r * r
        }
        for (i in 0 until n - 1) {
            val r = radius + maxOf(stroke.widthAt(i), stroke.widthAt(i + 1)) * 0.5f
            if (segmentDistanceSq(
                    stroke.xs[i], stroke.ys[i], stroke.xs[i + 1], stroke.ys[i + 1],
                    x0, y0, x1, y1,
                ) <= r * r
            ) return true
        }
        return false
    }

    /**
     * Indices of strokes enclosed by a lasso polygon.
     *
     * [requireFullyInside] is the meaningful choice here. Selecting anything the loop merely
     * grazes makes a lasso around a word grab the line above it; requiring full containment
     * matches what people actually draw a loop to mean.
     */
    fun strokesInPolygon(
        strokes: List<Stroke>,
        polygonX: FloatArray,
        polygonY: FloatArray,
        pointCount: Int,
        requireFullyInside: Boolean = true,
    ): List<Int> {
        if (pointCount < 3) return emptyList()
        val lassoBounds = polygonBounds(polygonX, polygonY, pointCount)

        var out: MutableList<Int>? = null
        for (i in strokes.indices) {
            val stroke = strokes[i]
            if (!stroke.bounds.intersects(lassoBounds)) continue
            if (stroke.pointCount == 0) continue

            var inside = 0
            for (p in 0 until stroke.pointCount) {
                if (pointInPolygon(stroke.xs[p], stroke.ys[p], polygonX, polygonY, pointCount)) inside++
            }
            val hit = if (requireFullyInside) inside == stroke.pointCount else inside > 0
            if (hit) (out ?: ArrayList<Int>(8).also { out = it }).add(i)
        }
        return out ?: emptyList()
    }

    fun polygonBounds(xs: FloatArray, ys: FloatArray, count: Int): Rect {
        if (count == 0) return Rect.ZERO
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            if (xs[i] < minX) minX = xs[i]
            if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]
            if (ys[i] > maxY) maxY = ys[i]
        }
        return Rect(minX, minY, maxX, maxY)
    }

    fun boundsOf(strokes: List<Stroke>, indices: List<Int>): Rect {
        if (indices.isEmpty()) return Rect.ZERO
        var out = Rect.EMPTY
        for (i in indices) out = out.union(strokes[i].bounds)
        return out
    }

    /**
     * The stroke under a tap, or null if the tap landed on bare paper.
     *
     * Searched from the end of the list because that is the order the ink was laid down in: where
     * two strokes overlap, the one drawn last is the one on top and the one the user is pointing at.
     *
     * [radius] is a reach around the fingertip, in document points, and is added to half the
     * stroke's own width — a hairline drawn at 0.5 pt has to be as easy to hit as a highlighter.
     */
    fun strokeAt(strokes: List<Stroke>, x: Float, y: Float, radius: Float): Int? {
        for (i in strokes.indices.reversed()) {
            val stroke = strokes[i]
            if (!stroke.bounds.inflate(radius).contains(x, y)) continue
            if (strokeIntersectsSegment(stroke, x, y, x, y, radius)) return i
        }
        return null
    }

    /** Even-odd ray casting. The polygon is treated as closed regardless of the caller. */
    fun pointInPolygon(x: Float, y: Float, xs: FloatArray, ys: FloatArray, count: Int): Boolean {
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val yi = ys[i]
            val yj = ys[j]
            if ((yi > y) != (yj > y)) {
                val t = (y - yi) / (yj - yi)
                if (x < xs[i] + t * (xs[j] - xs[i])) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun pointToSegmentDistanceSq(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9f) {
            val ax = px - x0
            val ay = py - y0
            return ax * ax + ay * ay
        }
        val t = (((px - x0) * dx + (py - y0) * dy) / lenSq).coerceIn(0f, 1f)
        val cx = x0 + t * dx - px
        val cy = y0 + t * dy - py
        return cx * cx + cy * cy
    }

    /** Squared distance between two segments; zero when they cross. */
    fun segmentDistanceSq(
        ax0: Float, ay0: Float, ax1: Float, ay1: Float,
        bx0: Float, by0: Float, bx1: Float, by1: Float,
    ): Float {
        if (segmentsIntersect(ax0, ay0, ax1, ay1, bx0, by0, bx1, by1)) return 0f
        return minOf(
            pointToSegmentDistanceSq(ax0, ay0, bx0, by0, bx1, by1),
            pointToSegmentDistanceSq(ax1, ay1, bx0, by0, bx1, by1),
            pointToSegmentDistanceSq(bx0, by0, ax0, ay0, ax1, ay1),
            pointToSegmentDistanceSq(bx1, by1, ax0, ay0, ax1, ay1),
        )
    }

    private fun segmentsIntersect(
        ax0: Float, ay0: Float, ax1: Float, ay1: Float,
        bx0: Float, by0: Float, bx1: Float, by1: Float,
    ): Boolean {
        val d1 = cross(bx0, by0, bx1, by1, ax0, ay0)
        val d2 = cross(bx0, by0, bx1, by1, ax1, ay1)
        val d3 = cross(ax0, ay0, ax1, ay1, bx0, by0)
        val d4 = cross(ax0, ay0, ax1, ay1, bx1, by1)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun cross(x0: Float, y0: Float, x1: Float, y1: Float, px: Float, py: Float): Float =
        (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)
}
