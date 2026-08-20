package pl.dakil.notes.model

/**
 * The drawing tools. The ordinal is persisted in the binary stroke stream, so entries may be
 * appended but never reordered or removed.
 */
enum class ToolId {
    PEN,
    FOUNTAIN_PEN,
    PENCIL,
    HIGHLIGHTER,
    ERASER_STROKE,
    ERASER_POINT,
    LASSO;

    val isDrawing: Boolean get() = this == PEN || this == FOUNTAIN_PEN || this == PENCIL || this == HIGHLIGHTER

    val isEraser: Boolean get() = this == ERASER_STROKE || this == ERASER_POINT

    companion object {
        private val VALUES = entries.toTypedArray()
        /** Unknown ids from a newer file degrade to a plain pen rather than failing the load. */
        fun fromOrdinal(o: Int): ToolId = VALUES.getOrElse(o) { PEN }
    }
}

/** Compositing modes a stroke can be drawn with. Ordinals are persisted; append only. */
enum class BlendId {
    NORMAL,
    MULTIPLY;

    companion object {
        private val VALUES = entries.toTypedArray()
        fun fromOrdinal(o: Int): BlendId = VALUES.getOrElse(o) { NORMAL }
    }
}

/**
 * One committed vector stroke.
 *
 * Point data is held in parallel primitive arrays rather than a `List<Point>`: a dense handwriting
 * page runs to tens of thousands of samples, and per-point object headers would dominate both heap
 * and GC pressure on the render path. [widthFactors], [tilts] and [times] are null when the input
 * device did not report them or the tool does not modulate, so a mouse-drawn highlighter stroke
 * costs nothing extra.
 *
 * Coordinates are in page units (points, 1/72 inch).
 */
class Stroke(
    val tool: ToolId,
    /** Packed ARGB, matching `android.graphics.Color` layout. */
    val color: Int,
    /** The widest this stroke ever gets, in points. Per-point width is [width] × its factor. */
    val width: Float,
    val blend: BlendId,
    val xs: FloatArray,
    val ys: FloatArray,
    /**
     * Per-point width as a fraction of [width], in (0, 1].
     *
     * Modulation is baked in at capture time rather than re-derived at render time. A stroke is a
     * record of what the user drew: later changing the pen's pressure sensitivity must not reach
     * back and reshape strokes they already made. It also keeps the renderer free of tool state.
     */
    val widthFactors: FloatArray? = null,
    /** Stylus tilt in radians from the screen normal. Reserved for nib-shaped tools. */
    val tilts: FloatArray? = null,
    /** Milliseconds since the first sample of the stroke. */
    val times: IntArray? = null,
    /**
     * What this stroke *is*, when it came from shape recognition rather than free drawing.
     *
     * Null for ordinary ink, which is almost every stroke. It is metadata, not geometry: [xs] and
     * [ys] already trace the shape, so a build that ignores this renders exactly the same picture.
     * Keeping it lets a shape stay re-editable instead of decaying into an anonymous polyline the
     * moment it is committed.
     */
    val shape: ShapeSpec? = null,
) {
    init {
        require(xs.size == ys.size) { "Stroke coordinate arrays must be the same length" }
    }

    val pointCount: Int get() = xs.size

    /**
     * Bounding box inflated by half the maximum drawn width, cached because both hit-testing and
     * tile invalidation query it on every frame.
     */
    val bounds: Rect by lazy(LazyThreadSafetyMode.NONE) {
        if (xs.isEmpty()) return@lazy Rect.ZERO
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in xs.indices) {
            val x = xs[i]
            val y = ys[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        Rect(minX, minY, maxX, maxY).inflate(width * 0.5f + 0.5f)
    }

    /**
     * The bounding box of the centreline alone, with no allowance for drawn width.
     *
     * Distinct from [bounds] because they answer different questions. [bounds] asks "where might
     * this stroke put ink", which is what hit-testing and invalidation need. This asks "where is
     * this stroke", which is what deciding *which page it is on* needs — and the difference is
     * exactly half a stroke width, which is the size of an end cap. Using the inflated box to
     * assign a stroke to a page draws the cap of a stroke that merely ends at the page boundary as
     * a disc on the page below.
     */
    val coreBounds: Rect by lazy(LazyThreadSafetyMode.NONE) {
        if (xs.isEmpty()) return@lazy Rect.ZERO
        val pad = width * 0.5f + 0.5f
        bounds.inflate(-pad)
    }

    /** Drawn width at point [i], in points. */
    fun widthAt(i: Int): Float = width * (widthFactors?.get(i) ?: 1f)

    /** Returns a copy sharing the point arrays, used by recolour and tool-change edits. */
    fun withStyle(color: Int = this.color, width: Float = this.width, blend: BlendId = this.blend): Stroke =
        Stroke(tool, color, width, blend, xs, ys, widthFactors, tilts, times, shape)

    /** Returns a copy carrying [shape], sharing the point arrays. Used when loading a note. */
    fun withShape(shape: ShapeSpec?): Stroke =
        Stroke(tool, color, width, blend, xs, ys, widthFactors, tilts, times, shape)

    /** Returns a copy with every point mapped through [m]. Used by lasso move/scale/rotate. */
    fun transformed(m: Affine): Stroke {
        if (m.isIdentity) return this
        val n = xs.size
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            nx[i] = m.mapX(xs[i], ys[i])
            ny[i] = m.mapY(xs[i], ys[i])
        }
        // Uniform scale factor, so a scaled selection keeps its visual stroke weight.
        val scale = kotlin.math.sqrt(kotlin.math.abs(m.a * m.d - m.b * m.c))
        // A sheared square is a parallelogram, which no ShapeSpec can describe; transformedBy says
        // so by returning null, and the result is honest ink rather than a shape that lies.
        return Stroke(tool, color, width * scale, blend, nx, ny, widthFactors, tilts, times, shape?.transformedBy(m))
    }

    /**
     * Splits this stroke into the pieces lying between [top] and [bottom], cut exactly at the
     * boundary.
     *
     * The primitive behind every page operation. A stroke that runs across a page break is one
     * stroke — the break is presentation, not structure — but duplicating, deleting or reordering a
     * page has to act on *the part of the drawing that is on that page* and nothing else. Selecting
     * whole strokes by overlap would copy the half that belongs to the neighbouring page along with
     * it, and leave the other page's content behind when it moved.
     *
     * Crossings are interpolated, so a cut edge lands on the page boundary rather than at the
     * nearest sample: pressure, tilt and timing are carried across the new endpoint too, and a
     * tapering stroke keeps its taper through the cut.
     */
    fun clippedToBand(top: Float, bottom: Float): List<Stroke> {
        val n = xs.size
        if (n == 0) return emptyList()
        if (n == 1) return if (ys[0] in top..bottom) listOf(this) else emptyList()

        // Nothing to cut: hand back the original untouched, so a deliberate dot — two coincident
        // points — is never mistaken for the degenerate offcut filtered out below.
        if (ys.all { it in top..bottom }) return listOf(this)

        val out = ArrayList<Stroke>(1)
        var run: Run? = null

        fun flush() {
            // A segment grazing the boundary produces a piece of no length, which would render as
            // a dot on an otherwise empty page. It is an artefact of the cut, not part of the mark.
            run?.let { if (it.size >= 2 && it.extent() > MIN_PIECE) out += it.toStroke(this) }
            run = null
        }

        for (i in 0 until n - 1) {
            val y0 = ys[i]
            val y1 = ys[i + 1]
            var t0 = 0f
            var t1 = 1f

            if (y1 == y0) {
                if (y0 < top || y0 > bottom) {
                    flush()
                    continue
                }
            } else {
                val dy = y1 - y0
                val ta = (top - y0) / dy
                val tb = (bottom - y0) / dy
                t0 = maxOf(0f, minOf(ta, tb))
                t1 = minOf(1f, maxOf(ta, tb))
                if (t0 > t1) {
                    flush()
                    continue
                }
            }

            // A run continues only if the previous segment ended exactly at this point; any gap
            // means the stroke left the band and came back, which is two separate marks.
            if (run == null || t0 > 0f) {
                flush()
                run = Run(n).also { it.add(this, i, t0) }
            }
            if (t1 < 1f) {
                run!!.add(this, i, t1)
                flush()
            } else {
                run!!.add(this, i, 1f)
            }
        }
        flush()
        return out
    }

    /** Accumulates one contiguous in-band piece. */
    private class Run(capacity: Int) {
        val xs = FloatArray(capacity + 2)
        val ys = FloatArray(capacity + 2)
        val factors = FloatArray(capacity + 2)
        val tilts = FloatArray(capacity + 2)
        val times = IntArray(capacity + 2)
        var size = 0

        /** Adds the point [t] of the way from sample [i] to sample [i]+1. */
        fun add(source: Stroke, i: Int, t: Float) {
            val j = i + 1
            xs[size] = lerp(source.xs[i], source.xs[j], t)
            ys[size] = lerp(source.ys[i], source.ys[j], t)
            source.widthFactors?.let { factors[size] = lerp(it[i], it[j], t) }
            source.tilts?.let { tilts[size] = lerp(it[i], it[j], t) }
            source.times?.let { times[size] = lerp(it[i].toFloat(), it[j].toFloat(), t).toInt() }
            size++
        }

        fun toStroke(source: Stroke): Stroke = Stroke(
            tool = source.tool,
            color = source.color,
            width = source.width,
            blend = source.blend,
            xs = xs.copyOf(size),
            ys = ys.copyOf(size),
            widthFactors = if (source.widthFactors != null) factors.copyOf(size) else null,
            tilts = if (source.tilts != null) tilts.copyOf(size) else null,
            times = if (source.times != null) times.copyOf(size) else null,
            // Half a square is not a square. Note that clippedToBand returns the original instance
            // when the band contains the whole stroke, so an uncut shape keeps its spec.
            shape = null,
        )

        /** Longest side of the piece's bounding box, in points. */
        fun extent(): Float {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until size) {
                if (xs[i] < minX) minX = xs[i]
                if (xs[i] > maxX) maxX = xs[i]
                if (ys[i] < minY) minY = ys[i]
                if (ys[i] > maxY) maxY = ys[i]
            }
            return maxOf(maxX - minX, maxY - minY)
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }

    private companion object {
        /** Below this a cut offcut is not a mark, in points. Coordinates quantise to 1/32 pt. */
        const val MIN_PIECE = 0.05f
    }
}
