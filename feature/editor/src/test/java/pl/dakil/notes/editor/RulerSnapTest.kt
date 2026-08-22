package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.editor.canvas.rulerEdgeAt
import pl.dakil.notes.ink.CM_IN_POINTS
import pl.dakil.notes.ink.RulerGuide
import pl.dakil.notes.ink.RulerPose
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PointerSample
import kotlin.math.PI

/**
 * The hand-off between the ruler, which lies on the screen's strip, and the ink, which is written
 * in document points.
 *
 * Worth testing on its own because getting it wrong is invisible in exactly the way the coordinate
 * mapping next door is: the line still appears, still looks straight, and is simply not where the
 * straightedge was — or, in paged view, drifts by one inter-page gap per page.
 */
class RulerSnapTest {

    private val format = PageFormat.DEFAULT
    private val ptToPx = 6.67f

    /** A ruler lying flat across the first page, its numbered edge on [docY]. */
    private fun flatRuler(docY: Float, angleRad: Float = 0f, paged: Boolean = true) = RulerPose(
        edgeX = 200f * ptToPx,
        edgeY = SheetPainter.documentYToStripPx(docY, format, ptToPx, paged),
        angleRad = angleRad,
        length = 20f * CM_IN_POINTS * ptToPx,
        thickness = 60f,
    )

    /** The document y of the far edge of a ruler whose numbered edge is on [docY]. */
    private fun lowerEdgeDocY(docY: Float): Float = docY + 60f / ptToPx

    @Test
    fun `a stroke started at the edge is straightened onto it`() {
        val pose = flatRuler(docY = 300f)
        val edgeY = lowerEdgeDocY(300f)
        // The pen lands a few pixels short of the edge, which is what aiming at it looks like.
        val startY = edgeY + 4f / ptToPx
        val edge = rulerEdgeAt(pose, docX = 150f, docY = startY, band = 10f, ptToPx, format, paged = true)
        assertNotNull(edge)

        val guide = RulerGuide()
        guide.engage(edge)
        // The hand wanders off the edge, as it does when the pen is pressed against a real ruler.
        val wandered = guide.snap(PointerSample(x = 260f, y = startY + 12f))
        assertEquals(260f, wandered.x, 0.01f)
        assertEquals(edgeY, wandered.y, 0.05f)
    }

    @Test
    fun `the ink line sits half a nib outside the edge it was drawn against`() {
        // A pen held against a straightedge marks the paper beside it, never underneath it. The
        // visible consequence of dropping this is that the line does not appear at all until the
        // ruler is moved off it — the slab's own edge is drawn over exactly where it would land.
        val pose = flatRuler(docY = 300f)
        val edgeY = lowerEdgeDocY(300f)
        val nib = 4f

        val edge = rulerEdgeAt(
            pose, docX = 150f, docY = edgeY + 4f / ptToPx, band = 10f, ptToPx, format,
            paged = true, outward = nib * 0.5f,
        )
        assertNotNull(edge)
        assertEquals(edgeY + nib * 0.5f, edge!!.y, 0.01f)
    }

    @Test
    fun `the offset is away from the slab on whichever edge was taken`() {
        val pose = flatRuler(docY = 300f)
        val upperEdgeY = 300f
        val edge = rulerEdgeAt(
            pose, docX = 150f, docY = upperEdgeY - 4f / ptToPx, band = 10f, ptToPx, format,
            paged = true, outward = 2f,
        )
        assertNotNull(edge)
        assertEquals(upperEdgeY - 2f, edge!!.y, 0.01f)
    }

    @Test
    fun `a stroke started well away from the ruler stays free-hand`() {
        val pose = flatRuler(docY = 300f)
        assertNull(rulerEdgeAt(pose, docX = 150f, docY = 380f, band = 10f, ptToPx, format, paged = true))
    }

    @Test
    fun `the edge a stroke snaps to does not move when the page is zoomed`() {
        // The slab's width is fixed on the glass, so it is a different number of document points
        // at every zoom. If that width were centred on the ruler's anchor, the line a stroke snaps
        // to would shift under the paper each time the user pinched — the ruler would drift off
        // whatever it had been lined up against.
        val edgeY = 300f
        val zoomedOut = flatRuler(edgeY).copy(thickness = 12f)
        val zoomedIn = flatRuler(edgeY).copy(thickness = 400f)

        for (pose in listOf(zoomedOut, zoomedIn)) {
            val edge = rulerEdgeAt(
                pose, docX = 150f, docY = edgeY - 2f / ptToPx, band = 10f, ptToPx, format,
                paged = true,
            )
            assertNotNull(edge)
            assertEquals(edgeY, edge!!.y, 0.01f)
        }
    }

    @Test
    fun `the edge on the fourth page carries no accumulated page gaps`() {
        // The gaps between sheets are screen furniture. A ruler placed on page four maps to a line
        // in the document, and a coordinate that kept the gaps would put the ink three gaps low.
        val docY = format.height * 3 + 120f
        val pose = flatRuler(docY = docY)
        val edgeY = lowerEdgeDocY(docY)

        val edge = rulerEdgeAt(pose, docX = 150f, docY = edgeY + 4f / ptToPx, band = 10f, ptToPx, format, paged = true)
        assertNotNull(edge)
        assertEquals(edgeY, edge!!.y, 0.05f)

        val guide = RulerGuide()
        guide.engage(edge)
        assertEquals(edgeY, guide.snap(PointerSample(x = 400f, y = docY + 200f)).y, 0.05f)
    }

    @Test
    fun `a tilted ruler keeps its slope through the conversion`() {
        // Both axes carry the same scale, so the direction needs no unwinding — but a mistake here
        // would show up as a line at a subtly different angle from the edge it was drawn against.
        val pose = flatRuler(docY = 300f, angleRad = (PI / 6).toFloat())
        val edge = rulerEdgeAt(pose, docX = 200f, docY = 300f, band = 40f, ptToPx, format, paged = true)
        assertNotNull(edge)
        assertEquals(kotlin.math.cos(PI / 6).toFloat(), edge!!.dx, 1e-4f)
        assertEquals(kotlin.math.sin(PI / 6).toFloat(), edge.dy, 1e-4f)
    }

    @Test
    fun `continuous view maps the same edge to the same document line`() {
        val docY = format.height * 2 + 90f
        val pose = flatRuler(docY = docY, paged = false)
        val edgeY = lowerEdgeDocY(docY)

        val edge = rulerEdgeAt(pose, docX = 150f, docY = edgeY + 4f / ptToPx, band = 10f, ptToPx, format, paged = false)
        assertNotNull(edge)
        assertEquals(edgeY, edge!!.y, 0.05f)
    }
}
