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

    /** Drawn width at point [i], in points. */
    fun widthAt(i: Int): Float = width * (widthFactors?.get(i) ?: 1f)

    /** Returns a copy sharing the point arrays, used by recolour and tool-change edits. */
    fun withStyle(color: Int = this.color, width: Float = this.width, blend: BlendId = this.blend): Stroke =
        Stroke(tool, color, width, blend, xs, ys, widthFactors, tilts, times)

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
        return Stroke(tool, color, width * scale, blend, nx, ny, widthFactors, tilts, times)
    }
}
