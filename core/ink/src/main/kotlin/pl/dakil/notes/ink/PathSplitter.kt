package pl.dakil.notes.ink

import pl.dakil.notes.model.Stroke

/**
 * The point eraser: removes the part of a stroke that falls inside the eraser disc and returns
 * whatever survives.
 *
 * This is the operation that makes a vector eraser feel like a real one. Rubbing out the middle of
 * a line has to leave two independent strokes, each still carrying its own width and pressure
 * profile — not a masked bitmap, and not a whole line vanishing at a touch.
 */
object PathSplitter {

    /**
     * Erases a disc of [radius] centred at ([cx], [cy]) from [stroke].
     *
     * Returns the surviving fragments: the original stroke unchanged when the disc missed, an
     * empty list when it covered everything, or two or more pieces when it cut through the middle.
     */
    fun erase(stroke: Stroke, cx: Float, cy: Float, radius: Float): List<Stroke> {
        val n = stroke.pointCount
        if (n == 0) return emptyList()

        // The eraser cuts the centreline, but the stroke has width: a disc that only grazes the
        // edge of a fat highlighter should still take a bite out of it.
        val keep = BooleanArray(n)
        var anyRemoved = false
        var anyKept = false
        for (i in 0 until n) {
            val effective = radius + stroke.widthAt(i) * 0.5f
            val dx = stroke.xs[i] - cx
            val dy = stroke.ys[i] - cy
            val hit = dx * dx + dy * dy <= effective * effective
            keep[i] = !hit
            if (hit) anyRemoved = true else anyKept = true
        }

        if (!anyRemoved) return listOf(stroke)
        if (!anyKept) return emptyList()

        return fragmentsOf(stroke, keep)
    }

    /**
     * Erases along the segment the eraser travelled this frame, rather than at a single point.
     *
     * Sampling only at frame positions leaves gaps whenever the user swipes quickly — the eraser
     * appears to skip. Treating the movement as a swept capsule fixes that without needing a
     * higher input rate.
     */
    fun eraseAlongSegment(
        stroke: Stroke,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        radius: Float,
    ): List<Stroke> {
        val n = stroke.pointCount
        if (n == 0) return emptyList()

        val keep = BooleanArray(n)
        var anyRemoved = false
        var anyKept = false
        for (i in 0 until n) {
            val effective = radius + stroke.widthAt(i) * 0.5f
            val distSq = HitTester.pointToSegmentDistanceSq(stroke.xs[i], stroke.ys[i], x0, y0, x1, y1)
            val hit = distSq <= effective * effective
            keep[i] = !hit
            if (hit) anyRemoved = true else anyKept = true
        }

        if (!anyRemoved) return listOf(stroke)
        if (!anyKept) return emptyList()

        return fragmentsOf(stroke, keep)
    }

    /** Splits [stroke] into maximal runs of kept points. */
    private fun fragmentsOf(stroke: Stroke, keep: BooleanArray): List<Stroke> {
        val out = ArrayList<Stroke>(2)
        var start = -1
        for (i in keep.indices) {
            if (keep[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                emit(stroke, start, i, out)
                start = -1
            }
        }
        if (start >= 0) emit(stroke, start, keep.size, out)
        return out
    }

    /** Emits points `[from, to)` as a fragment, dropping runs too short to be visible. */
    private fun emit(stroke: Stroke, from: Int, to: Int, out: MutableList<Stroke>) {
        val length = to - from
        // A single orphaned point is a speck the user did not ask to keep.
        if (length < MIN_FRAGMENT_POINTS) return

        out += Stroke(
            tool = stroke.tool,
            color = stroke.color,
            width = stroke.width,
            blend = stroke.blend,
            xs = stroke.xs.copyOfRange(from, to),
            ys = stroke.ys.copyOfRange(from, to),
            widthFactors = stroke.widthFactors?.copyOfRange(from, to),
            tilts = stroke.tilts?.copyOfRange(from, to),
            times = stroke.times?.copyOfRange(from, to),
            // A rubbed-out circle is an arc, not a circle. Note that both entry points hand back
            // the original instance when nothing was hit, so an untouched shape keeps its spec.
            shape = null,
        )
    }

    private const val MIN_FRAGMENT_POINTS = 2
}
