package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import pl.dakil.notes.model.ShapeSpec
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The reliability contract for auto-shape.
 *
 * Strokes here are synthesised the way a hand actually produces them — an ideal outline walked at
 * even speed, displaced by *smoothed* noise rather than per-sample hash, and usually left slightly
 * unclosed — because per-sample white noise is both harsher and less like a person than the real
 * thing, and would tune the recogniser for a problem it does not have.
 */
class ShapeRecognizerTest {

    // ---- Squares and rectangles ------------------------------------------------------------

    @Test
    fun `a hand-drawn square is recognised as a square rather than a rectangle`() {
        val drawn = handDrawn(square(200f), seed = 1)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertTrue("should be marked equilateral", shape.equilateral)
        assertEquals(shape.hw, shape.hh, 1e-3f)
        assertEquals(200f, shape.hw * 2f, 12f)
    }

    @Test
    fun `a square drawn a couple of degrees off level comes out level`() {
        // The snap is most of what makes the result look deliberate: people cannot draw level, and
        // a box that is visibly 2 degrees out reads as a mistake rather than a shape.
        val drawn = handDrawn(square(200f, rot = 2f.toRadians()), seed = 2)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertEquals(0f, shape.rot, 1e-6f)
    }

    @Test
    fun `a deliberately tilted square keeps its tilt`() {
        val drawn = handDrawn(square(200f, rot = 30f.toRadians()), seed = 3)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertTrue(shape.equilateral)
        // A square is symmetric every quarter turn, so 30 and -60 describe the same shape.
        assertEquals(30f, normaliseQuarter(shape.rot).toDegrees(), 3f)
    }

    @Test
    fun `an oblong is not rounded up to a square`() {
        val drawn = handDrawn(rect(240f, 120f), seed = 4)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertTrue("2:1 is nowhere near square", !shape.equilateral)
        assertEquals(2f, max(shape.hw, shape.hh) / min(shape.hw, shape.hh), 0.15f)
    }

    @Test
    fun `a diamond is a rotated square, not a four-sided polygon`() {
        val drawn = handDrawn(square(200f, rot = 45f.toRadians()), seed = 5)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count)
        assertTrue("got $shape", shape is ShapeSpec.Rect && shape.equilateral)
    }

    // ---- Circles and ellipses --------------------------------------------------------------

    @Test
    fun `a hand-drawn circle is a circle and not a many-sided polygon`() {
        // The failure this guards against is the tempting one: a twelve-gon fitted to a wobbly
        // circle matches the drawing more closely than the circle does.
        val drawn = handDrawn(circle(120f), seed = 6)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Ellipse
        assertTrue(shape.equilateral)
        assertEquals(120f, shape.rx, 8f)
    }

    @Test
    fun `a tilted ellipse keeps both its axes and its angle`() {
        val drawn = handDrawn(ellipse(160f, 80f, rot = 25f.toRadians()), seed = 7)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Ellipse
        assertTrue(!shape.equilateral)
        assertEquals(2f, max(shape.rx, shape.ry) / min(shape.rx, shape.ry), 0.2f)
        assertEquals(25f, normaliseQuarter(shape.rot).toDegrees(), 5f)
    }

    // ---- Polygons --------------------------------------------------------------------------

    @Test
    fun `an equilateral triangle is fitted as a regular polygon`() {
        val drawn = handDrawn(ngon(3, 120f), seed = 8)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Ngon
        assertEquals(3, shape.sides)
    }

    @Test
    fun `a scalene triangle keeps the shape it was drawn as`() {
        // Regularising this into an equilateral triangle would be the recogniser overruling the
        // user, so the free-polygon fit has to win despite its heavier penalty.
        val drawn = handDrawn(poly(0f to 0f, 260f to 30f, 90f to 190f), seed = 9)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Poly
        assertEquals(3, shape.vertexCount)
    }

    @Test
    fun `every regular polygon from a pentagon to an octagon is identified by its side count`() {
        for (sides in 3..8) {
            val drawn = handDrawn(ngon(sides, 130f, rot = 0.4f), seed = 100 + sides)
            val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count)
            val kind = if (sides == 4) "square" else "$sides-gon"
            when (sides) {
                // A regular quadrilateral is a square, and the rectangle fit describes it better —
                // it is the one that can then be dragged into an oblong.
                4 -> assertTrue("$kind came out as $shape", shape is ShapeSpec.Rect && shape.equilateral)
                else -> assertEquals(kind, sides, (shape as? ShapeSpec.Ngon)?.sides)
            }
        }
    }

    @Test
    fun `a trapezium is kept as a free polygon`() {
        val drawn = handDrawn(poly(0f to 0f, 240f to 0f, 200f to 150f, 40f to 150f), seed = 11)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Poly
        assertEquals(4, shape.vertexCount)
    }

    // ---- Lines -----------------------------------------------------------------------------

    @Test
    fun `a stroke drawn straight becomes a line`() {
        val drawn = handDrawn(ShapeSpec.Line(40f, 60f, 300f, 210f), seed = 12)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count)
        assertTrue("got $shape", shape is ShapeSpec.Line)
    }

    @Test
    fun `a nearly vertical line is snapped to vertical`() {
        val drawn = handDrawn(ShapeSpec.Line(100f, 40f, 106f, 300f), seed = 13)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Line
        assertEquals("should be dead vertical", shape.x0, shape.x1, 1e-3f)
    }

    @Test
    fun `a line at no particular angle is left at that angle`() {
        val drawn = handDrawn(ShapeSpec.Line(40f, 40f, 300f, 150f), seed = 14)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Line
        val angle = kotlin.math.atan2(shape.y1 - shape.y0, shape.x1 - shape.x0).toDegrees()
        assertEquals(22.9f, angle, 4f)
    }

    // ---- Curves ----------------------------------------------------------------------------

    @Test
    fun `a bowed stroke becomes an arc rather than a straight line`() {
        val drawn = handDrawn(arc(130f, 60f), endFraction = 1f, seed = 30)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Arc
        assertEquals(60f, abs(shape.sweep).toDegrees(), 6f)
        assertEquals(130f, shape.r, 12f)
    }

    @Test
    fun `a stroke bowed only as much as an unsteady hand bows one is still a line`() {
        // The other half of the contract, and the one that protects the feature people already
        // use: nobody draws a straight line straight, and a few points of sag must not be read as
        // a deliberate curve.
        val drawn = handDrawn(arc(600f, 8f), endFraction = 1f, seed = 31)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count)
        assertTrue("got $shape", shape is ShapeSpec.Line)
    }

    @Test
    fun `an arc drawn nearly a half turn comes out an exact half turn`() {
        val drawn = handDrawn(arc(130f, 172f), endFraction = 1f, seed = 32)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Arc
        assertEquals(180f, abs(shape.sweep).toDegrees(), 0.01f)
    }

    @Test
    fun `an arc at no particular sweep keeps the sweep it was drawn at`() {
        // The snap has to be a rounding of the near misses, not a menu of the only curves on offer.
        val drawn = handDrawn(arc(130f, 55f), endFraction = 1f, seed = 33)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Arc
        assertEquals(55f, abs(shape.sweep).toDegrees(), 8f)
    }

    @Test
    fun `an arc drawn with its ends nearly level comes out with them exactly level`() {
        // A bow's chord is the line it would have been, and it gets the same levelling: an arch
        // whose feet are three degrees apart reads as badly drawn, not as deliberately tilted.
        val drawn = handDrawn(arc(130f, 120f, start = 26f.toRadians()), endFraction = 1f, seed = 34)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Arc
        val ends = FloatArray(2)
        shape.handleInto(0, ends)
        val x0 = ends[0]
        val y0 = ends[1]
        shape.handleInto(2, ends)
        assertEquals("the chord should be dead level", y0, ends[1], 1e-3f)
        assertTrue("and should not have collapsed", abs(ends[0] - x0) > 100f)
    }

    @Test
    fun `an arc records which way round the pen went`() {
        // Sweep is signed, and the sign is the difference between a bow that opens upwards and one
        // that opens down. Reversing the drawing must reverse it rather than produce the same arc.
        val drawn = handDrawn(arc(130f, 100f), endFraction = 1f, seed = 35)
        val forward = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Arc
        val n = drawn.count
        val backward = ShapeRecognizer.recognize(
            FloatArray(n) { drawn.xs[n - 1 - it] },
            FloatArray(n) { drawn.ys[n - 1 - it] },
            n,
        ) as ShapeSpec.Arc
        assertTrue("opposite senses", forward.sweep * backward.sweep < 0f)
        assertEquals(abs(forward.sweep), abs(backward.sweep), 0.05f)
        assertEquals(forward.cx, backward.cx, 4f)
        assertEquals(forward.cy, backward.cy, 4f)
    }

    @Test
    fun `a curve that bends both ways is left as the ink it was drawn with`() {
        // An S is smooth and deliberate and is not an arc: no circle bends in two directions. It
        // has to come back as the drawing rather than as the single bend that averages it out,
        // which is what a fit judged on distance alone would happily return.
        val n = 90
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            xs[i] = 60f + 260f * t
            ys[i] = 200f + 70f * sin(2f * PI.toFloat() * t)
        }
        assertNull(ShapeRecognizer.recognize(xs, ys, n))
    }

    // ---- Made perfect --------------------------------------------------------------------

    @Test
    fun `a square drawn a few degrees off level comes out level`() {
        // Nobody draws level. A box six degrees out does not read as a box drawn at six degrees;
        // it reads as a box drawn badly, and the user has to fix by hand what they meant all along.
        for (tilt in listOf(-9f, -6f, -3f, 3f, 6f, 9f)) {
            val drawn = handDrawn(square(200f, rot = tilt.toRadians()), seed = 200)
            val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
            assertEquals("$tilt degrees", 0f, shape.rot, 1e-6f)
            assertTrue(shape.equilateral)
        }
    }

    @Test
    fun `a square drawn near the diagonal comes out an exact diamond`() {
        // 0 is not the only orientation worth snapping to: a square at 41 degrees was a diamond.
        val drawn = handDrawn(square(200f, rot = 41f.toRadians()), seed = 201)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertTrue(shape.equilateral)
        assertEquals(45f, abs(normaliseQuarter(shape.rot).toDegrees()), 1e-3f)
    }

    @Test
    fun `an oblong drawn off level comes out level, still an oblong`() {
        val drawn = handDrawn(rect(240f, 120f, rot = 7f.toRadians()), seed = 202)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertEquals(0f, shape.rot, 1e-6f)
        assertTrue("levelling must not also make it square", !shape.equilateral)
    }

    @Test
    fun `an ellipse drawn slightly off upright is straightened`() {
        val drawn = handDrawn(ellipse(160f, 80f, rot = 8f.toRadians()), seed = 203)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Ellipse
        assertEquals(0f, shape.rot, 1e-6f)
    }

    @Test
    fun `a deliberate tilt well past the snap is left exactly where it was drawn`() {
        // The other half of the contract: snapping must not become an inability to draw at an angle.
        val drawn = handDrawn(rect(240f, 120f, rot = 25f.toRadians()), seed = 204)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertEquals(25f, normaliseQuarter(shape.rot).toDegrees(), 4f)
    }

    @Test
    fun `a triangle drawn nearly equilateral is made equilateral`() {
        // Judged the way a person would judge it — are the sides the same length, are the corners
        // the same angle — rather than by the distance metric, which forgives neither and so
        // leaves a very slightly uneven triangle as an arbitrary three-sided shape.
        val drawn = handDrawn(poly(0f to 0f, 300f to 0f, 144f to -268f), seed = 205)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Ngon
        assertEquals(3, shape.sides)
    }

    @Test
    fun `a quadrilateral with four near-right angles is made a rectangle`() {
        val drawn = handDrawn(poly(0f to 0f, 300f to 6f, 294f to 190f, -4f to 184f), seed = 206)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Rect
        assertEquals(0f, shape.rot, 1e-6f)
        assertEquals(300f, shape.hw * 2f, 20f)
        assertEquals(186f, shape.hh * 2f, 20f)
    }

    @Test
    fun `a right-angled triangle drawn off level is levelled without being reshaped`() {
        // The trap this guards against: the hypotenuse of a 300 by 220 triangle sits near 135
        // degrees, so snapping edges one at a time drags both legs to 260 and turns an upright
        // triangle into an isosceles one. Tilt is one number and correcting it is one rotation.
        val ideal = poly(0f to 0f, 300f to 0f, 0f to -220f)
        val drawn = handDrawn(rotated(ideal, 6f.toRadians()), seed = 207)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Poly

        val legs = sideLengths(shape).sorted()
        assertEquals("short leg", 220f, legs[0], 22f)
        assertEquals("long leg", 300f, legs[1], 30f)
        // Two of its edges should now be square to the page.
        val square = edgeAngles(shape).count { abs(it % 90f) < 1.5f || abs(abs(it % 90f) - 90f) < 1.5f }
        assertEquals("edges square to the page", 2, square)
    }

    @Test
    fun `a trapezium drawn off level gets its parallel sides level and keeps its slant`() {
        val ideal = poly(0f to 0f, 260f to 0f, 210f to 150f, 50f to 150f)
        val drawn = handDrawn(rotated(ideal, 7f.toRadians()), seed = 208)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Poly

        val level = edgeAngles(shape).count { abs(it % 180f) < 2f || abs(abs(it % 180f) - 180f) < 2f }
        assertEquals("the two parallel sides", 2, level)
        val lengths = sideLengths(shape).sorted()
        assertEquals("the slanted sides keep their slant", lengths[0], lengths[1], 12f)
    }

    @Test
    fun `a pentagon drawn nearly upright comes out exactly upright`() {
        // A pentagon repeats every 72 degrees, so asking it for a multiple of 45 would ask it to be
        // something it cannot be. What it can be is symmetric about the vertical.
        val upright = -90f + 4f
        val drawn = handDrawn(ngon(5, 130f, rot = upright.toRadians()), seed = 209)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Ngon
        assertEquals(5, shape.sides)
        // A pentagon turned by a whole period is the same pentagon, so the rotation is only
        // meaningful modulo 72 degrees; what is being asserted is that a vertex points straight up.
        assertEquals("apex straight up", 0f, foldInto(shape.rot.toDegrees() + 90f, 72f), 0.01f)
    }

    // ---- Refusals --------------------------------------------------------------------------

    @Test
    fun `handwriting is not a shape`() {
        // The single most important negative: the dwell is a deliberate gesture, but a pause in the
        // middle of writing must not turn a letter into a circle.
        val random = Random(20260820)
        val n = 120
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var x = 0f
        var y = 0f
        for (i in 0 until n) {
            x += 1.4f + random.nextFloat() * 2f
            y = 20f * sin(i / 4.5f) + random.nextFloat() * 6f
            xs[i] = x
            ys[i] = y
        }
        assertNull(ShapeRecognizer.recognize(xs, ys, n))
    }

    @Test
    fun `a spiral is not a circle`() {
        val n = 200
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = i / n.toFloat() * 4f * PI.toFloat()
            val r = 20f + i * 0.6f
            xs[i] = 200f + r * cos(a)
            ys[i] = 200f + r * sin(a)
        }
        assertNull(ShapeRecognizer.recognize(xs, ys, n))
    }

    @Test
    fun `a three-quarter arc is an arc and not a closed circle`() {
        // Every point of the arc genuinely lies on the circle, so only measuring the ideal back
        // against the drawing reveals the quarter that was never drawn. What is left is an arc:
        // the same evidence that refuses the circle describes the piece that was drawn exactly.
        val drawn = handDrawn(circle(120f), endFraction = 0.75f, seed = 15)
        val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) as ShapeSpec.Arc
        assertEquals(270f, abs(shape.sweep).toDegrees(), 1f)
        assertEquals(120f, shape.r, 8f)
    }

    @Test
    fun `a stroke too small to be a deliberate shape is left alone`() {
        val drawn = handDrawn(square(8f), seed = 16)
        assertNull(ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count))
    }

    @Test
    fun `a dot is left alone`() {
        assertNull(ShapeRecognizer.recognize(floatArrayOf(10f, 10.01f), floatArrayOf(10f, 10f), 2))
    }

    // ---- Tolerance -------------------------------------------------------------------------

    @Test
    fun `every shape survives the wobble of an unsteady hand`() {
        // The reliability contract, written down, and the budgets are measurements rather than
        // aspirations. Peak displacement is a fraction of the shape's own diagonal, so this is
        // scale-free: 3% of a 280 pt square is an 8 pt wander, which is a decidedly unsteady hand.
        //
        // The budgets fall as the sides multiply, and that is inherent: the difference between an
        // octagon and a circle is a fifth of the difference between a pentagon and one, so it is
        // the first thing a shaky hand erases. The test below covers what happens past the budget.
        for ((name, ideal, budget) in TOLERANCE_CASES) {
            for (seed in 0 until 20) {
                val drawn = handDrawn(ideal, jitter = budget, seed = 700 + seed)
                val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count)
                assertTrue("$name (seed $seed) was not recognised at all", shape != null)
                assertTrue("$name (seed $seed) came out as $shape", sameFamily(ideal, shape!!))
            }
        }
    }

    @Test
    fun `a shape drawn too shakily to place is never mistaken for a different polygon`() {
        // Past the point of certainty what matters is not that the recogniser keeps being right,
        // but *how* it is wrong. An octagon that comes out as a circle is a near-miss the user can
        // live with; one that comes out as a hexagon is a bug they will report. Well beyond every
        // budget above, each miss still lands on the circle.
        for ((name, ideal, _) in TOLERANCE_CASES) {
            for (seed in 0 until 20) {
                val drawn = handDrawn(ideal, jitter = 0.03f, seed = 800 + seed)
                val shape = ShapeRecognizer.recognize(drawn.xs, drawn.ys, drawn.count) ?: continue
                assertTrue(
                    "$name (seed $seed) degraded to $shape, which is a different shape entirely",
                    sameFamily(ideal, shape) || shape is ShapeSpec.Ellipse || bentLine(ideal, shape),
                )
            }
        }
    }

    /**
     * A line come out as a gentle bow, which is the one direction a line is allowed to miss in.
     *
     * A hand this unsteady genuinely does put a curve on the page, and the arc fit reports it
     * honestly; what must not happen is a line arriving as a pronounced curve, so the bow is
     * bounded. Below the recogniser's straightening threshold this cannot happen at all — the arc
     * is turned back into a line — so what is left here is the narrow band above it.
     */
    private fun bentLine(ideal: ShapeSpec, got: ShapeSpec): Boolean {
        if (ideal !is ShapeSpec.Line || got !is ShapeSpec.Arc) return false
        return abs(got.sweep).toDegrees() < 35f
    }

    private fun sameFamily(ideal: ShapeSpec, got: ShapeSpec): Boolean = when (ideal) {
        is ShapeSpec.Rect -> got is ShapeSpec.Rect && got.equilateral == ideal.equilateral
        is ShapeSpec.Ellipse -> got is ShapeSpec.Ellipse && got.equilateral == ideal.equilateral
        // A regular quadrilateral is described as a square, which is the same picture.
        is ShapeSpec.Ngon -> (got as? ShapeSpec.Ngon)?.sides == ideal.sides ||
            (ideal.sides == 4 && got is ShapeSpec.Rect && got.equilateral)
        else -> got::class == ideal::class
    }

    // ---- Fixtures --------------------------------------------------------------------------

    /** Shape, and the peak wobble it tolerates as a fraction of its own diagonal. */
    private val TOLERANCE_CASES = listOf(
        Triple("square", square(200f), 0.030f),
        Triple("oblong", rect(240f, 120f), 0.030f),
        Triple("circle", circle(120f), 0.020f),
        Triple("ellipse", ellipse(160f, 80f, rot = 0.3f), 0.030f),
        Triple("triangle", ngon(3, 130f), 0.030f),
        Triple("pentagon", ngon(5, 130f), 0.025f),
        Triple("hexagon", ngon(6, 130f), 0.025f),
        Triple("heptagon", ngon(7, 130f), 0.015f),
        Triple("octagon", ngon(8, 130f), 0.006f),
        Triple("line", ShapeSpec.Line(40f, 60f, 300f, 210f), 0.030f),
        Triple("arc", arc(130f, 90f), 0.030f),
        Triple("half circle", arc(130f, 180f), 0.030f),
    )

    private class Drawn(val xs: FloatArray, val ys: FloatArray) {
        val count: Int get() = xs.size
    }

    private fun square(side: Float, rot: Float = 0f) =
        ShapeSpec.Rect(200f, 200f, side / 2f, side / 2f, rot, equilateral = true)

    private fun rect(w: Float, h: Float, rot: Float = 0f) =
        ShapeSpec.Rect(200f, 200f, w / 2f, h / 2f, rot, equilateral = false)

    private fun circle(r: Float) = ShapeSpec.Ellipse(200f, 200f, r, r, 0f, equilateral = true)

    /** An arc of [sweepDegrees] on a circle of radius [r], starting at three o'clock. */
    private fun arc(r: Float, sweepDegrees: Float, start: Float = 0f) =
        ShapeSpec.Arc(200f, 200f, r, start, sweepDegrees.toRadians())

    private fun ellipse(rx: Float, ry: Float, rot: Float) =
        ShapeSpec.Ellipse(200f, 200f, rx, ry, rot, equilateral = false)

    private fun ngon(sides: Int, r: Float, rot: Float = 0f) =
        ShapeSpec.Ngon(200f, 200f, r, rot, sides)

    private fun poly(vararg points: Pair<Float, Float>) =
        ShapeSpec.Poly(points.map { it.first }.toFloatArray(), points.map { it.second }.toFloatArray())

    private fun rotated(poly: ShapeSpec.Poly, angle: Float): ShapeSpec.Poly {
        val c = cos(angle)
        val s = sin(angle)
        val n = poly.vertexCount
        return ShapeSpec.Poly(
            FloatArray(n) { poly.xs[it] * c - poly.ys[it] * s },
            FloatArray(n) { poly.xs[it] * s + poly.ys[it] * c },
        )
    }

    private fun sideLengths(poly: ShapeSpec.Poly): List<Float> =
        (0 until poly.vertexCount).map {
            val j = (it + 1) % poly.vertexCount
            hypot(poly.xs[j] - poly.xs[it], poly.ys[j] - poly.ys[it])
        }

    /** Each edge's direction in degrees, normalised to 0..180 so opposite senses agree. */
    private fun edgeAngles(poly: ShapeSpec.Poly): List<Float> =
        (0 until poly.vertexCount).map {
            val j = (it + 1) % poly.vertexCount
            val a = kotlin.math.atan2(poly.ys[j] - poly.ys[it], poly.xs[j] - poly.xs[it]).toDegrees()
            ((a % 180f) + 180f) % 180f
        }

    /**
     * Walks [ideal] at even speed and displaces it, producing what a person would have put on the
     * page. [endFraction] below 1 leaves the shape unclosed, as a real closed stroke nearly always
     * is; [jitter] is the peak displacement as a fraction of the shape's diagonal.
     */
    private fun handDrawn(
        ideal: ShapeSpec,
        samples: Int = 90,
        jitter: Float = 0.012f,
        endFraction: Float = 0.97f,
        seed: Int = 0,
    ): Drawn {
        val outline = StrokeOutline()
        ideal.outlineInto(outline)
        val m = outline.count

        val cumulative = FloatArray(m)
        for (i in 1 until m) {
            cumulative[i] = cumulative[i - 1] +
                hypot(outline.x(i) - outline.x(i - 1), outline.y(i) - outline.y(i - 1))
        }
        val total = cumulative[m - 1]

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until m) {
            minX = min(minX, outline.x(i)); maxX = max(maxX, outline.x(i))
            minY = min(minY, outline.y(i)); maxY = max(maxY, outline.y(i))
        }
        val diagonal = hypot(maxX - minX, maxY - minY)

        val xs = FloatArray(samples)
        val ys = FloatArray(samples)
        var cursor = 0
        for (k in 0 until samples) {
            val target = total * endFraction * k / (samples - 1)
            while (cursor < m - 2 && cumulative[cursor + 1] < target) cursor++
            val span = cumulative[cursor + 1] - cumulative[cursor]
            val t = if (span <= 1e-6f) 0f else (target - cumulative[cursor]) / span
            xs[k] = outline.x(cursor) + (outline.x(cursor + 1) - outline.x(cursor)) * t
            ys[k] = outline.y(cursor) + (outline.y(cursor + 1) - outline.y(cursor)) * t
        }

        val random = Random(20260820 + seed)
        val nx = smoothNoise(samples, random)
        val ny = smoothNoise(samples, random)
        for (k in 0 until samples) {
            xs[k] += nx[k] * jitter * diagonal
            ys[k] += ny[k] * jitter * diagonal
        }
        return Drawn(xs, ys)
    }

    /**
     * Low-frequency noise in ±1, normalised to that peak.
     *
     * A hand wanders; it does not hop between samples. Smoothing white noise twice is the cheapest
     * way to get a displacement with the right character, and it is a *harder* test than white
     * noise for the corner detector, which sees a smooth bulge as a possible corner.
     */
    private fun smoothNoise(n: Int, random: Random): FloatArray {
        var v = FloatArray(n) { random.nextFloat() * 2f - 1f }
        repeat(3) {
            val out = FloatArray(n)
            for (i in 0 until n) {
                val a = v[max(0, i - 1)]
                val b = v[i]
                val c = v[min(n - 1, i + 1)]
                out[i] = (a + b + c) / 3f
            }
            v = out
        }
        val peak = v.maxOf { abs(it) }
        if (peak > 1e-6f) for (i in 0 until n) v[i] /= peak
        return v
    }

    /** Folds a value into ±[period]/2, for comparing angles that repeat. */
    private fun foldInto(value: Float, period: Float): Float {
        var v = value % period
        if (v > period / 2f) v -= period
        if (v < -period / 2f) v += period
        return v
    }

    private fun Float.toRadians(): Float = this * PI.toFloat() / 180f
    private fun Float.toDegrees(): Float = this * 180f / PI.toFloat()

    /** Folds an angle into ±45°, the range in which a square's rotation is unique. */
    private fun normaliseQuarter(rot: Float): Float {
        var r = rot
        val quarter = PI.toFloat() / 2f
        while (r >= quarter / 2f) r -= quarter
        while (r < -quarter / 2f) r += quarter
        return r
    }
}
