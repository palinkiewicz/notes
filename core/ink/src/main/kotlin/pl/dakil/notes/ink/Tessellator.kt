package pl.dakil.notes.ink

import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.WidthedPath
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A polyline, as interleaved x/y pairs in [points], valid for `2 * count` entries.
 *
 * Reused across calls so tessellating the visible strokes each frame does not allocate. Two
 * producers fill it: [Tessellator], for which it is a set of closed fillable contours, and
 * `ShapeSpec` outlining, for which it is a single centreline. Both want the same thing — a growable
 * primitive buffer that survives between frames — so they share one rather than keeping a copy each.
 *
 * A producer that never calls [beginContour] gets one contour covering everything it added, which
 * is what keeps the centreline users unaware that contours exist at all.
 */
class StrokeOutline(initialCapacity: Int = 256) {
    var points: FloatArray = FloatArray(initialCapacity * 2)
        private set
    var count: Int = 0
        private set

    private var starts = IntArray(INITIAL_CONTOURS)

    /** How many closed contours [points] holds. Zero until the first point is added. */
    var contourCount: Int = 0
        private set

    fun x(i: Int): Float = points[i * 2]
    fun y(i: Int): Float = points[i * 2 + 1]

    /** First point index of contour [c]. */
    fun contourStart(c: Int): Int = starts[c]

    /** One past the last point index of contour [c]. */
    fun contourEnd(c: Int): Int = if (c + 1 < contourCount) starts[c + 1] else count

    internal fun clear() {
        count = 0
        contourCount = 0
    }

    /** Starts a new closed contour. A no-op while the current one is still empty. */
    internal fun beginContour() {
        if (contourCount > 0 && starts[contourCount - 1] == count) return
        if (contourCount == starts.size) starts = starts.copyOf(starts.size * 2)
        starts[contourCount] = count
        contourCount++
    }

    internal fun add(x: Float, y: Float) {
        if (contourCount == 0) beginContour()
        if ((count + 1) * 2 > points.size) {
            points = points.copyOf(maxOf((count + 1) * 2, points.size * 2))
        }
        points[count * 2] = x
        points[count * 2 + 1] = y
        count++
    }

    private companion object {
        const val INITIAL_CONTOURS = 8
    }
}

/**
 * Converts a stroke's centreline into a fillable outline.
 *
 * This exists only for strokes whose width varies. A constant-width stroke should be handed
 * straight to the platform's own path stroker (`DrawScope.drawPath` with a `Stroke` style), which
 * is hardware-accelerated and much faster than anything done here — see [needsTessellation].
 *
 * ### Why the outline comes in pieces
 *
 * The obvious way to outline a variable-width centreline is to walk one side offsetting by half the
 * local width, round the far cap, and walk back down the other — one closed contour, two points per
 * sample. It is also wrong wherever the pen turns sharply, and wrong in a way that gets worse the
 * wider the nib is.
 *
 * The offset of the *inside* of a turn doubles back on itself as soon as the turn is tighter than
 * the nib is wide, and the little loop it ties is wound against the rest of the contour — so the
 * non-zero fill rule cancels it to nothing and punches a hole through the ink. At a true reversal
 * the two offsets swap sides entirely, which hands the filler a bow tie. At a pen width of twenty
 * points, changing direction leaves a spike or a bite taken out of the corner.
 *
 * So the outline is cut into **runs**, each a ribbon that is simple by construction, and the cut is
 * made exactly where offsetting stops being safe: at a vertex where half the width, walked around
 * the corner, would outrun the segments meeting there, or where the turn is sharp enough that
 * mitring it would grow a spike. Both tests are the same statement — the curve turns tighter than
 * the nib can follow — and inside a run neither can happen, so the ribbon cannot cross itself.
 *
 * Where a run is cut, [addJoint] fills the wedge the two ribbons leave between them: a disc when
 * the corner is sharp enough to need rounding, and the bare wedge when it is not.
 *
 * ### Why the seams have to be exact
 *
 * Within a run every vertex is offset along the **bisector** of the two segments meeting there, so
 * consecutive pieces share their edge to the last bit rather than merely abutting near it.
 * Offsetting each segment along its own normal instead looks equivalent and is not: it leaves a
 * wedge at every vertex, open by the width of the nib times the angle of the turn. Half a degree of
 * turn under a twenty-point nib is a gap a fifth of a point wide and ten points long — a hairline
 * crack running most of the way across the stroke, at every single sample.
 *
 * Filling the lot as **one** `Path` under the non-zero rule is what makes the pieces a union:
 * overlapping ones are rasterised into a single coverage mask, so a translucent pencil or a
 * MULTIPLY highlighter is still composited exactly once.
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
     * Builds the outline of [path] into [into], as one closed contour per piece.
     *
     * [path] is a [WidthedPath] rather than a `Stroke` so that the wet stroke under the pen goes
     * through this same code: the ink being drawn and the ink already on the page are the same
     * picture, and only one of them existing at capture time is no reason for them to be drawn by
     * two different routines.
     */
    fun tessellate(path: WidthedPath, into: StrokeOutline = StrokeOutline()): StrokeOutline {
        into.clear()
        val n = path.pointCount
        if (n == 0) return into

        // A tap, or a pen lifted without ever moving: every sample is the same point, so there is
        // no direction anywhere to offset along and the mark is simply a disc.
        if (nextDistinct(path, 0, n - 1) < 0) {
            addDisc(into, path.pointX(0), path.pointY(0), path.pointWidth(0) * 0.5f)
            return into
        }

        var runStart = 0
        for (v in 1 until n - 1) {
            if (!breaksAt(path, v, n - 1)) continue
            addRibbon(into, path, runStart, v)
            addJoint(into, path, v, n - 1)
            runStart = v
        }
        addRibbon(into, path, runStart, n - 1)

        // Round caps. A whole disc at each end is half wasted — the inner half lies under the
        // ribbon it caps — and is still cheaper than reasoning about which way the half should
        // face, which is where the old outline's start cap used to get its sign wrong.
        addDisc(into, path.pointX(0), path.pointY(0), path.pointWidth(0) * 0.5f)
        addDisc(into, path.pointX(n - 1), path.pointY(n - 1), path.pointWidth(n - 1) * 0.5f)
        return into
    }

    /**
     * One run, as a closed ribbon: the left offsets from [lo] to [hi], then the right ones back.
     *
     * Simple by construction, because [breaksAt] has already ruled out every vertex where the two
     * sides could cross. That is what lets it be one contour rather than a piece per segment, and
     * keeps the outline at two points per sample.
     */
    private fun addRibbon(into: StrokeOutline, path: WidthedPath, lo: Int, hi: Int) {
        into.beginContour()
        var nx = 0f
        var ny = 0f
        for (i in lo..hi) {
            normalAt(path, i, lo, hi) { x, y -> nx = x; ny = y }
            val r = path.pointWidth(i) * 0.5f
            into.add(path.pointX(i) + nx * r, path.pointY(i) + ny * r)
        }
        for (i in hi downTo lo) {
            normalAt(path, i, lo, hi) { x, y -> nx = x; ny = y }
            val r = path.pointWidth(i) * 0.5f
            into.add(path.pointX(i) - nx * r, path.pointY(i) - ny * r)
        }
    }

    /**
     * Fills the gap where a run was cut.
     *
     * The two ribbons both end flat across the vertex, at right angles to their own segment, so
     * what is left uncovered is a wedge of exactly the turn's angle on the outside of the corner.
     * A disc rounds the corner as well as filling it, which a sharp turn needs and a slight one
     * does not: the wedge alone is three points against a disc's twenty.
     */
    private fun addJoint(into: StrokeOutline, path: WidthedPath, v: Int, hi: Int) {
        val previous = previousDistinct(path, v, 0)
        val next = nextDistinct(path, v, hi)
        if (previous < 0 || next < 0) return
        val r = path.pointWidth(v) * 0.5f
        if (r < MIN_CAP_RADIUS) return

        val x = path.pointX(v)
        val y = path.pointY(v)
        var inX = x - path.pointX(previous)
        var inY = y - path.pointY(previous)
        var outX = path.pointX(next) - x
        var outY = path.pointY(next) - y
        val inLength = sqrt(inX * inX + inY * inY)
        val outLength = sqrt(outX * outX + outY * outY)
        inX /= inLength; inY /= inLength
        outX /= outLength; outY /= outLength

        val cosTurn = (inX * outX + inY * outY).coerceIn(-1f, 1f)
        // How far the outer corner falls short of the arc a round nib would sweep. Past a fraction
        // of a point that reads as a chipped corner; below it, nobody can tell a chamfer from a
        // fillet and the wedge is enough.
        if (r * (1f - sqrt((1f + cosTurn) * 0.5f)) > JOINT_TOLERANCE) {
            addDisc(into, x, y, r)
            return
        }

        // The outer side is the one the turn bends away from.
        val side = if (inX * outY - inY * outX > 0f) -1f else 1f
        val ax = x + -inY * r * side
        val ay = y + inX * r * side
        val bx = x + -outY * r * side
        val by = y + outX * r * side
        into.beginContour()
        into.add(x, y)
        // Wound to match everything else: a triangle the other way round would cancel against the
        // ribbons it sits between rather than joining them.
        if ((ax - x) * (by - y) - (bx - x) * (ay - y) > 0f) {
            into.add(bx, by); into.add(ax, ay)
        } else {
            into.add(ax, ay); into.add(bx, by)
        }
    }

    /**
     * Whether the run has to be cut at vertex [v].
     *
     * Two ways a corner defeats a ribbon, and both are the same fact — the pen turned tighter than
     * its own nib. `r · tan(θ/2)` is how far the inner offset walks *backwards* along each segment
     * at the corner; once that outruns the segment, the two sides of the ribbon cross. And a turn
     * sharp enough to need a real mitre grows a spike as long as the mitre, which is not what the
     * end of a round nib looks like.
     */
    private fun breaksAt(path: WidthedPath, v: Int, hi: Int): Boolean {
        val previous = previousDistinct(path, v, 0)
        val next = nextDistinct(path, v, hi)
        if (previous < 0 || next < 0) return false

        val x = path.pointX(v)
        val y = path.pointY(v)
        var inX = x - path.pointX(previous)
        var inY = y - path.pointY(previous)
        var outX = path.pointX(next) - x
        var outY = path.pointY(next) - y
        val inLength = sqrt(inX * inX + inY * inY)
        val outLength = sqrt(outX * outX + outY * outY)
        inX /= inLength; inY /= inLength
        outX /= outLength; outY /= outLength

        val cosTurn = (inX * outX + inY * outY).coerceIn(-1f, 1f)
        if (cosTurn < COS_MAX_JOIN) return true
        if (cosTurn > COS_STRAIGHT) return false
        val tanHalf = sqrt((1f - cosTurn) / (1f + cosTurn))
        return path.pointWidth(v) * 0.5f * tanHalf > TURN_SAFETY * minOf(inLength, outLength)
    }

    /**
     * The unit normal to offset vertex [i] along, as the bisector of the segments meeting there,
     * lengthened by the mitre so the ribbon keeps its width around the bend.
     *
     * Written as an inline block rather than returning a pair because it is called twice per point
     * per frame for every variable-width stroke on screen, and a `Pair<Float, Float>` there boxes
     * two floats and allocates a third object each time.
     */
    private inline fun normalAt(
        path: WidthedPath,
        i: Int,
        lo: Int,
        hi: Int,
        block: (Float, Float) -> Unit,
    ) {
        val previous = if (i > lo) previousDistinct(path, i, lo) else -1
        val next = if (i < hi) nextDistinct(path, i, hi) else -1

        var inX = 0f; var inY = 0f
        if (previous >= 0) {
            inX = path.pointX(i) - path.pointX(previous)
            inY = path.pointY(i) - path.pointY(previous)
            val length = sqrt(inX * inX + inY * inY)
            inX /= length; inY /= length
        }
        var outX = 0f; var outY = 0f
        if (next >= 0) {
            outX = path.pointX(next) - path.pointX(i)
            outY = path.pointY(next) - path.pointY(i)
            val length = sqrt(outX * outX + outY * outY)
            outX /= length; outY /= length
        }

        if (previous < 0) { block(-outY, outX); return }
        if (next < 0) { block(-inY, inX); return }

        // The sum of two unit normals is 2·cos(θ/2) long, so dividing by its own squared length
        // both normalises it and applies the mitre in one step.
        val sumX = -inY + -outY
        val sumY = inX + outX
        val squared = sumX * sumX + sumY * sumY
        if (squared < MITRE_FLOOR) { block(-inY, inX); return }
        block(sumX * 2f / squared, sumY * 2f / squared)
    }

    /** The first index after [i] that is a different point, or -1. */
    private fun nextDistinct(path: WidthedPath, i: Int, hi: Int): Int {
        val x = path.pointX(i)
        val y = path.pointY(i)
        for (j in i + 1..hi) {
            val dx = path.pointX(j) - x
            val dy = path.pointY(j) - y
            if (dx * dx + dy * dy >= MIN_SEGMENT_SQ) return j
        }
        return -1
    }

    /** The last index before [i] that is a different point, or -1. */
    private fun previousDistinct(path: WidthedPath, i: Int, lo: Int): Int {
        val x = path.pointX(i)
        val y = path.pointY(i)
        for (j in i - 1 downTo lo) {
            val dx = path.pointX(j) - x
            val dy = path.pointY(j) - y
            if (dx * dx + dy * dy >= MIN_SEGMENT_SQ) return j
        }
        return -1
    }

    /** A closed disc, wound to match [addRibbon] so the two add rather than cancel. */
    private fun addDisc(into: StrokeOutline, cx: Float, cy: Float, r: Float) {
        if (r < MIN_CAP_RADIUS) return
        val steps = discSteps(r)
        into.beginContour()
        for (s in 0 until steps) {
            // Negated, and therefore clockwise in the same sense a ribbon is wound.
            val a = -TWO_PI * s / steps
            into.add(cx + cos(a) * r, cy + sin(a) * r)
        }
    }

    /** Segment count scaled to radius, so small nibs do not pay for arcs nobody can see. */
    private fun discSteps(radius: Float): Int = when {
        radius < 1.5f -> 8
        radius < 4f -> 12
        radius < 12f -> 20
        else -> 32
    }

    private const val TWO_PI = (2.0 * Math.PI).toFloat()
    private const val MIN_CAP_RADIUS = 0.2f
    private const val FLAT_TOLERANCE = 1e-3f

    /** Closer than this and two samples are the same point as far as direction goes. */
    private const val MIN_SEGMENT = 1e-3f
    private const val MIN_SEGMENT_SQ = MIN_SEGMENT * MIN_SEGMENT

    /** How deep a chamfer at a corner may be, in points, before a disc is worth its twenty points. */
    private const val JOINT_TOLERANCE = 0.1f

    /** cos 30°: past this the mitre starts to grow a spike a round nib would never leave. */
    private const val COS_MAX_JOIN = 0.866f

    /** Straight enough that the bisector is the segment normal and there is nothing to test. */
    private const val COS_STRAIGHT = 0.999999f

    /** The share of the shorter segment the inner offset may walk back over before the run is cut. */
    private const val TURN_SAFETY = 0.5f

    /** Below this the two normals are opposed and the bisector is meaningless. */
    private const val MITRE_FLOOR = 1e-6f
}
