package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeDwellTest {

    private val tolerance = 2f

    @Test
    fun `holding still accumulates time towards the trigger`() {
        val dwell = DwellTracker()
        dwell.start(10f, 10f, timeMs = 1000L, pointIndex = 40)
        dwell.onSample(10.5f, 9.8f, 1100L, 41, tolerance)
        assertEquals(400L, dwell.heldFor(1400L))
    }

    @Test
    fun `moving away restarts the clock from where the pen went`() {
        // Otherwise a long slow stroke would snap in the middle of being drawn.
        val dwell = DwellTracker()
        dwell.start(10f, 10f, 1000L, 40)
        dwell.onSample(60f, 10f, 1300L, 55, tolerance)
        assertEquals(100L, dwell.heldFor(1400L))
        assertEquals(60f, dwell.anchorX, 1e-6f)
    }

    @Test
    fun `the anchor records how much of the stroke existed when the pen stopped`() {
        // The samples deposited while holding still are a knot at one point. Fitting a shape to
        // them drags a corner towards the middle, so recognition runs on the stroke up to here.
        val dwell = DwellTracker()
        dwell.start(0f, 0f, 0L, 0)
        dwell.onSample(50f, 0f, 100L, 30, tolerance)
        repeat(20) { dwell.onSample(50.4f, 0.3f, 150L + it * 10L, 31 + it, tolerance) }
        assertEquals(30, dwell.anchorPointIndex)
        assertEquals(340L, dwell.heldFor(440L))
    }

    @Test
    fun `recognition is attempted once per dwell, and again once the pen has moved`() {
        // A stroke that is not a shape must not be re-examined forty times a second while the user
        // thinks about it — but moving and stopping again is a fresh request.
        val dwell = DwellTracker()
        dwell.start(0f, 0f, 0L, 0)
        dwell.onSample(0.2f, 0.1f, 100L, 5, tolerance)
        assertFalse(dwell.attempted)

        dwell.attempted = true
        dwell.onSample(0.4f, 0.2f, 200L, 6, tolerance)
        assertTrue("still the same dwell", dwell.attempted)

        dwell.onSample(90f, 40f, 300L, 20, tolerance)
        assertFalse("the pen moved, so this is a new dwell", dwell.attempted)
    }

    @Test
    fun `a tracker that was never started never reports a dwell`() {
        // The ticker runs for the whole stroke, including the pass-through cases where auto-shape
        // is off or the tool is not a drawing one.
        val dwell = DwellTracker()
        dwell.onSample(0f, 0f, 0L, 0, tolerance)
        assertEquals(0L, dwell.heldFor(10_000L))
    }

    @Test
    fun `resetting stops the clock`() {
        val dwell = DwellTracker()
        dwell.start(0f, 0f, 0L, 0)
        dwell.reset()
        assertEquals(0L, dwell.heldFor(10_000L))
        assertFalse(dwell.attempted)
    }
}
