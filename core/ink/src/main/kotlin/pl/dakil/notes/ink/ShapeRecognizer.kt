package pl.dakil.notes.ink

import pl.dakil.notes.model.ShapeSpec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Guesses what shape a stroke was meant to be.
 *
 * ### Why it proposes generously and judges strictly
 *
 * The obvious design — detect corners, count them, emit that polygon — is brittle in exactly the
 * way a user notices: one missed corner on a wobbly square and you get a triangle. So corner
 * detection here only *proposes*. Every candidate, however it was arrived at, is then measured by
 * one metric against the drawn path, and the best one wins. A mis-counted corner costs nothing,
 * because the rectangle and the circle were in the running anyway.
 *
 * ### Why the metric is symmetric
 *
 * Measuring only "how far is each drawn point from the ideal shape" says a three-quarter arc is an
 * excellent circle: every point the user drew really is on the circle. The missing quarter is only
 * visible from the other direction, so the ideal is sampled back against the drawing too, and the
 * worse of the two decides. This is what stops a C becoming an O and a spiral becoming anything.
 *
 * ### Why an open stroke has two answers and not one
 *
 * A line and an arc are the same shape at different curvatures, so they are proposed together and
 * the arc has to earn its extra freedom. Which one comes back is settled last, on how far the
 * winner bows away from its own chord, rather than on which fits the wobble by a hair — see
 * [regularise]. That is what lets a curve be drawn deliberately without a straight line ever
 * arriving bent.
 *
 * ### Why simpler answers are favoured
 *
 * Freely-fitted candidates can always match the drawing better than regular ones — a twelve-sided
 * polygon fitted to a wobbly circle is nearly exact. A per-vertex penalty, heavier for free-form
 * polygons than for regular ones, is what makes the recogniser answer "circle" to a circle instead
 * of "hendecagon". It is the whole reason the output is stable enough to trust.
 */
object ShapeRecognizer {

    /**
     * Returns the shape [xs]/[ys] were meant to be, or null to leave the stroke exactly as drawn.
     *
     * Null is the common and correct answer for handwriting; the caller keeps drawing.
     */
    fun recognize(xs: FloatArray, ys: FloatArray, count: Int): ShapeSpec? {
        if (count < MIN_SAMPLES) return null
        val pathLength = polylineLength(xs, ys, count)
        if (pathLength < MIN_PATH_LENGTH) return null

        val n = RESAMPLE_COUNT
        val rx = FloatArray(n)
        val ry = FloatArray(n)
        if (!resample(xs, ys, count, pathLength, rx, ry)) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, rx[i]); maxX = max(maxX, rx[i])
            minY = min(minY, ry[i]); maxY = max(maxY, ry[i])
        }
        val diagonal = hypot(maxX - minX, maxY - minY)
        if (diagonal < MIN_DIAGONAL) return null

        val gap = hypot(rx[n - 1] - rx[0], ry[n - 1] - ry[0])
        val closed = gap < CLOSE_FRACTION * pathLength

        // Deliberately generous: a candidate that does not belong simply loses.
        val candidates = ArrayList<ShapeSpec>(MAX_NGON_SIDES + 5)
        candidates += ShapeSpec.Line(rx[0], ry[0], rx[n - 1], ry[n - 1])
        if (closed) {
            candidates += fitRect(rx, ry, n)
            candidates += fitEllipse(rx, ry, n)
            for (sides in 3..MAX_NGON_SIDES) candidates += fitNgon(rx, ry, n, sides)
            fitPoly(rx, ry, n)?.let { candidates += it }
        } else {
            // The arc runs only while the ends are apart. A curve that comes back to where it
            // started is a circle the user did not quite finish, and the ellipse describes that
            // better than an arc of 350 degrees does.
            fitArc(rx, ry, n, diagonal)?.let { candidates += it }
        }

        val scratch = StrokeOutline(RESAMPLE_COUNT)
        var best: ShapeSpec? = null
        var bestScore = Float.MAX_VALUE
        for (candidate in candidates) {
            val error = errorOf(candidate, rx, ry, n, diagonal, pathLength, scratch)
            // Whether a candidate is good enough at all is judged on the fit alone; the penalty
            // below only decides between candidates that already qualify.
            if (error.isNaN() || error > MAX_MEAN_ERROR) continue
            val score = (error + ERROR_FLOOR) * (1f + penaltyOf(candidate))
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.let { regularise(it) }
    }

    // ---- Scoring ---------------------------------------------------------------------------

    /**
     * Symmetric mean distance between the drawn path and [spec], as a fraction of [diagonal], or
     * NaN when the two disagree badly enough somewhere that no average should excuse it.
     */
    @Suppress("LongParameterList")
    private fun errorOf(
        spec: ShapeSpec,
        rx: FloatArray,
        ry: FloatArray,
        n: Int,
        diagonal: Float,
        pathLength: Float,
        scratch: StrokeOutline,
    ): Float {
        compactOutline(spec, scratch)
        val m = scratch.count
        if (m < 2) return Float.NaN

        // How far the pen travelled, against how far it would have to travel to draw this shape.
        // Distance alone cannot tell a straight line from a line of handwriting: every letter sits
        // close to the baseline, so the average deviation stays small however much the pen wandered
        // up and down. The length it covered getting there gives that away immediately.
        var perimeter = 0f
        for (i in 0 until m - 1) perimeter += hypot(scratch.x(i + 1) - scratch.x(i), scratch.y(i + 1) - scratch.y(i))
        if (perimeter <= EPSILON) return Float.NaN
        val ratio = pathLength / perimeter
        if (ratio < MIN_LENGTH_RATIO || ratio > MAX_LENGTH_RATIO) return Float.NaN

        var forwardSum = 0f
        var worst = 0f
        for (i in 0 until n) {
            val d = sqrt(distanceToPolylineSq(rx[i], ry[i], scratch, m))
            forwardSum += d
            if (d > worst) worst = d
        }

        // The ideal sampled back against the drawing. Subsampled because the outline is only ever a
        // few dozen points and every one of them costs a pass over the drawn path.
        val step = max(1, m / BACKWARD_SAMPLES)
        var backwardSum = 0f
        var backwardCount = 0
        var i = 0
        while (i < m) {
            val d = sqrt(distanceToPathSq(scratch.x(i), scratch.y(i), rx, ry, n))
            backwardSum += d
            backwardCount++
            if (d > worst) worst = d
            i += step
        }
        if (backwardCount == 0) return Float.NaN

        if (worst > MAX_POINT_ERROR * diagonal) return Float.NaN
        val mean = 0.5f * (forwardSum / n + backwardSum / backwardCount)
        return mean / diagonal
    }

    /**
     * How much worse a candidate is allowed to fit before a simpler one is preferred.
     *
     * Proportional rather than a flat surcharge, and that is the important part. A fixed penalty
     * has to be sized for one situation and is wrong in the other: big enough to stop a wobbly
     * circle being read as a nine-sided polygon, it also swamps the small honest difference between
     * a circle and a cleanly drawn octagon. Scaling with the error instead makes the rule read as
     * "a more complicated answer must fit proportionally better", which holds at both ends — when
     * the drawing is clean, small absolute differences still decide; when it is messy, every
     * candidate is wrong by roughly the same amount and the simplest one wins.
     *
     * Free-form polygons are penalised an order of magnitude more heavily per vertex than regular
     * ones, because they are fitted *to* the drawing and could otherwise match any scribble.
     */
    private fun penaltyOf(spec: ShapeSpec): Float = when (spec) {
        is ShapeSpec.Poly -> FREE_VERTEX_PENALTY * spec.vertexCount
        is ShapeSpec.Arc -> ARC_PENALTY
        else -> REGULAR_VERTEX_PENALTY * spec.handleCount()
    }

    /** The candidate as a coarse polyline: exact for polygons, ample for ellipses. */
    private fun compactOutline(spec: ShapeSpec, into: StrokeOutline) {
        into.clear()
        val p = FloatArray(2)
        when (spec) {
            is ShapeSpec.Line -> {
                into.add(spec.x0, spec.y0)
                into.add(spec.x1, spec.y1)
            }
            is ShapeSpec.Arc -> {
                // Sampled at the density the ellipse is, so an arc and a circle are measured on
                // comparable footings rather than one of them being flattered by coarser chords.
                val steps = max(2, (SCORE_ELLIPSE_STEPS * abs(spec.sweep) / TWO_PI).roundToInt())
                for (i in 0..steps) {
                    val a = spec.start + spec.sweep * i / steps
                    into.add(spec.cx + spec.r * cos(a), spec.cy + spec.r * sin(a))
                }
            }
            is ShapeSpec.Ellipse -> {
                val cosR = cos(spec.rot)
                val sinR = sin(spec.rot)
                for (i in 0..SCORE_ELLIPSE_STEPS) {
                    val a = i * TWO_PI / SCORE_ELLIPSE_STEPS
                    val ex = spec.rx * cos(a)
                    val ey = spec.ry * sin(a)
                    into.add(
                        spec.cx + ex * cosR - ey * sinR,
                        spec.cy + ex * sinR + ey * cosR,
                    )
                }
            }
            else -> {
                val k = spec.handleCount()
                for (i in 0 until k) {
                    spec.handleInto(i, p)
                    into.add(p[0], p[1])
                }
                spec.handleInto(0, p)
                into.add(p[0], p[1])
            }
        }
    }

    private fun distanceToPolylineSq(x: Float, y: Float, line: StrokeOutline, m: Int): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until m - 1) {
            val d = HitTester.pointToSegmentDistanceSq(x, y, line.x(i), line.y(i), line.x(i + 1), line.y(i + 1))
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToPathSq(x: Float, y: Float, rx: FloatArray, ry: FloatArray, n: Int): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until n - 1) {
            val d = HitTester.pointToSegmentDistanceSq(x, y, rx[i], ry[i], rx[i + 1], ry[i + 1])
            if (d < best) best = d
        }
        return best
    }

    // ---- Fits ------------------------------------------------------------------------------

    /**
     * The minimum-area oriented rectangle, by sweeping the rotation.
     *
     * A convex hull and rotating calipers would be the textbook answer and roughly three times the
     * code; over a fixed 64 samples a one-degree sweep with a refinement pass costs microseconds
     * and is accurate to a twentieth of a degree.
     */
    private fun fitRect(rx: FloatArray, ry: FloatArray, n: Int): ShapeSpec.Rect {
        val out = FloatArray(4)
        var bestRot = 0f
        var bestArea = Float.MAX_VALUE
        var coarse = 0
        while (coarse < RECT_SWEEP_STEPS) {
            val theta = coarse * QUARTER_TURN / RECT_SWEEP_STEPS
            val area = rectAt(rx, ry, n, theta, out)
            if (area < bestArea) {
                bestArea = area
                bestRot = theta
            }
            coarse++
        }
        val span = QUARTER_TURN / RECT_SWEEP_STEPS
        var refine = -RECT_REFINE_STEPS
        var refinedRot = bestRot
        while (refine <= RECT_REFINE_STEPS) {
            val theta = bestRot + span * refine / RECT_REFINE_STEPS
            val area = rectAt(rx, ry, n, theta, out)
            if (area < bestArea) {
                bestArea = area
                refinedRot = theta
            }
            refine++
        }
        rectAt(rx, ry, n, refinedRot, out)
        refineEdges(rx, ry, n, refinedRot, out)
        return ShapeSpec.Rect(out[0], out[1], out[2], out[3], refinedRot)
    }

    /**
     * Replaces the bounding box's extents with the average position of the points on each side.
     *
     * A bounding box is set by the four most extreme samples, so an unsteady hand pushes every side
     * outwards by the full amount of its wobble and never pulls one back — the error is one-sided
     * and grows with the shakiness. Averaging the points that lie on a side cancels it instead,
     * because the wobble is as often inwards as outwards. Sides are found by asking which one each
     * point is nearest, rather than by taking a fixed share of the samples, so a long thin
     * rectangle whose ends carry few points is measured as well as a square.
     */
    private fun refineEdges(rx: FloatArray, ry: FloatArray, n: Int, theta: Float, out: FloatArray) {
        val c = cos(theta)
        val s = sin(theta)
        val cu = out[0] * c + out[1] * s
        val cv = -out[0] * s + out[1] * c
        val hw = out[2]
        val hh = out[3]

        var left = 0f; var leftN = 0
        var right = 0f; var rightN = 0
        var top = 0f; var topN = 0
        var bottom = 0f; var bottomN = 0
        for (i in 0 until n) {
            val u = rx[i] * c + ry[i] * s - cu
            val v = -rx[i] * s + ry[i] * c - cv
            if (hw - abs(u) < hh - abs(v)) {
                if (u > 0f) { right += u; rightN++ } else { left += u; leftN++ }
            } else {
                if (v > 0f) { bottom += v; bottomN++ } else { top += v; topN++ }
            }
        }
        // A side with no points of its own means this is not a rectangle at all; the bounding box
        // is then as good an answer as any, and the score will reject it shortly.
        if (leftN == 0 || rightN == 0 || topN == 0 || bottomN == 0) return

        left /= leftN; right /= rightN; top /= topN; bottom /= bottomN
        val ncu = cu + (left + right) * 0.5f
        val ncv = cv + (top + bottom) * 0.5f
        out[0] = ncu * c - ncv * s
        out[1] = ncu * s + ncv * c
        out[2] = max((right - left) * 0.5f, MIN_RADIUS)
        out[3] = max((bottom - top) * 0.5f, MIN_RADIUS)
    }

    /** Area of the bounding box in the frame rotated by [theta]; writes cx, cy, hw, hh to [out]. */
    private fun rectAt(rx: FloatArray, ry: FloatArray, n: Int, theta: Float, out: FloatArray): Float {
        val c = cos(theta)
        val s = sin(theta)
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until n) {
            val u = rx[i] * c + ry[i] * s
            val v = -rx[i] * s + ry[i] * c
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val hw = (maxU - minU) * 0.5f
        val hh = (maxV - minV) * 0.5f
        val cu = (maxU + minU) * 0.5f
        val cv = (maxV + minV) * 0.5f
        // Back out of the rotated frame, so the centre is in page coordinates.
        out[0] = cu * c - cv * s
        out[1] = cu * s + cv * c
        out[2] = hw
        out[3] = hh
        return hw * hh
    }

    /**
     * The circle the samples lie on, cut to the piece the pen actually walked.
     *
     * The centre and radius come from the algebraic fit that minimises the *squared* residual of
     * `(x−a)² + (y−b)² − r²` rather than the geometric distance. It is one 2x2 solve instead of an
     * iteration, and its known bias — under-weighting a short arc's curvature, so it reads slightly
     * flatter than the true circle — is well inside what the scoring tolerates, whereas an
     * iterative fit on a stroke this noisy is as likely to wander as to converge.
     *
     * The sweep is then accumulated step by step rather than taken from the two ends, which is what
     * lets an arc past a half turn be told from the shallow one drawn the other way round: the ends
     * alone cannot say which side of the circle the pen went.
     *
     * Null when the samples are too straight for a circle to mean anything — the arc through three
     * nearly-collinear points has a radius limited only by arithmetic — and the line candidate,
     * which is what such a stroke is, takes it.
     */
    private fun fitArc(rx: FloatArray, ry: FloatArray, n: Int, diagonal: Float): ShapeSpec.Arc? {
        var mx = 0f
        var my = 0f
        for (i in 0 until n) { mx += rx[i]; my += ry[i] }
        mx /= n
        my /= n

        // Centred first: the normal equations below drop their linear terms once the samples have
        // zero mean, and a page-coordinate fit would otherwise square four-digit numbers.
        var suu = 0f; var svv = 0f; var suv = 0f
        var suuu = 0f; var svvv = 0f; var suvv = 0f; var suuv = 0f
        for (i in 0 until n) {
            val u = rx[i] - mx
            val v = ry[i] - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v
            suvv += u * v * v; suuv += u * u * v
        }
        val det = suu * svv - suv * suv
        if (abs(det) < EPSILON) return null
        val h0 = 0.5f * (suuu + suvv)
        val h1 = 0.5f * (svvv + suuv)
        val a = (h0 * svv - h1 * suv) / det
        val b = (h1 * suu - h0 * suv) / det
        val cx = mx + a
        val cy = my + b
        val radius = sqrt(a * a + b * b + (suu + svv) / n)
        // A radius far larger than the drawing is the fit saying "this is straight" in the only
        // vocabulary it has.
        if (radius < MIN_RADIUS || radius > MAX_ARC_RADIUS * diagonal) return null

        var sweep = 0f
        var previous = atan2(ry[0] - cy, rx[0] - cx)
        for (i in 1 until n) {
            val angle = atan2(ry[i] - cy, rx[i] - cx)
            sweep += angleDelta(angle, previous)
            previous = angle
        }
        if (abs(sweep) < MIN_ARC_SWEEP || abs(sweep) > MAX_ARC_SWEEP) return null
        return ShapeSpec.Arc(cx, cy, radius, atan2(ry[0] - cy, rx[0] - cx), sweep)
    }

    /** Principal axes from the sample covariance, then the extents along them. */
    private fun fitEllipse(rx: FloatArray, ry: FloatArray, n: Int): ShapeSpec.Ellipse {
        var mx = 0f
        var my = 0f
        for (i in 0 until n) { mx += rx[i]; my += ry[i] }
        mx /= n
        my /= n

        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (i in 0 until n) {
            val dx = rx[i] - mx
            val dy = ry[i] - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val rot = 0.5f * atan2(2f * sxy, sxx - syy)
        val out = FloatArray(4)
        rectAt(rx, ry, n, rot, out)
        return ShapeSpec.Ellipse(out[0], out[1], out[2], out[3], rot)
    }

    /**
     * Fits a regular polygon of [sides] vertices in closed form.
     *
     * A regular polygon has polar radius `R·cos(π/k)/cos(α mod 2π/k − π/k)` about its centre, so
     * every sample yields an estimate of the circumradius once a rotation is assumed. The rotation
     * that is right is the one making those estimates agree, which turns a two-parameter fit into a
     * one-dimensional sweep over a single period. The radius is then the *median* estimate, so one
     * badly drawn edge cannot drag the whole polygon outwards.
     */
    private fun fitNgon(rx: FloatArray, ry: FloatArray, n: Int, sides: Int): ShapeSpec.Ngon {
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) { cx += rx[i]; cy += ry[i] }
        cx /= n
        cy /= n

        val period = TWO_PI / sides
        val half = period * 0.5f
        val apothemRatio = cos(half)
        val radii = FloatArray(n)
        val angles = FloatArray(n)
        for (i in 0 until n) {
            radii[i] = hypot(rx[i] - cx, ry[i] - cy)
            angles[i] = atan2(ry[i] - cy, rx[i] - cx)
        }

        val estimates = FloatArray(n)
        var bestRot = 0f
        var bestSpread = Float.MAX_VALUE
        var step = 0
        while (step < NGON_SWEEP_STEPS) {
            val phi = step * period / NGON_SWEEP_STEPS
            val spread = ngonSpread(radii, angles, n, phi, period, half, apothemRatio, estimates)
            if (spread < bestSpread) {
                bestSpread = spread
                bestRot = phi
            }
            step++
        }
        val span = period / NGON_SWEEP_STEPS
        var refine = -NGON_REFINE_STEPS
        while (refine <= NGON_REFINE_STEPS) {
            val phi = bestRot + span * refine / NGON_REFINE_STEPS
            val spread = ngonSpread(radii, angles, n, phi, period, half, apothemRatio, estimates)
            if (spread < bestSpread) {
                bestSpread = spread
                bestRot = phi
            }
            refine++
        }

        ngonSpread(radii, angles, n, bestRot, period, half, apothemRatio, estimates)
        estimates.sort()
        val radius = estimates[n / 2]
        return ShapeSpec.Ngon(cx, cy, max(radius, MIN_RADIUS), bestRot, sides)
    }

    /** Circumradius estimates for rotation [phi], returning how much they disagree. */
    @Suppress("LongParameterList")
    private fun ngonSpread(
        radii: FloatArray,
        angles: FloatArray,
        n: Int,
        phi: Float,
        period: Float,
        half: Float,
        apothemRatio: Float,
        into: FloatArray,
    ): Float {
        var sum = 0f
        var sumSq = 0f
        for (i in 0 until n) {
            // Offset from the nearest edge perpendicular, which is what the polar model above is
            // written about. Measuring from a vertex instead leaves the fitted polygon rotated by
            // half a period — a 60 degree error on a triangle, which reads as a different shape.
            var a = (angles[i] - phi) % period
            if (a < 0f) a += period
            a -= half
            val r = radii[i] * cos(a) / apothemRatio
            into[i] = r
            sum += r
            sumSq += r * r
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /**
     * A polygon through the corners the user actually drew, with each vertex placed where its two
     * edges meet rather than where the pen rounded the turn.
     *
     * Returns null unless the corners are unambiguous: only genuinely sharp turns count, so a
     * circle proposes nothing here and an octagon — whose 45° turns are gentler than the threshold
     * — is left to the regular fit, which is the better answer for it anyway.
     */
    private fun fitPoly(rx: FloatArray, ry: FloatArray, n: Int): ShapeSpec.Poly? {
        val corners = detectCorners(rx, ry, n) ?: return null
        val k = corners.size

        // Fit a line to each edge, trimming the ends so the rounded corner does not bend it.
        val px = FloatArray(k)
        val py = FloatArray(k)
        val dx = FloatArray(k)
        val dy = FloatArray(k)
        for (e in 0 until k) {
            val from = corners[e]
            val to = corners[(e + 1) % k]
            val steps = cyclicSteps(from, to, n)
            if (steps < MIN_EDGE_SAMPLES) return null
            val trim = max(1, (steps * EDGE_TRIM).roundToInt())
            if (!fitLine(rx, ry, n, from + trim, steps - 2 * trim, e, px, py, dx, dy)) return null
        }

        val vx = FloatArray(k)
        val vy = FloatArray(k)
        for (v in 0 until k) {
            val a = (v - 1 + k) % k
            val cross = dx[a] * dy[v] - dy[a] * dx[v]
            if (abs(cross) < PARALLEL_EPSILON) {
                // Two edges that never meet mean the corner was spurious; trust the sample.
                vx[v] = rx[corners[v]]
                vy[v] = ry[corners[v]]
            } else {
                val t = ((px[v] - px[a]) * dy[v] - (py[v] - py[a]) * dx[v]) / cross
                vx[v] = px[a] + t * dx[a]
                vy[v] = py[a] + t * dy[a]
            }
        }
        return ShapeSpec.Poly(vx, vy)
    }

    /** Least-squares direction through [length] samples from [start], written to slot [slot]. */
    @Suppress("LongParameterList")
    private fun fitLine(
        rx: FloatArray, ry: FloatArray, n: Int,
        start: Int, length: Int, slot: Int,
        px: FloatArray, py: FloatArray, dx: FloatArray, dy: FloatArray,
    ): Boolean {
        if (length < 2) return false
        var mx = 0f
        var my = 0f
        for (i in 0 until length) {
            val j = (start + i) % n
            mx += rx[j]
            my += ry[j]
        }
        mx /= length
        my /= length
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (i in 0 until length) {
            val j = (start + i) % n
            val ax = rx[j] - mx
            val ay = ry[j] - my
            sxx += ax * ax
            syy += ay * ay
            sxy += ax * ay
        }
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        px[slot] = mx
        py[slot] = my
        dx[slot] = cos(theta)
        dy[slot] = sin(theta)
        return true
    }

    /**
     * Indices of sharp turns, or null when there are too few or too many to describe a polygon.
     *
     * The threshold is set well above the turn a circle shows at this sampling density — a 64-point
     * circle turns about 34° over the same window — so a smooth curve can never fabricate corners.
     */
    private fun detectCorners(rx: FloatArray, ry: FloatArray, n: Int): IntArray? {
        val turn = FloatArray(n)
        for (i in 0 until n) {
            val a = (i - CORNER_SPAN + n) % n
            val b = (i + CORNER_SPAN) % n
            val v0x = rx[i] - rx[a]
            val v0y = ry[i] - ry[a]
            val v1x = rx[b] - rx[i]
            val v1y = ry[b] - ry[i]
            val l0 = hypot(v0x, v0y)
            val l1 = hypot(v1x, v1y)
            turn[i] = if (l0 < EPSILON || l1 < EPSILON) {
                0f
            } else {
                acos(((v0x * v1x + v0y * v1y) / (l0 * l1)).coerceIn(-1f, 1f))
            }
        }

        // Strongest first, suppressing neighbours, so a corner smeared over several samples counts
        // once rather than as a cluster of near-identical vertices.
        val order = (0 until n).sortedByDescending { turn[it] }
        val accepted = ArrayList<Int>(MAX_POLY_VERTICES)
        for (i in order) {
            if (turn[i] < CORNER_THRESHOLD) break
            if (accepted.any { cyclicSteps(it, i, n) < NMS_WINDOW || cyclicSteps(i, it, n) < NMS_WINDOW }) continue
            accepted += i
            if (accepted.size > MAX_POLY_VERTICES) return null
        }
        if (accepted.size < 3) return null
        accepted.sort()
        return accepted.toIntArray()
    }

    // ---- Regularisation --------------------------------------------------------------------

    /**
     * The last few degrees of polish, and most of what separates a result that looks deliberate
     * from one that is merely close.
     *
     * Two rules, applied to every kind of shape:
     *
     * - **Near enough to perfect is perfect.** A rectangle whose sides differ by a few per cent was
     *   a square; a polygon whose sides and corners are nearly equal was a regular one. People
     *   cannot draw those accurately and do not expect to have to.
     * - **Near enough to level is level.** An angle within [ANGLE_SNAP] of an eighth turn — 0°, 45°,
     *   90°, and so on — is set to it exactly. A box six degrees out does not read as a box drawn
     *   at six degrees; it reads as a box drawn badly.
     *
     * The tolerances are deliberately generous. Being wrong here costs the user one adjustment they
     * can make immediately, with the pen still down; being too timid costs them a shape that is
     * subtly crooked in a way they will only notice later.
     */
    private fun regularise(spec: ShapeSpec): ShapeSpec = when (spec) {
        is ShapeSpec.Line -> {
            val angle = atan2(spec.y1 - spec.y0, spec.x1 - spec.x0)
            val snapped = snapToEighth(angle)
            if (snapped == angle) spec else {
                // Rotated about the midpoint so the line stays where it was drawn.
                val mx = (spec.x0 + spec.x1) * 0.5f
                val my = (spec.y0 + spec.y1) * 0.5f
                val half = hypot(spec.x1 - spec.x0, spec.y1 - spec.y0) * 0.5f
                val ux = cos(snapped) * half
                val uy = sin(snapped) * half
                ShapeSpec.Line(mx - ux, my - uy, mx + ux, my + uy)
            }
        }

        is ShapeSpec.Arc -> arc(spec)

        is ShapeSpec.Rect -> rectangle(spec.cx, spec.cy, spec.hw, spec.hh, spec.rot)

        // A regular quadrilateral is a square, and the four-sided regular fit is the better
        // *measurement* of one — its median radius shrugs off the wobble that drags a bounding box
        // outwards. It is the wrong *description*, though: a square wants a rectangle's handles, so
        // that dragging a corner can pull it into an oblong. Fit as one, describe as the other.
        is ShapeSpec.Ngon ->
            if (spec.sides == 4) {
                val half = spec.r * ROOT_HALF
                rectangle(spec.cx, spec.cy, half, half, spec.rot - EIGHTH_TURN)
            } else {
                ShapeSpec.Ngon(spec.cx, spec.cy, spec.r, snapRegular(spec.rot, spec.sides), spec.sides)
            }

        is ShapeSpec.Ellipse -> ellipse(spec.cx, spec.cy, spec.rx, spec.ry, spec.rot)

        is ShapeSpec.Poly -> perfect(spec)
    }

    /**
     * Straightens an arc that was meant to be a line, and squares up one that was not.
     *
     * ### Why straightness is decided here and not by the score
     *
     * A line and an arc are not two shapes competing on how well each matches; a line *is* an arc,
     * of no curvature. Left to the score the two are separated by whichever fits the wobble in the
     * drawing by a hair, which is a coin toss on a stroke a person meant to be straight. Judging
     * the winner's bow instead — the crown's distance from the chord, as a fraction of the chord —
     * makes the rule one a user can feel: draw it near enough straight and it comes out straight,
     * with the same generous tolerance the angles get, and bow it deliberately and it stays bowed.
     *
     * What remains is the arc's own version of levelling. Its sweep is snapped to a quarter turn,
     * so the quarter circles and half circles people actually draw come out exactly, and its chord
     * is then snapped to an eighth turn the way a line's direction is, which stands the whole bow
     * upright without touching how deep it is.
     */
    private fun arc(spec: ShapeSpec.Arc): ShapeSpec {
        val ends = FloatArray(2)
        spec.handleInto(0, ends)
        val x0 = ends[0]
        val y0 = ends[1]
        spec.handleInto(2, ends)
        val x1 = ends[0]
        val y1 = ends[1]
        val chord = hypot(x1 - x0, y1 - y0)
        if (chord < EPSILON) return spec

        spec.handleInto(1, ends)
        val bow = sagittaOf(x0, y0, x1, y1, ends[0], ends[1])
        if (abs(bow) <= STRAIGHT_BOW * chord) return regularise(ShapeSpec.Line(x0, y0, x1, y1))

        // Sagitta and sweep are interchangeable descriptions of the same bow — s = c·tan(θ/4)/2 —
        // so the snapped sweep is applied by rewriting the depth, leaving the ends where the pen
        // put them.
        val sweep = snapSweep(abs(spec.sweep))
        val depth = chord * tan(sweep / 4f) * 0.5f * if (bow < 0f) -1f else 1f

        val angle = atan2(y1 - y0, x1 - x0)
        val correction = snapToEighth(angle) - angle
        val mx = (x0 + x1) * 0.5f
        val my = (y0 + y1) * 0.5f
        val c = cos(correction)
        val sn = sin(correction)
        return arcFromChord(
            mx + (x0 - mx) * c - (y0 - my) * sn, my + (x0 - mx) * sn + (y0 - my) * c,
            mx + (x1 - mx) * c - (y1 - my) * sn, my + (x1 - mx) * sn + (y1 - my) * c,
            depth,
        )
    }

    /** A sweep set to the nearest quarter turn when it is close enough, in 0..2pi. */
    private fun snapSweep(sweep: Float): Float {
        val snapped = (sweep / QUARTER_TURN).roundToInt() * QUARTER_TURN
        return if (snapped > 0f && abs(sweep - snapped) <= ANGLE_SNAP) snapped else sweep
    }

    /** An angle set to the nearest eighth turn when it is close enough, or left exactly as it was. */
    private fun snapToEighth(angle: Float): Float {
        val snapped = (angle / EIGHTH_TURN).roundToInt() * EIGHTH_TURN
        return if (abs(angleDelta(angle, snapped)) <= ANGLE_SNAP) snapped else angle
    }

    /**
     * Normalises a rectangle's angle and decides whether it is square.
     *
     * A rectangle rotated by a quarter turn is the same rectangle with its sides swapped, so the
     * angle only carries information within ±45°. Snapping happens before that fold, because the
     * orientations worth snapping to include the diagonal ones: a square drawn at 43° is a diamond,
     * and should come out as an exact one.
     */
    private fun rectangle(cx: Float, cy: Float, halfW: Float, halfH: Float, rotation: Float): ShapeSpec.Rect {
        var rot = snapToEighth(rotation)
        var hw = halfW
        var hh = halfH
        while (rot >= EIGHTH_TURN) { rot -= QUARTER_TURN; val t = hw; hw = hh; hh = t }
        while (rot < -EIGHTH_TURN) { rot += QUARTER_TURN; val t = hw; hw = hh; hh = t }
        return if (abs(hw - hh) <= EQUAL_TOLERANCE * max(hw, hh)) {
            val side = (hw + hh) * 0.5f
            ShapeSpec.Rect(cx, cy, side, side, rot, equilateral = true)
        } else {
            ShapeSpec.Rect(cx, cy, hw, hh, rot, equilateral = false)
        }
    }

    /** The ellipse counterpart of [rectangle]: same fold, same snap, same near-enough rule. */
    private fun ellipse(cx: Float, cy: Float, radiusX: Float, radiusY: Float, rotation: Float): ShapeSpec.Ellipse {
        var rot = snapToEighth(rotation)
        var rx = radiusX
        var ry = radiusY
        while (rot >= EIGHTH_TURN) { rot -= QUARTER_TURN; val t = rx; rx = ry; ry = t }
        while (rot < -EIGHTH_TURN) { rot += QUARTER_TURN; val t = rx; rx = ry; ry = t }
        return if (abs(rx - ry) <= EQUAL_TOLERANCE * max(rx, ry)) {
            val radius = (rx + ry) * 0.5f
            // A circle has no orientation; zeroing it keeps later handle maths uncomplicated.
            ShapeSpec.Ellipse(cx, cy, radius, radius, 0f, equilateral = true)
        } else {
            ShapeSpec.Ellipse(cx, cy, rx, ry, rot, equilateral = false)
        }
    }

    /**
     * The rotation of a regular polygon, snapped to the nearest orientation that stands up straight.
     *
     * Unlike a rectangle, an n-gon has no meaningful relationship to the eighth turns: a pentagon
     * repeats every 72°, so demanding a multiple of 45° would ask it to be something it cannot be.
     * What it *can* be is symmetric about the vertical — apex up, or flat side down — which happens
     * whenever a vertex or an edge midpoint points straight up, every half period. The tolerance is
     * capped at a third of that period so a many-sided polygon, whose orientations are close
     * together anyway, is not snapped almost wherever it was drawn.
     */
    private fun snapRegular(rot: Float, sides: Int): Float {
        val step = PI / sides
        val tolerance = min(ANGLE_SNAP, step / 3f)
        val snapped = ((rot + QUARTER_TURN) / step).roundToInt() * step - QUARTER_TURN
        return if (abs(angleDelta(rot, snapped)) <= tolerance) snapped else rot
    }

    /**
     * Promotes a free polygon to the perfect shape it was nearly drawn as, or tidies its edges.
     *
     * The recogniser already competes a regular polygon and a rectangle against the free fit, but
     * that contest is decided by *distance*, which is unforgiving: a triangle whose sides differ by
     * a tenth loses to no shape in particular and stays as drawn. Judging it again by the two
     * measurements a person would actually use — are the sides the same length, are the corners the
     * same angle — catches the ones that were meant to be regular and were merely drawn by hand.
     */
    private fun perfect(poly: ShapeSpec.Poly): ShapeSpec {
        val n = poly.vertexCount
        if (n < 3) return poly

        val sides = FloatArray(n)
        val corners = FloatArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            sides[i] = hypot(poly.xs[j] - poly.xs[i], poly.ys[j] - poly.ys[i])
        }
        for (i in 0 until n) {
            val p = (i - 1 + n) % n
            val q = (i + 1) % n
            corners[i] = turnAt(
                poly.xs[i] - poly.xs[p], poly.ys[i] - poly.ys[p],
                poly.xs[q] - poly.xs[i], poly.ys[q] - poly.ys[i],
            )
        }

        val shortest = sides.min()
        val longest = sides.max()
        if (shortest <= EPSILON) return poly
        val evenSides = longest / shortest - 1f <= SIDE_TOLERANCE
        val evenCorners = corners.all { abs(it - TWO_PI / n) <= CORNER_TOLERANCE }
        if (evenSides && evenCorners) {
            // A regular quadrilateral is a square, and squares are described as rectangles.
            return if (n == 4) rectangleFrom(poly) else regularise(regularFrom(poly))
        }

        // Not regular, but four right angles still make a rectangle — an oblong drawn by hand.
        if (n == 4 && corners.all { abs(it - QUARTER_TURN) <= CORNER_TOLERANCE }) return rectangleFrom(poly)

        return levelled(poly)
    }

    /** The exterior turn between two consecutive edge vectors, in 0..π. */
    private fun turnAt(inX: Float, inY: Float, outX: Float, outY: Float): Float {
        val l0 = hypot(inX, inY)
        val l1 = hypot(outX, outY)
        if (l0 < EPSILON || l1 < EPSILON) return 0f
        return acos(((inX * outX + inY * outY) / (l0 * l1)).coerceIn(-1f, 1f))
    }

    /** The regular polygon nearest a free one: mean centre, mean radius, circular-mean rotation. */
    private fun regularFrom(poly: ShapeSpec.Poly): ShapeSpec.Ngon {
        val n = poly.vertexCount
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) { cx += poly.xs[i]; cy += poly.ys[i] }
        cx /= n
        cy /= n

        val period = TWO_PI / n
        var radius = 0f
        var sumSin = 0f
        var sumCos = 0f
        for (i in 0 until n) {
            val dx = poly.xs[i] - cx
            val dy = poly.ys[i] - cy
            radius += hypot(dx, dy)
            // Each vertex votes for the same rotation once its own share of the turn is removed;
            // averaging on the unit circle rather than numerically is what makes the vote immune
            // to where the wrap-around happens to fall.
            val vote = (atan2(dy, dx) - i * period) * n
            sumSin += sin(vote)
            sumCos += cos(vote)
        }
        return ShapeSpec.Ngon(cx, cy, max(radius / n, MIN_RADIUS), atan2(sumSin, sumCos) / n, n)
    }

    /** The rectangle nearest a quadrilateral: adjacent edges are perpendicular, so all four vote. */
    private fun rectangleFrom(poly: ShapeSpec.Poly): ShapeSpec.Rect {
        var sumSin = 0f
        var sumCos = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val vote = atan2(poly.ys[j] - poly.ys[i], poly.xs[j] - poly.xs[i]) * 4f
            sumSin += sin(vote)
            sumCos += cos(vote)
        }
        val rot = atan2(sumSin, sumCos) / 4f

        val c = cos(rot)
        val s = sin(rot)
        val us = FloatArray(4)
        val vs = FloatArray(4)
        var cu = 0f
        var cv = 0f
        for (i in 0 until 4) {
            us[i] = poly.xs[i] * c + poly.ys[i] * s
            vs[i] = -poly.xs[i] * s + poly.ys[i] * c
            cu += us[i]
            cv += vs[i]
        }
        cu /= 4
        cv /= 4
        var hw = 0f
        var hh = 0f
        for (i in 0 until 4) {
            hw += abs(us[i] - cu)
            hh += abs(vs[i] - cv)
        }
        return rectangle(cu * c - cv * s, cu * s + cv * c, max(hw / 4, MIN_RADIUS), max(hh / 4, MIN_RADIUS), rot)
    }

    /**
     * Levels a polygon that is not regular — a right triangle's upright, a trapezium's base.
     *
     * ### Why this rotates the whole shape rather than snapping each edge
     *
     * Snapping edges one at a time and re-cutting the corners where they now meet is the obvious
     * approach and it silently changes what the user drew. Take a right triangle with legs of 300
     * and 220, already perfectly square to the page: its hypotenuse lies at 144°, within reach of
     * 135°, so snapping edges independently pulls it there and drags both legs to 260 — an upright
     * triangle deformed into an isosceles one that was never asked for. The tilt was already
     * correct; only the proportions moved.
     *
     * A figure's tilt is one number, so correcting it is one rigid rotation, which cannot change a
     * length or an angle. The only question is what the tilt *is*, and a polygon only has one if
     * enough of it agrees: the orientation is taken from the edges that concur to within a snap,
     * weighted by length, and rejected unless they account for most of the perimeter. A trapezium's
     * two parallel sides carry it; an equilateral triangle's three mutually-60° edges do not, and
     * it is left alone — correctly, since its regular form was already handled above.
     */
    private fun levelled(poly: ShapeSpec.Poly): ShapeSpec.Poly {
        val n = poly.vertexCount
        val angles = FloatArray(n)
        val weights = FloatArray(n)
        var perimeter = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = poly.xs[j] - poly.xs[i]
            val ey = poly.ys[j] - poly.ys[i]
            weights[i] = hypot(ex, ey)
            perimeter += weights[i]
            // Folded into a quarter turn: an edge and the one across from it point opposite ways
            // but describe the same orientation, and so does one at right angles to both.
            var a = atan2(ey, ex) % QUARTER_TURN
            if (a < 0f) a += QUARTER_TURN
            angles[i] = a
        }
        if (perimeter <= EPSILON) return poly

        var bestSupport = 0f
        var tilt = 0f
        for (candidate in 0 until n) {
            var support = 0f
            var sumSin = 0f
            var sumCos = 0f
            for (i in 0 until n) {
                if (abs(quarterDelta(angles[i], angles[candidate])) > ANGLE_SNAP) continue
                support += weights[i]
                val vote = angles[i] * 4f
                sumSin += weights[i] * sin(vote)
                sumCos += weights[i] * cos(vote)
            }
            if (support > bestSupport) {
                bestSupport = support
                tilt = atan2(sumSin, sumCos) / 4f
            }
        }
        if (bestSupport < TILT_SUPPORT * perimeter) return poly

        val correction = snapToEighth(tilt) - tilt
        if (correction == 0f) return poly

        var cx = 0f
        var cy = 0f
        for (i in 0 until n) { cx += poly.xs[i]; cy += poly.ys[i] }
        cx /= n
        cy /= n
        val c = cos(correction)
        val sn = sin(correction)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            val dx = poly.xs[i] - cx
            val dy = poly.ys[i] - cy
            nx[i] = cx + dx * c - dy * sn
            ny[i] = cy + dx * sn + dy * c
        }
        return ShapeSpec.Poly(nx, ny)
    }

    /** The difference between two orientations, folded into the ±45° they are unique within. */
    private fun quarterDelta(a: Float, b: Float): Float {
        var d = (a - b) % QUARTER_TURN
        if (d > EIGHTH_TURN) d -= QUARTER_TURN
        if (d < -EIGHTH_TURN) d += QUARTER_TURN
        return d
    }

    // ---- Shared helpers --------------------------------------------------------------------

    private fun polylineLength(xs: FloatArray, ys: FloatArray, count: Int): Float {
        var total = 0f
        for (i in 1 until count) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return total
    }

    /** Rewrites the path as [rx]/[ry] evenly spaced by arc length. False if it has no length. */
    private fun resample(
        xs: FloatArray, ys: FloatArray, count: Int, pathLength: Float,
        rx: FloatArray, ry: FloatArray,
    ): Boolean {
        val n = rx.size
        if (pathLength <= EPSILON) return false
        val step = pathLength / (n - 1)
        rx[0] = xs[0]
        ry[0] = ys[0]
        var out = 1
        var travelled = 0f
        var i = 1
        var prevX = xs[0]
        var prevY = ys[0]
        while (i < count && out < n - 1) {
            val segment = hypot(xs[i] - prevX, ys[i] - prevY)
            if (segment <= EPSILON) {
                i++
                prevX = xs[i - 1]
                prevY = ys[i - 1]
                continue
            }
            val next = travelled + segment
            val target = out * step
            if (next >= target) {
                val t = (target - travelled) / segment
                prevX += (xs[i] - prevX) * t
                prevY += (ys[i] - prevY) * t
                rx[out] = prevX
                ry[out] = prevY
                out++
                travelled = target
            } else {
                travelled = next
                prevX = xs[i]
                prevY = ys[i]
                i++
            }
        }
        // Rounding can leave the tail unfilled; clamp it to the real endpoint rather than zero.
        while (out < n) {
            rx[out] = xs[count - 1]
            ry[out] = ys[count - 1]
            out++
        }
        return true
    }

    /** Steps forward from [from] to [to] around a cycle of [n]. */
    private fun cyclicSteps(from: Int, to: Int, n: Int): Int = (to - from + n) % n

    /** The signed difference between two angles, wrapped into ±π. */
    private fun angleDelta(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= TWO_PI
        while (d < -PI) d += TWO_PI
        return d
    }

    // ---- Tuning ------------------------------------------------------------------------------

    private const val PI = kotlin.math.PI.toFloat()
    private const val QUARTER_TURN = PI / 2f
    private const val EIGHTH_TURN = PI / 4f
    private const val EPSILON = 1e-6f
    /** Half the diagonal of a unit square: a regular quadrilateral's circumradius to half-side. */
    private val ROOT_HALF = sqrt(0.5f)

    /** Fewer samples than this is a tick, not a shape. */
    private const val MIN_SAMPLES = 8
    private const val MIN_PATH_LENGTH = 24f
    private const val MIN_DIAGONAL = 12f
    private const val MIN_RADIUS = 0.5f

    /**
     * A circle this many times the drawing's own diagonal across is indistinguishable from a line
     * over the piece of it that was drawn, and fitting one is arithmetic looking for precision the
     * samples do not carry.
     */
    private const val MAX_ARC_RADIUS = 12f
    private const val MIN_ARC_SWEEP = 8f * PI / 180f
    /** Nearly the whole way round; past this the stroke is a circle, and is fitted as one. */
    private const val MAX_ARC_SWEEP = 1.8f * PI
    /**
     * How far an arc may bow, relative to its chord, and still be read as a line.
     *
     * A twenty-fifth of the chord is a 300 pt stroke wandering 12 pt off true, which is a wobble
     * rather than a curve. In sweep it is about 18 degrees, so the deliberate curves this leaves
     * alone start comfortably above what an unsteady hand produces trying to draw straight.
     */
    private const val STRAIGHT_BOW = 0.04f

    private const val RESAMPLE_COUNT = 64
    private const val BACKWARD_SAMPLES = 48
    private const val SCORE_ELLIPSE_STEPS = 48

    /** Beyond this fraction of the path length, the ends are too far apart to call it closed. */
    private const val CLOSE_FRACTION = 0.25f

    /**
     * Stopping at the octagon is a reliability decision, not a limitation of the fit.
     *
     * Past eight sides the regular polygons converge on the circle faster than an unsteady hand
     * diverges from it, so admitting a nine- or twelve-sided candidate does not let anyone draw a
     * nonagon — it only gives the wobble in a hand-drawn circle somewhere to win. Measured over
     * twenty seeds at a 3% wobble, every circle that came out as a polygon came out as a nine-,
     * ten- or twelve-gon; none was mistaken for anything with eight sides or fewer.
     */
    private const val MAX_NGON_SIDES = 8
    private const val RECT_SWEEP_STEPS = 90
    private const val RECT_REFINE_STEPS = 20
    private const val NGON_SWEEP_STEPS = 48
    private const val NGON_REFINE_STEPS = 16

    private const val CORNER_SPAN = 3
    /** Comfortably above the ~34° a 64-sample circle shows across the same window. */
    private const val CORNER_THRESHOLD = 55f * PI / 180f
    private const val NMS_WINDOW = 6
    private const val MAX_POLY_VERTICES = 6
    private const val MIN_EDGE_SAMPLES = 5
    private const val EDGE_TRIM = 0.2f
    private const val PARALLEL_EPSILON = 1e-4f

    /**
     * Priced near the free-form rate rather than the regular one, because an arc is fitted *to* the
     * drawing the way a free polygon is: it competes only against the line, and its one extra
     * degree of freedom lets it match a shaky hand's wander slightly better than a line every time.
     * Charging for that freedom is what keeps a stroke meant to be straight from arriving bent.
     */
    private const val ARC_PENALTY = 0.25f
    private const val REGULAR_VERTEX_PENALTY = 0.05f
    private const val FREE_VERTEX_PENALTY = 0.30f
    /**
     * Keeps a near-perfect fit from making every penalty vanish along with the error.
     *
     * Small on purpose. Raising it flattens the ratio between two good fits, which is precisely the
     * comparison that decides an octagon from a circle; the wobbly cases it would protect are
     * already governed by the penalties, whose effect does not depend on it.
     */
    private const val ERROR_FLOOR = 0.001f
    /** Above this the candidate is not a description of the stroke, however simple it is. */
    private const val MAX_MEAN_ERROR = 0.05f
    /** One point this far off is a mismatch no average should be allowed to hide. */
    private const val MAX_POINT_ERROR = 0.15f
    /** Drawn length against the candidate's own perimeter; outside this the pen did something else. */
    private const val MIN_LENGTH_RATIO = 0.70f
    private const val MAX_LENGTH_RATIO = 1.45f

    /** Sides within this of each other were meant to be the same length. */
    private const val EQUAL_TOLERANCE = 0.18f
    private const val SIDE_TOLERANCE = 0.15f
    /** Corners within this of the regular angle were meant to be the regular angle. */
    private const val CORNER_TOLERANCE = 12f * PI / 180f
    /** How far off an eighth turn an angle may be and still be treated as meant to be one. */
    private const val ANGLE_SNAP = 10f * PI / 180f
    /** A polygon has an orientation only if this much of its perimeter agrees on one. */
    private const val TILT_SUPPORT = 0.5f
}
