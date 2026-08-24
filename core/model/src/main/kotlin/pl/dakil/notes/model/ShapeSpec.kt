package pl.dakil.notes.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * What an auto-snapped stroke *is*, as opposed to where its ink lies.
 *
 * A recognised shape is committed as one ordinary [Stroke] tracing its outline — that is what makes
 * it a single selectable unit under the existing lasso stack, with no grouping machinery — and this
 * rides alongside so the shape stays re-editable. Given the parameters, the outline can always be
 * regenerated; given only the outline, a pentagon is indistinguishable from any other closed
 * polyline.
 *
 * Deliberately pure data. Fitting, outlining and handle dragging all live in `:core:ink`, so the
 * model stays a description of the document and nothing else.
 *
 * Coordinates are page units (points, 1/72 inch) and rotations are radians, clockwise, matching the
 * y-down page convention.
 */
sealed interface ShapeSpec {

    /** A straight segment. Open, like [Arc]; everything else closes. */
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ShapeSpec

    /**
     * A circular arc: the curved counterpart of [Line], and the only other open shape.
     *
     * [start] is where the pen began, in radians about ([cx], [cy]); [sweep] is signed, positive
     * turning the same way as increasing angle does on a y-down page, so it records which way round
     * the user drew. Its magnitude carries the whole shape — a shallow bow and a three-quarter turn
     * are the same type — which is what lets a line and an arc be judged against each other rather
     * than by separate rules.
     *
     * Deliberately circular and not elliptical. An arc has no closed figure to take its axes from,
     * so an elliptical fit to a partial curve is badly conditioned — the axes swing wildly on a
     * bow of less than a quarter turn — and the extra freedom mostly buys a better match to the
     * wobble. A curve too lopsided for a circle stays as the ink it was drawn with.
     */
    data class Arc(
        val cx: Float, val cy: Float,
        val r: Float,
        val start: Float,
        val sweep: Float,
    ) : ShapeSpec

    /**
     * A polygon taken from the corners the user actually drew.
     *
     * The fallback for shapes with no regular form worth imposing: a scalene triangle, a trapezium.
     * A regular polygon is fitted as [Ngon] instead, because a hand-drawn hexagon means a hexagon.
     */
    class Poly(val xs: FloatArray, val ys: FloatArray) : ShapeSpec {
        init {
            require(xs.size == ys.size) { "Poly coordinate arrays must be the same length" }
        }

        val vertexCount: Int get() = xs.size

        // Generated equals would compare the arrays by identity, which would make two identical
        // polygons unequal and quietly break round-trip assertions.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Poly) return false
            return xs.contentEquals(other.xs) && ys.contentEquals(other.ys)
        }

        override fun hashCode(): Int = 31 * xs.contentHashCode() + ys.contentHashCode()

        override fun toString(): String = "Poly(${xs.toList()}, ${ys.toList()})"
    }

    /**
     * An oriented rectangle, half-extents from the centre.
     *
     * Covers the square, and — at 45° — the diamond, which is why there is no separate rhombus.
     * [equilateral] is intent rather than measurement: it keeps the shape square while a corner is
     * dragged, instead of letting it drift into a rectangle the moment the user adjusts it.
     */
    data class Rect(
        val cx: Float, val cy: Float,
        val hw: Float, val hh: Float,
        val rot: Float,
        val equilateral: Boolean = false,
    ) : ShapeSpec

    /** A regular polygon of [sides] vertices at circumradius [r] — triangle through dodecagon. */
    data class Ngon(
        val cx: Float, val cy: Float,
        val r: Float,
        val rot: Float,
        val sides: Int,
    ) : ShapeSpec {
        init {
            require(sides >= 3) { "A polygon needs at least three sides" }
        }
    }

    /** An oriented ellipse. [equilateral] means circle, and keeps it circular while dragged. */
    data class Ellipse(
        val cx: Float, val cy: Float,
        val rx: Float, val ry: Float,
        val rot: Float,
        val equilateral: Boolean = false,
    ) : ShapeSpec
}

/**
 * Maps a shape through [m], or returns null when [m] would take it outside what these types can say.
 *
 * Only similarities — uniform scale, rotation, translation — are expressible: every shape here is
 * defined by extents and one angle, so a shear or a non-uniform scale would turn a circle into an
 * ellipse at an angle its own parameters cannot represent, and a square into a parallelogram with no
 * type at all. Rather than record a lie, such a transform drops the spec and leaves an ordinary
 * stroke, which is exactly what the ink already is.
 */
fun ShapeSpec.transformedBy(m: Affine): ShapeSpec? {
    if (m.isIdentity) return this
    val det = m.a * m.d - m.b * m.c
    // A reflection flips vertex order and the sense of rotation; not worth the special case.
    if (det <= 0f) return null
    val scale = sqrt(det)
    if (abs(m.a - m.d) > SIMILARITY_TOLERANCE * scale) return null
    if (abs(m.b + m.c) > SIMILARITY_TOLERANCE * scale) return null
    val turn = atan2(m.b, m.a)

    return when (this) {
        is ShapeSpec.Line -> ShapeSpec.Line(
            m.mapX(x0, y0), m.mapY(x0, y0),
            m.mapX(x1, y1), m.mapY(x1, y1),
        )
        is ShapeSpec.Arc -> ShapeSpec.Arc(
            m.mapX(cx, cy), m.mapY(cx, cy), r * scale, start + turn, sweep,
        )
        is ShapeSpec.Poly -> {
            val n = vertexCount
            val nx = FloatArray(n)
            val ny = FloatArray(n)
            for (i in 0 until n) {
                nx[i] = m.mapX(xs[i], ys[i])
                ny[i] = m.mapY(xs[i], ys[i])
            }
            ShapeSpec.Poly(nx, ny)
        }
        is ShapeSpec.Rect -> ShapeSpec.Rect(
            m.mapX(cx, cy), m.mapY(cx, cy), hw * scale, hh * scale, rot + turn, equilateral,
        )
        is ShapeSpec.Ngon -> ShapeSpec.Ngon(
            m.mapX(cx, cy), m.mapY(cx, cy), r * scale, rot + turn, sides,
        )
        is ShapeSpec.Ellipse -> ShapeSpec.Ellipse(
            m.mapX(cx, cy), m.mapY(cx, cy), rx * scale, ry * scale, rot + turn, equilateral,
        )
    }
}

/** Relative slack when deciding whether an affine is close enough to a similarity to keep. */
private const val SIMILARITY_TOLERANCE = 1e-4f
