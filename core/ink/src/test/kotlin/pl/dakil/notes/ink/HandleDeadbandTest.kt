package pl.dakil.notes.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleDeadbandTest {

    @Test
    fun `a pen resting a few degrees off a snapped end leaves the end where the snap put it`() {
        val band = HandleDeadband()
        // A 300 pt line: three degrees off its end is about 15.7 pt away.
        band.arm(300f, 0f, length = 300f, minRadius = 2f)
        assertFalse(band.accept(300f, 15f))
        assertFalse(band.accept(298f, -10f))
    }

    @Test
    fun `leaving the margin hands the end to the pen`() {
        val band = HandleDeadband()
        band.arm(300f, 0f, length = 300f, minRadius = 2f)
        assertTrue(band.accept(300f, 40f))
    }

    @Test
    fun `once free the end follows the pen even back inside the margin`() {
        // The margin is a one-off allowance for the hand's miss, not a magnet: someone nudging the
        // end back towards level is adjusting it, and must not find it stuck.
        val band = HandleDeadband()
        band.arm(300f, 0f, length = 300f, minRadius = 2f)
        band.accept(300f, 40f)
        assertTrue(band.accept(300f, 1f))
    }

    @Test
    fun `a short line still gets a margin a hand can rest in`() {
        val band = HandleDeadband()
        band.arm(20f, 0f, length = 20f, minRadius = 3f)
        assertFalse(band.accept(20f, 2.5f))
    }

    @Test
    fun `an unarmed or released band never holds anything back`() {
        val band = HandleDeadband()
        assertTrue(band.accept(0f, 0f))
        band.arm(0f, 0f, length = 300f, minRadius = 2f)
        band.release()
        assertTrue(band.accept(0f, 0f))
    }
}
