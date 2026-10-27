package pl.dakil.notes.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
 * A [ShapeSpec.Line] and a [ShapeSpec.Poly] are nothing but their points, and any affine map takes a
 * line to a line and a polygon to a polygon, so those two follow every transform.
 *
 * The rest are defined by extents and one angle. A similarity — uniform scale, rotation, translation,
 * and a mirror — keeps them what they are. Beyond that, a stretch along the page's axes keeps an
 * axis-aligned rectangle or ellipse a rectangle or ellipse, which is the case the selection's edge
 * grips produce, and a regular polygon pulled out of shape is still a polygon. Anything else — a
 * shear, or a stretch across a turned ellipse — would need parameters these types do not have, so
 * rather than record a lie the spec is dropped and an ordinary stroke is left, which is exactly what
 * the ink already is.
 */
fun ShapeSpec.transformedBy(m: Affine): ShapeSpec? {
    if (m.isIdentity) return this
    val det = m.a * m.d - m.b * m.c
    if (abs(det) < DEGENERATE_DET) return null

    when (this) {
        is ShapeSpec.Line -> return ShapeSpec.Line(
            m.mapX(x0, y0), m.mapY(x0, y0),
            m.mapX(x1, y1), m.mapY(x1, y1),
        )
        is ShapeSpec.Poly -> return mapPoints(xs, ys, m)
        else -> Unit
    }

    val scale = sqrt(abs(det))
    // A mirror is a turn followed by a flip: M = Rot(turn) · diag(1, −1). Its matrix has a == −d and
    // b == c where a turn has a == d and b == −c, so the similarity test differs by those two signs.
    val mirrored = det < 0f
    val similar = if (mirrored) {
        abs(m.a + m.d) <= SIMILARITY_TOLERANCE * scale && abs(m.b - m.c) <= SIMILARITY_TOLERANCE * scale
    } else {
        abs(m.a - m.d) <= SIMILARITY_TOLERANCE * scale && abs(m.b + m.c) <= SIMILARITY_TOLERANCE * scale
    }
    if (!similar) return stretchedBy(m)

    val turn = atan2(m.b, m.a)
    // A mirror reverses the sense of every angle: the direction θ comes out at turn − θ.
    fun angle(theta: Float) = if (mirrored) turn - theta else theta + turn

    return when (this) {
        is ShapeSpec.Arc -> ShapeSpec.Arc(
            m.mapX(cx, cy), m.mapY(cx, cy), r * scale, angle(start), if (mirrored) -sweep else sweep,
        )
        // Symmetric about their own axes, so turning the axis by the mirror describes the mirror
        // image exactly; a regular polygon's vertex set is likewise its own reflection.
        is ShapeSpec.Rect -> ShapeSpec.Rect(
            m.mapX(cx, cy), m.mapY(cx, cy), hw * scale, hh * scale, angle(rot), equilateral,
        )
        is ShapeSpec.Ngon -> ShapeSpec.Ngon(
            m.mapX(cx, cy), m.mapY(cx, cy), r * scale, angle(rot), sides,
        )
        is ShapeSpec.Ellipse -> ShapeSpec.Ellipse(
            m.mapX(cx, cy), m.mapY(cx, cy), rx * scale, ry * scale, angle(rot), equilateral,
        )
        is ShapeSpec.Line, is ShapeSpec.Poly -> error("handled above")
    }
}

/** The non-similar half of [transformedBy]. */
private fun ShapeSpec.stretchedBy(m: Affine): ShapeSpec? = when (this) {
    // Still a polygon, just no longer a regular one; its vertices are the ones the handles stood on.
    is ShapeSpec.Ngon -> {
        val xs = FloatArray(sides)
        val ys = FloatArray(sides)
        for (i in 0 until sides) {
            val a = rot + i * 2f * PI.toFloat() / sides
            xs[i] = cx + r * cos(a)
            ys[i] = cy + r * sin(a)
        }
        mapPoints(xs, ys, m)
    }
    is ShapeSpec.Rect -> {
        val (sw, sh) = axisStretch(m, rot)
        if (sw == null || sh == null) {
            // A turned rectangle stretched along the page is a parallelogram: still four corners.
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            val c = cos(rot)
            val s = sin(rot)
            for (i in 0 until 4) {
                // Same corner order as the shape's handles.
                val lx = if (i == 1 || i == 2) hw else -hw
                val ly = if (i == 2 || i == 3) hh else -hh
                xs[i] = cx + lx * c - ly * s
                ys[i] = cy + lx * s + ly * c
            }
            mapPoints(xs, ys, m)
        } else {
            ShapeSpec.Rect(m.mapX(cx, cy), m.mapY(cx, cy), hw * sw, hh * sh, rot, equilateral = false)
        }
    }
    is ShapeSpec.Ellipse -> {
        val (sw, sh) = axisStretch(m, rot)
        if (sw == null || sh == null) null
        else ShapeSpec.Ellipse(m.mapX(cx, cy), m.mapY(cx, cy), rx * sw, ry * sh, rot, equilateral = false)
    }
    // A stretched circle is an elliptical arc, which an Arc deliberately cannot be.
    is ShapeSpec.Arc, is ShapeSpec.Line, is ShapeSpec.Poly -> null
}

/**
 * How much [m] stretches a shape's own two axes, when [m] is a stretch along the page's axes and the
 * shape, turned by [rot], lies along them too; nulls otherwise.
 *
 * A shape turned a quarter has its width along the page's height, so the factors swap.
 */
private fun axisStretch(m: Affine, rot: Float): Pair<Float?, Float?> {
    val size = maxOf(abs(m.a), abs(m.d))
    if (abs(m.b) > SIMILARITY_TOLERANCE * size || abs(m.c) > SIMILARITY_TOLERANCE * size) return null to null
    val quarter = (PI / 2).toFloat()
    val quarters = Math.round(rot / quarter)
    if (abs(rot - quarters * quarter) > AXIS_TOLERANCE) return null to null
    val sx = abs(m.a)
    val sy = abs(m.d)
    return if (quarters % 2 == 0) sx to sy else sy to sx
}

private fun mapPoints(xs: FloatArray, ys: FloatArray, m: Affine): ShapeSpec.Poly {
    val nx = FloatArray(xs.size)
    val ny = FloatArray(ys.size)
    for (i in xs.indices) {
        nx[i] = m.mapX(xs[i], ys[i])
        ny[i] = m.mapY(xs[i], ys[i])
    }
    return ShapeSpec.Poly(nx, ny)
}

/** Below this a transform has flattened the shape onto a line, and no description survives it. */
private const val DEGENERATE_DET = 1e-9f

/** How far off the page's axes a shape may be turned and still count as lying along them: ~0.06°. */
private const val AXIS_TOLERANCE = 1e-3f

/** Relative slack when deciding whether an affine is close enough to a similarity to keep. */
private const val SIMILARITY_TOLERANCE = 1e-4f
