package pl.dakil.notes.ink

import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.ShapeSpec
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turning a [ShapeSpec] into points, and dragging it by one of them.
 *
 * Two jobs that have to agree with each other: the handles are the vertices of the outline, so the
 * apex the user grabs is the apex that moves.
 */

// ---- Handles -----------------------------------------------------------------------------------

/** How many apexes this shape can be dragged by. */
fun ShapeSpec.handleCount(): Int = when (this) {
    is ShapeSpec.Line -> 2
    is ShapeSpec.Arc -> 3
    is ShapeSpec.Poly -> vertexCount
    is ShapeSpec.Rect -> 4
    is ShapeSpec.Ngon -> sides
    is ShapeSpec.Ellipse -> 4
}

/** Writes handle [i] into `out[0]`, `out[1]`. Takes an array so a hit scan allocates nothing. */
fun ShapeSpec.handleInto(i: Int, out: FloatArray) {
    when (this) {
        is ShapeSpec.Line -> {
            out[0] = if (i == 0) x0 else x1
            out[1] = if (i == 0) y0 else y1
        }
        is ShapeSpec.Arc -> {
            // Ends and the crown of the bow. The crown is the one handle that is not on the drawn
            // path's ends, and it is what makes the curve adjustable: everything about an arc that
            // its two endpoints do not already say is how far it bulges away from them.
            val a = start + sweep * i * 0.5f
            out[0] = cx + r * cos(a)
            out[1] = cy + r * sin(a)
        }
        is ShapeSpec.Poly -> {
            out[0] = xs[i]
            out[1] = ys[i]
        }
        is ShapeSpec.Rect -> {
            val sx = if (i == 1 || i == 2) hw else -hw
            val sy = if (i == 2 || i == 3) hh else -hh
            rotateInto(sx, sy, rot, cx, cy, out)
        }
        is ShapeSpec.Ngon -> {
            val a = rot + i * TWO_PI / sides
            rotateInto(r * cos(a), r * sin(a), 0f, cx, cy, out)
        }
        is ShapeSpec.Ellipse -> {
            val sx = when (i) { 0 -> rx; 2 -> -rx; else -> 0f }
            val sy = when (i) { 1 -> ry; 3 -> -ry; else -> 0f }
            rotateInto(sx, sy, rot, cx, cy, out)
        }
    }
}

fun ShapeSpec.handleX(i: Int): Float = FloatArray(2).also { handleInto(i, it) }[0]

fun ShapeSpec.handleY(i: Int): Float = FloatArray(2).also { handleInto(i, it) }[1]

/**
 * The handle nearest ([x], [y]) — the apex the pen is resting on when a shape is recognised.
 *
 * The pen is at the end of the stroke, which for a closed shape is where it started, so this
 * reliably picks the corner the user drew last rather than an arbitrary one.
 */
fun ShapeSpec.nearestHandle(x: Float, y: Float): Int {
    val p = FloatArray(2)
    var best = 0
    var bestDistSq = Float.MAX_VALUE
    for (i in 0 until handleCount()) {
        handleInto(i, p)
        val dx = p[0] - x
        val dy = p[1] - y
        val d = dx * dx + dy * dy
        if (d < bestDistSq) {
            bestDistSq = d
            best = i
        }
    }
    return best
}

/**
 * A shape after a handle drag, and the handle the pen is on now.
 *
 * The index can change mid-drag: pulling a corner past the one that is pinned mirrors the shape,
 * and the apex under the pen is then the corner on the other side. Carrying it back out is what
 * keeps the next sample of the same gesture pinning the same point: the caller holds an index, and
 * an index that quietly means a different corner makes the shape flail instead of follow the hand.
 */
data class DraggedShape(val spec: ShapeSpec, val handle: Int)

/**
 * Returns this shape with handle [i] dragged to ([x], [y]), and where that handle now is.
 *
 * Every kind pins something and lets the rest follow, because a handle that only translated one
 * vertex would make a square stop being a square the instant it was adjusted. What is pinned is
 * chosen to be the thing the user is not touching: the opposite corner for a rectangle, the centre
 * for a regular polygon. `equilateral` shapes stay equilateral — it records intent, not a
 * measurement, so a square the user drew as a square keeps its corners at 90° while resized.
 *
 * Dragged past what is pinned, a shape mirrors about it rather than folding back on itself, which
 * is what makes a rectangle behave like the rubber band it looks like. The handle index follows the
 * pen across that flip; see [DraggedShape].
 */
fun ShapeSpec.dragHandle(i: Int, x: Float, y: Float): DraggedShape = when (this) {
    is ShapeSpec.Line ->
        DraggedShape(if (i == 0) copy(x0 = x, y0 = y) else copy(x1 = x, y1 = y), i)

    is ShapeSpec.Arc -> {
        val p = FloatArray(2)
        handleInto(0, p)
        val ax = p[0]
        val ay = p[1]
        handleInto(2, p)
        val bx = p[0]
        val by = p[1]
        val moved = if (i == 1) {
            // Only the component across the chord counts: the crown of an arc is on the chord's
            // perpendicular bisector by definition, so sliding the handle along the chord would be
            // asking the shape to be something it is not, and the arc would jump sideways.
            arcFromChord(ax, ay, bx, by, sagittaOf(ax, ay, bx, by, x, y))
        } else {
            // Dragging an end keeps the *proportion* of the bow rather than its depth, so
            // stretching an arc scales it instead of gradually flattening it towards a line.
            handleInto(1, p)
            val bow = sagittaOf(ax, ay, bx, by, p[0], p[1]) / max(hypot(bx - ax, by - ay), MIN_EXTENT)
            val x0 = if (i == 0) x else ax
            val y0 = if (i == 0) y else ay
            val x1 = if (i == 0) bx else x
            val y1 = if (i == 0) by else y
            arcFromChord(x0, y0, x1, y1, bow * hypot(x1 - x0, y1 - y0))
        }
        // An arc is built from its start, so an end that crosses the other stays the end it was.
        DraggedShape(moved, i)
    }

    is ShapeSpec.Poly -> {
        val nx = xs.copyOf()
        val ny = ys.copyOf()
        nx[i] = x
        ny[i] = y
        DraggedShape(ShapeSpec.Poly(nx, ny), i)
    }

    is ShapeSpec.Rect -> {
        val pinned = FloatArray(2).also { handleInto((i + 2) % 4, it) }
        val ux = cos(rot)
        val uy = sin(rot)
        val dx = x - pinned[0]
        val dy = y - pinned[1]
        // Distances along the rectangle's own axes, so dragging stays square to the shape however
        // it is rotated.
        var du = dx * ux + dy * uy
        var dv = -dx * uy + dy * ux
        if (equilateral) {
            // Split the difference rather than following one axis: the diagonal tracks the pen
            // instead of snapping to whichever component happens to be larger.
            val s = (abs(du) + abs(dv)) * 0.5f
            du = if (du < 0f) -s else s
            dv = if (dv < 0f) -s else s
        }
        val halfU = du * 0.5f
        val halfV = dv * 0.5f
        DraggedShape(
            ShapeSpec.Rect(
                cx = pinned[0] + halfU * ux - halfV * uy,
                cy = pinned[1] + halfU * uy + halfV * ux,
                hw = max(abs(halfU), MIN_EXTENT),
                hh = max(abs(halfV), MIN_EXTENT),
                rot = rot,
                equilateral = equilateral,
            ),
            // Which corner the pen ended up on is just which side of the pinned corner it is on,
            // per axis. Read off the signs rather than from proximity: a square that cannot follow
            // the pen off its diagonal has its nearest corner on the wrong side of the drag.
            rectHandle(du, dv),
        )
    }

    is ShapeSpec.Ngon -> {
        val dx = x - cx
        val dy = y - cy
        val radius = hypot(dx, dy)
        val moved = if (radius < MIN_EXTENT) this else ShapeSpec.Ngon(
            cx = cx,
            cy = cy,
            r = radius,
            // Setting rotation from the dragged vertex is what makes the gesture feel direct: the
            // vertex stays under the pen, so the polygon spins as well as resizes. It is also why
            // there is no flip to track here — the vertex follows the pen the whole way round.
            rot = atan2(dy, dx) - i * TWO_PI / sides,
            sides = sides,
        )
        DraggedShape(moved, i)
    }

    is ShapeSpec.Ellipse -> {
        val pinned = FloatArray(2).also { handleInto((i + 2) % 4, it) }
        if (equilateral) {
            // A circle has no meaningful axes, so treat the drag as a diameter: the new circle
            // passes through the pinned point and the pen.
            val radius = max(hypot(x - pinned[0], y - pinned[1]) * 0.5f, MIN_EXTENT)
            val ncx = (pinned[0] + x) * 0.5f
            val ncy = (pinned[1] + y) * 0.5f
            DraggedShape(
                ShapeSpec.Ellipse(
                    cx = ncx, cy = ncy, rx = radius, ry = radius,
                    // Turn the invisible axes to face the pen, the same trick as the polygon: on a
                    // circle the rotation shows up nowhere, and it is what holds the handle under
                    // the pen instead of letting the pinned point creep round the rim each sample.
                    rot = atan2(y - ncy, x - ncx) - i * QUARTER_TURN,
                    equilateral = true,
                ),
                i,
            )
        } else {
            val ux = cos(rot)
            val uy = sin(rot)
            val alongX = if (i == 0 || i == 2) ux else -uy
            val alongY = if (i == 0 || i == 2) uy else ux
            val along = (x - pinned[0]) * alongX + (y - pinned[1]) * alongY
            val radius = max(abs(along) * 0.5f, MIN_EXTENT)
            // Handles 0 and 1 point along that axis and 2 and 3 against it, so the pen is still on
            // its own end while the two agree in sign, and on the opposite one once it is dragged
            // through the pinned end.
            val ownSide = if (i == 0 || i == 1) along >= 0f else along <= 0f
            DraggedShape(
                ShapeSpec.Ellipse(
                    cx = pinned[0] + along * 0.5f * alongX,
                    cy = pinned[1] + along * 0.5f * alongY,
                    rx = if (i == 0 || i == 2) radius else rx,
                    ry = if (i == 1 || i == 3) radius else ry,
                    rot = rot,
                    equilateral = false,
                ),
                if (ownSide) i else (i + 2) % 4,
            )
        }
    }
}

/** Which corner of a rectangle lies [du] along and [dv] across its axes from the pinned one. */
private fun rectHandle(du: Float, dv: Float): Int =
    if (du < 0f) (if (dv < 0f) 0 else 3) else (if (dv < 0f) 1 else 2)

// ---- Outline -----------------------------------------------------------------------------------

/**
 * Fills [into] with the shape's centreline, closed for everything but a line and an arc.
 *
 * ### Why corners are emitted three times
 *
 * `StrokeRenderer` connects samples with quadratics through their midpoints, which is right for
 * handwriting and wrong for a square: a four-point square would render with its corners rounded off
 * by half the sample spacing. Repeating a vertex collapses the control polygon onto it, so the
 * curve passes exactly through the corner and leaves it in a straight line. Curves are not
 * duplicated — there the smoothing is doing what it is for.
 *
 * ### Why edges are dense
 *
 * Two points would draw a straight edge perfectly well, but `HitTester.strokesInPolygon` decides
 * selection by testing every point of a stroke, so a sparse shape could be lassoed by a loop that
 * only contains its corners.
 */
fun ShapeSpec.outlineInto(into: StrokeOutline) {
    into.clear()
    when (this) {
        is ShapeSpec.Line -> {
            val spacing = spacingFor(hypot(x1 - x0, y1 - y0))
            into.add(x0, y0)
            addEdge(into, x0, y0, x1, y1, spacing)
            into.add(x1, y1)
        }
        is ShapeSpec.Arc -> addArc(into)
        is ShapeSpec.Ellipse -> addEllipse(into)
        else -> addPolygon(into)
    }
}

/** Builds the committed stroke for this shape, at a single constant [width]. */
@Suppress("LongParameterList")
fun ShapeSpec.toStroke(
    tool: ToolId,
    color: Int,
    width: Float,
    blend: BlendId,
    into: StrokeOutline = StrokeOutline(),
    filled: Boolean = false,
): Stroke {
    outlineInto(into)
    val n = into.count
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        xs[i] = into.x(i)
        ys[i] = into.y(i)
    }
    // No width factors: a snapped shape is drawn at one weight, which also means the renderer skips
    // tessellation and hands it straight to the platform stroker.
    return Stroke(tool, color, width, blend, xs, ys, shape = this, filled = filled)
}

/**
 * The arc from ([x0], [y0]) to ([x1], [y1]) whose crown stands [sagitta] away from the chord.
 *
 * Signed: positive bulges to the left of the chord's direction, which is what carries the sense the
 * user drew in. Past half the chord length the arc is the major one, running the long way round,
 * so this covers everything from a shallow bow to very nearly a full circle in one expression.
 */
fun arcFromChord(x0: Float, y0: Float, x1: Float, y1: Float, sagitta: Float): ShapeSpec.Arc {
    val dx = x1 - x0
    val dy = y1 - y0
    val chord = hypot(dx, dy)
    // A collapsed chord has no perpendicular to measure against; keep the shape rather than divide
    // by zero, by treating the two ends as the extremes of a circle's diameter.
    if (chord < MIN_EXTENT) {
        val radius = max(abs(sagitta) * 0.5f, MIN_EXTENT)
        return ShapeSpec.Arc(x0, y0 - radius, radius, QUARTER_TURN, TWO_PI)
    }
    // Never let the bow vanish entirely: an arc with no curvature has no centre, and the user
    // dragging it flat means "as straight as an arc goes", not "stop being an arc".
    val s = if (abs(sagitta) < MIN_EXTENT) MIN_EXTENT else sagitta
    val half = chord * 0.5f
    val radius = (half * half + s * s) / (2f * abs(s))
    // Distance from the chord's midpoint to the centre, on the far side of the chord from the
    // crown while the arc is minor and on the same side once it is major.
    val offset = -(half * half - s * s) / (2f * s)
    val ux = -dy / chord
    val uy = dx / chord
    val cx = (x0 + x1) * 0.5f + ux * offset
    val cy = (y0 + y1) * 0.5f + uy * offset
    val start = atan2(y0 - cy, x0 - cx)
    var sweep = atan2(y1 - cy, x1 - cx) - start
    while (sweep > PI) sweep -= TWO_PI
    while (sweep < -PI) sweep += TWO_PI
    // The short way round passes through the crown only while the arc is minor; beyond that the
    // pen went the other way, and taking the short way would mirror the shape.
    if (abs(s) > half) sweep -= if (sweep < 0f) -TWO_PI else TWO_PI
    return ShapeSpec.Arc(cx, cy, max(radius, MIN_EXTENT), start, sweep)
}

/** Signed distance of ([px], [py]) from the chord, positive to the left of its direction. */
internal fun sagittaOf(x0: Float, y0: Float, x1: Float, y1: Float, px: Float, py: Float): Float {
    val dx = x1 - x0
    val dy = y1 - y0
    val chord = hypot(dx, dy)
    if (chord < MIN_EXTENT) return py - y0
    return ((px - x0) * -dy + (py - y0) * dx) / chord
}

private fun ShapeSpec.Arc.addArc(into: StrokeOutline) {
    val steps = arcSteps(r, sweep)
    for (i in 0..steps) {
        val a = start + sweep * i / steps
        into.add(cx + r * cos(a), cy + r * sin(a))
    }
}

/**
 * Step count for a circular sweep, on the same sagitta budget the ellipse is sampled to.
 *
 * Always even, so that the crown — the handle at half the sweep — lands on a sample rather than
 * between two. Handles that are not on the outline are the one way the two halves of this file can
 * disagree, and it shows up as the shape jumping when the user grabs it.
 */
private fun arcSteps(radius: Float, sweep: Float): Int {
    if (radius <= SAGITTA) return MIN_ARC_STEPS
    val step = 2f * acos(1f - min(SAGITTA / radius, 0.5f))
    val steps = ceil(abs(sweep) / step).toInt().coerceIn(MIN_ARC_STEPS, MAX_ELLIPSE_STEPS)
    return steps + (steps and 1)
}

private fun ShapeSpec.addPolygon(into: StrokeOutline) {
    val n = handleCount()
    val p = FloatArray(2)
    var perimeter = 0f
    val vx = FloatArray(n)
    val vy = FloatArray(n)
    for (i in 0 until n) {
        handleInto(i, p)
        vx[i] = p[0]
        vy[i] = p[1]
    }
    for (i in 0 until n) {
        val j = (i + 1) % n
        perimeter += hypot(vx[j] - vx[i], vy[j] - vy[i])
    }
    val spacing = spacingFor(perimeter)

    for (i in 0 until n) {
        val j = (i + 1) % n
        repeat(CORNER_REPEATS) { into.add(vx[i], vy[i]) }
        addEdge(into, vx[i], vy[i], vx[j], vy[j], spacing)
    }
    // Close on the first vertex, repeated so the join is as sharp as every other corner.
    repeat(CORNER_REPEATS) { into.add(vx[0], vy[0]) }
}

private fun ShapeSpec.Ellipse.addEllipse(into: StrokeOutline) {
    val big = max(rx, ry)
    // Step angle chosen so the sagitta — the gap between the chord and the true arc — stays under
    // half a coordinate quantisation step, which is the point at which it stops being visible.
    val steps = if (big <= SAGITTA) MIN_ELLIPSE_STEPS else {
        val byError = ceil(PI / acos(1f - min(SAGITTA / big, 0.5f))).toInt()
        byError.coerceIn(MIN_ELLIPSE_STEPS, MAX_ELLIPSE_STEPS)
    }
    val cosR = cos(rot)
    val sinR = sin(rot)
    for (i in 0..steps) {
        val a = i * TWO_PI / steps
        val ex = rx * cos(a)
        val ey = ry * sin(a)
        into.add(cx + ex * cosR - ey * sinR, cy + ex * sinR + ey * cosR)
    }
}

/** Adds the interior points of the segment, exclusive of both ends. */
private fun addEdge(into: StrokeOutline, x0: Float, y0: Float, x1: Float, y1: Float, spacing: Float) {
    val length = hypot(x1 - x0, y1 - y0)
    val steps = (length / spacing).toInt()
    for (k in 1 until steps) {
        val t = k / steps.toFloat()
        into.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
    }
}

/** Edge sampling step, opened up on a very large shape so the point budget is never blown. */
private fun spacingFor(totalLength: Float): Float =
    max(EDGE_SPACING_PT, totalLength / MAX_OUTLINE_POINTS)

private fun rotateInto(x: Float, y: Float, rot: Float, cx: Float, cy: Float, out: FloatArray) {
    if (rot == 0f) {
        out[0] = cx + x
        out[1] = cy + y
        return
    }
    val c = cos(rot)
    val s = sin(rot)
    out[0] = cx + x * c - y * s
    out[1] = cy + x * s + y * c
}

internal const val TWO_PI = (2.0 * kotlin.math.PI).toFloat()
private const val PI = kotlin.math.PI.toFloat()
private const val QUARTER_TURN = PI / 2f

/** Nothing smaller than this is a shape; it also keeps a collapsed drag out of the maths. */
private const val MIN_EXTENT = 0.5f
private const val EDGE_SPACING_PT = 1.5f
private const val MAX_OUTLINE_POINTS = 1024
private const val CORNER_REPEATS = 3
private const val SAGITTA = 0.5f / 32f
private const val MIN_ELLIPSE_STEPS = 24
private const val MIN_ARC_STEPS = 8
private const val MAX_ELLIPSE_STEPS = 512
