package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.ToolSpec
import kotlin.math.PI

class RulerTest {

    /** Lying flat, 200 long and 40 thick, with the middle of its numbered edge on the origin. */
    private val flat = RulerPose(edgeX = 0f, edgeY = 0f, angleRad = 0f, length = 200f, thickness = 40f)

    @Test
    fun `the slab hangs off the numbered edge rather than straddling it`() {
        assertEquals(0f, flat.edge(RulerSide.UPPER).y, 1e-4f)
        assertEquals(40f, flat.edge(RulerSide.LOWER).y, 1e-4f)
    }

    @Test
    fun `the numbered edge stays put however wide the slab has to be drawn`() {
        // The slab is a fixed size on the glass, so its width in paper terms changes with every
        // zoom. Anchored down the middle, that would slide *both* drawing edges across the page as
        // the user zoomed, and a ruler lined up against a word would no longer be lined up with it.
        for (thickness in listOf(8f, 40f, 300f)) {
            val zoomed = flat.copy(thickness = thickness)
            assertEquals(0f, zoomed.edge(RulerSide.UPPER).y, 1e-4f)
        }
    }

    @Test
    fun `a stroke starting just outside an edge takes hold of that edge`() {
        assertEquals(RulerSide.UPPER, flat.snapSide(0f, -6f, band = 10f))
        assertEquals(RulerSide.LOWER, flat.snapSide(0f, 46f, band = 10f))
    }

    @Test
    fun `a stroke starting well clear of the ruler is left free-hand`() {
        // The band has to be narrow enough that ordinary drawing near the ruler is unaffected;
        // a straightedge that captures everything on the page is worse than none.
        assertNull(flat.snapSide(0f, -60f, band = 10f))
    }

    @Test
    fun `a stroke starting past the end of the ruler is left free-hand`() {
        // On the ruler's line but beyond its length: nothing is under the pen to draw against.
        assertNull(flat.snapSide(140f, -22f, band = 10f))
    }

    @Test
    fun `a stroke starting on top of the slab takes the nearer edge`() {
        // Glass, unlike paper, lets the pen land under the ruler. Refusing that would read as the
        // ruler simply not working wherever the user aimed at the middle of it.
        assertEquals(RulerSide.UPPER, flat.snapSide(0f, 6f, band = 10f))
        assertEquals(RulerSide.LOWER, flat.snapSide(0f, 34f, band = 10f))
    }

    @Test
    fun `samples are projected onto the edge whatever the hand does`() {
        val guide = RulerGuide()
        guide.engage(flat.edge(RulerSide.UPPER))
        val drifted = guide.snap(PointerSample(x = 30f, y = -50f))
        assertEquals(30f, drifted.x, 1e-4f)
        assertEquals(0f, drifted.y, 1e-4f)
    }

    @Test
    fun `a stroke that runs off the end keeps going straight`() {
        // The ruler is 20 cm; the line someone wants is often longer. Stopping the snap at the end
        // would put a kink in the middle of a line the user is still drawing along the edge.
        val guide = RulerGuide()
        guide.engage(flat.edge(RulerSide.LOWER))
        val past = guide.snap(PointerSample(x = 400f, y = 90f))
        assertEquals(400f, past.x, 1e-4f)
        assertEquals(40f, past.y, 1e-4f)
    }

    @Test
    fun `a released guide leaves samples exactly as they arrived`() {
        val guide = RulerGuide()
        guide.engage(flat.edge(RulerSide.UPPER))
        guide.release()
        assertFalse(guide.isEngaged)
        val free = guide.snap(PointerSample(x = 7f, y = 9f))
        assertEquals(7f, free.x, 1e-4f)
        assertEquals(9f, free.y, 1e-4f)
    }

    @Test
    fun `a diagonal ruler projects onto its own axis`() {
        val diagonal = RulerPose(0f, 0f, (PI / 4).toFloat(), length = 200f, thickness = 0f)
        val guide = RulerGuide()
        guide.engage(diagonal.edge(RulerSide.UPPER))
        // (10, 0) is half a diagonal step from the 45° line through the origin.
        val snapped = guide.snap(PointerSample(x = 10f, y = 0f))
        assertEquals(5f, snapped.x, 1e-3f)
        assertEquals(5f, snapped.y, 1e-3f)
    }

    @Test
    fun `a ruled stroke is dead straight however fast the hand moves`() {
        // The bug this pins down: the One Euro filter runs per axis with a speed-dependent cutoff,
        // so a fast change of direction blends x and y by different amounts and lands the point
        // off a sloped line — perpendicular to it, which is what let a ruled stroke wander under
        // the ruler. Snapping the input is not enough; the constraint has to survive the filter.
        val edge = RulerPose(0f, 0f, (PI / 6).toFloat(), length = 4000f, thickness = 0f)
            .edge(RulerSide.UPPER)
        val builder = StrokeBuilder()
        val guided = feedZigZag(builder, edge, guide = edge)

        assertTrue("expected several points, got $guided", builder.pointCount > 8)
        for (i in 0 until builder.pointCount) {
            assertEquals(
                "point $i off the edge",
                0f,
                edge.distanceTo(builder.x(i), builder.y(i)),
                1e-2f,
            )
        }
    }

    @Test
    fun `the same hand without a ruler is smoothed off the line, as it should be`() {
        // The counterpart of the test above: free-hand, the filter is supposed to move points
        // around. If this ever stops deviating, the one above has stopped proving anything.
        val edge = RulerPose(0f, 0f, (PI / 6).toFloat(), length = 4000f, thickness = 0f)
            .edge(RulerSide.UPPER)
        val builder = StrokeBuilder()
        feedZigZag(builder, edge, guide = null)

        var worst = 0f
        for (i in 0 until builder.pointCount) {
            worst = maxOf(worst, edge.distanceTo(builder.x(i), builder.y(i)))
        }
        assertTrue("free-hand stayed suspiciously exact: $worst", worst > 0.5f)
    }

    /**
     * Drives the pen along [edge] the way a hand in a hurry does: hard reversals, big steps, and
     * a wobble across the edge that a real hand pressed against a ruler cannot avoid.
     */
    private fun feedZigZag(builder: StrokeBuilder, edge: RulerEdge, guide: RulerEdge?): Int {
        fun at(along: Float, across: Float, timeMs: Long): PointerSample {
            val x = edge.x + edge.dx * along - edge.dy * across
            val y = edge.y + edge.dy * along + edge.dx * across
            val snapped = if (guide == null) x to y else guide.projectX(x, y) to guide.projectY(x, y)
            return PointerSample(x = snapped.first, y = snapped.second, timeMs = timeMs)
        }

        builder.start(ToolSpec.PEN, InputConfig(), at(0f, 0f, 0L), guide)
        var along = 0f
        var time = 0L
        for (step in 1..40) {
            // Reversing every few samples is what makes the two axes disagree the most.
            along += if ((step / 3) % 2 == 0) 60f else -45f
            time += 4L
            builder.add(at(along, if (step % 2 == 0) 3f else -3f, time))
        }
        return builder.pointCount
    }

    @Test
    fun `two fingers anywhere on the slab take hold of it`() {
        assertTrue(flat.grabbedAt(80f, 15f, margin = 8f))
        assertTrue(flat.grabbedAt(-104f, 44f, margin = 8f))
        assertFalse(flat.grabbedAt(0f, 60f, margin = 8f))
        assertFalse(flat.grabbedAt(0f, -20f, margin = 8f))
        assertFalse(flat.grabbedAt(140f, 20f, margin = 8f))
    }

    @Test
    fun `turning the ruler upside down keeps the heading it was turned to`() {
        // The far half of the circle is a real heading, not a duplicate: the slab hangs off its
        // numbered edge, so folding 180° away would flip the body across to the other side of that
        // edge and the ruler would jump out from under the fingers turning it.
        assertEquals(PI.toFloat(), normalizeRulerAngle(PI.toFloat()), 1e-4f)
        assertEquals(0.2f - PI.toFloat(), normalizeRulerAngle(0.2f + PI.toFloat()), 1e-4f)
        assertEquals((PI / 2).toFloat(), normalizeRulerAngle((PI / 2).toFloat()), 1e-4f)
        assertEquals((-PI / 2).toFloat(), normalizeRulerAngle((-PI / 2).toFloat()), 1e-4f)
    }

    @Test
    fun `a heading only wraps once the fingers have turned right round`() {
        // Unbounded accumulation is the only thing being folded away here.
        val full = (PI * 2).toFloat()
        assertEquals(0.3f, normalizeRulerAngle(0.3f + full), 1e-4f)
        assertEquals(-0.3f, normalizeRulerAngle(-0.3f - full), 1e-4f)
    }

    @Test
    fun `an angle near a detent is pulled onto it and one between is left alone`() {
        val step = (PI / 12).toFloat()
        val tolerance = 0.04f
        assertEquals(step, snapRulerAngle(step + 0.03f, step, tolerance), 1e-4f)
        val free = step * 1.5f
        assertEquals(free, snapRulerAngle(free, step, tolerance), 1e-4f)
    }

    @Test
    fun `the scale gains a division as the paper is magnified`() {
        // Fit-to-width on a phone is a few dozen pixels to the centimetre: millimetres there would
        // be a grey band, not a scale.
        assertEquals(RulerScale.CENTIMETRE, rulerScaleFor(40f))
        assertEquals(RulerScale.MILLIMETRE, rulerScaleFor(60f))
        assertEquals(RulerScale.TENTH_MILLIMETRE, rulerScaleFor(700f))
    }

    @Test
    fun `a pinched length stays inside the range a hand can use`() {
        // The floor is what keeps the ruler pickable: two fingers have to fit on the slab, and a
        // ruler pinched to nothing could never be taken hold of again to grow it back.
        assertEquals(RULER_MIN_LENGTH_CM, clampRulerLength(0.2f), 1e-4f)
        assertEquals(RULER_MAX_LENGTH_CM, clampRulerLength(400f), 1e-4f)
        assertEquals(7.5f, clampRulerLength(7.5f), 1e-4f)
        assertEquals(RULER_DEFAULT_LENGTH_CM, clampRulerLength(Float.NaN), 1e-4f)
    }

    @Test
    fun `numbers thin out rather than overlap when the ruler is small`() {
        assertEquals(1, rulerLabelStepCm(60f))
        assertEquals(5, rulerLabelStepCm(10f))
        assertEquals(10, rulerLabelStepCm(3f))
    }
}
