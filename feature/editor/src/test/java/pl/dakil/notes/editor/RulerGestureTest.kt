package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.canvas.RulerState
import pl.dakil.notes.ink.RULER_MAX_LENGTH_CM
import pl.dakil.notes.ink.RULER_MIN_LENGTH_CM
import pl.dakil.notes.ink.RulerSide
import kotlin.math.PI

/**
 * What two fingers on the slab do to it.
 *
 * The gesture plumbing itself needs a touchscreen, but everything it decides — how far the ruler
 * moves, which way it turns, how long it ends up — is arithmetic, and this is where a regression
 * in it would otherwise only be visible as a ruler that behaves oddly in the hand.
 */
class RulerGestureTest {

    private fun placedRuler() = RulerState().apply {
        // 10 px to the centimetre keeps the numbers in this file readable.
        configure(ptToPx = 10f / (72f / 2.54f), thicknessOnGlassPx = 60f, endMarginOnGlassPx = 10f)
        placeAt(100f, 200f)
    }

    @Test
    fun `a two-finger drag carries the ruler and leaves its length alone`() {
        val ruler = placedRuler()
        ruler.transformBy(dx = 40f, dy = -25f, dAngle = 0f, lengthFactor = 1f, pivotX = 100f, pivotY = 200f)

        assertEquals(140f, ruler.edgeX, 1e-3f)
        assertEquals(175f, ruler.edgeY, 1e-3f)
        assertEquals(20f, ruler.lengthCm, 1e-3f)
    }

    @Test
    fun `spreading the fingers lengthens the ruler without moving it`() {
        // Growth is symmetric about the middle. Anchoring it to an end instead would walk the
        // ruler across the page while it was being sized, which is the opposite of what someone
        // lining an edge up is asking for.
        val ruler = placedRuler()
        ruler.transformBy(0f, 0f, 0f, lengthFactor = 1.5f, pivotX = 300f, pivotY = 400f)

        assertEquals(30f, ruler.lengthCm, 1e-3f)
        assertEquals(100f, ruler.edgeX, 1e-3f)
        assertEquals(200f, ruler.edgeY, 1e-3f)
    }

    @Test
    fun `the scale keeps its size on the paper as the ruler grows`() {
        // A centimetre is a centimetre. Pinching adds centimetres to the ruler; it must never
        // stretch the ones already on it, or the thing would stop being a measuring instrument.
        val ruler = placedRuler()
        val cmPx = ruler.cmPx
        ruler.transformBy(0f, 0f, 0f, lengthFactor = 0.25f, pivotX = 100f, pivotY = 200f)

        assertEquals(cmPx, ruler.cmPx, 1e-4f)
        assertEquals(5f, ruler.lengthCm, 1e-3f)
        assertEquals(5f * cmPx, ruler.scaleLengthPx, 1e-3f)
    }

    @Test
    fun `pinching cannot shrink or grow the ruler out of reach`() {
        val ruler = placedRuler()
        repeat(10) { ruler.transformBy(0f, 0f, 0f, 0.5f, 100f, 200f) }
        assertEquals(RULER_MIN_LENGTH_CM, ruler.lengthCm, 1e-3f)

        repeat(20) { ruler.transformBy(0f, 0f, 0f, 2f, 100f, 200f) }
        assertEquals(RULER_MAX_LENGTH_CM, ruler.lengthCm, 1e-3f)
    }

    @Test
    fun `turning about a finger swings the far end rather than the near one`() {
        // The pivot is the fingers' centroid, so the slab under them stays put — the behaviour of
        // a ruler pinned by two fingertips, and the reason rotating it does not feel like driving.
        val ruler = placedRuler()
        val pivotX = 100f
        val pivotY = 100f
        ruler.transformBy(0f, 0f, dAngle = (PI / 2).toFloat(), lengthFactor = 1f, pivotX = pivotX, pivotY = pivotY)

        // The centre was 100 below the pivot; a quarter turn puts it 100 to the pivot's left.
        assertEquals(0f, ruler.edgeX, 1e-2f)
        assertEquals(100f, ruler.edgeY, 1e-2f)
    }

    @Test
    fun `the slab covers exactly the scale plus a margin at each end`() {
        val ruler = placedRuler()
        val pose = ruler.pose(zoom = 1f)
        assertEquals(20f * ruler.cmPx + 20f, pose.length, 1e-3f)

        // Both ends of the scale are still on the slab, which is what the margins are for.
        assertTrue(pose.grabbedAt(100f - ruler.scaleLengthPx * 0.5f, 200f, margin = 0f))
        assertFalse(pose.grabbedAt(100f + pose.length, 200f, margin = 0f))
    }

    @Test
    fun `zooming leaves the numbered edge exactly where it was placed`() {
        // The whole reason the pose is anchored to an edge instead of the middle of the slab.
        val ruler = placedRuler()
        for (zoom in listOf(0.26f, 1f, 4f)) {
            val edge = ruler.pose(zoom).edge(RulerSide.UPPER)
            assertEquals(200f, edge.y, 1e-3f)
        }
        // The slab still gets wider as the page shrinks — it is the far edge that gives way.
        assertTrue(ruler.pose(0.26f).thickness > ruler.pose(4f).thickness)
    }
}
