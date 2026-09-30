package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.canvas.MAX_SCALE
import pl.dakil.notes.editor.canvas.MIN_SCALE
import pl.dakil.notes.editor.canvas.SelectionHandle
import pl.dakil.notes.editor.canvas.handleAnchor
import pl.dakil.notes.editor.canvas.handlePosition
import pl.dakil.notes.editor.canvas.rotateMatrix
import pl.dakil.notes.editor.canvas.scaleMatrix
import pl.dakil.notes.model.Rect

/**
 * The maths behind the grips round a selection.
 *
 * Worth having on the JVM because every one of these is otherwise only observable by dragging a
 * corner on a phone and squinting: a scale that quietly moved the anchor, or a rotation seeded from
 * the wrong point, looks like "the selection jumped a bit" and nothing else.
 */
class SelectionGeometryTest {

    private val box = Rect(100f, 200f, 300f, 400f)
    private val eps = 1e-3f

    @Test
    fun `a corner scale leaves the opposite corner exactly where it was`() {
        // The whole feel of a resize handle: the far corner is nailed to the page and only the
        // dragged one travels. Anything else reads as the selection sliding about as it grows.
        val handle = SelectionHandle.BOTTOM_RIGHT
        val (ax, ay) = handleAnchor(box, handle)
        assertEquals(box.left, ax, eps)
        assertEquals(box.top, ay, eps)

        val (hx, hy) = handlePosition(box, handle)
        val m = scaleMatrix(box, handle, hx, hy, hx + 200f, hy + 200f)

        assertEquals(ax, m.mapX(ax, ay), eps)
        assertEquals(ay, m.mapY(ax, ay), eps)
    }

    @Test
    fun `a corner scale is uniform whichever way the finger went`() {
        // Dragged along one axis only. A non-uniform scale would drop a recognised shape's spec
        // and leave the stroke widths undefined, so the factor has to come out the same on both.
        val handle = SelectionHandle.BOTTOM_RIGHT
        val (hx, hy) = handlePosition(box, handle)
        val m = scaleMatrix(box, handle, hx, hy, hx + 100f, hy)

        assertEquals(m.a, m.d, eps)
        assertEquals(0f, m.b, eps)
        assertEquals(0f, m.c, eps)
    }

    @Test
    fun `dragging a corner onto its anchor cannot collapse the selection`() {
        // A factor of zero leaves the selection a point, with no corner left to drag back out.
        val handle = SelectionHandle.TOP_LEFT
        val (ax, ay) = handleAnchor(box, handle)
        val (hx, hy) = handlePosition(box, handle)
        val m = scaleMatrix(box, handle, hx, hy, ax, ay)

        assertEquals(MIN_SCALE, m.a, eps)
        assertTrue(m.a > 0f)
    }

    @Test
    fun `a wild drag past the anchor is clamped rather than thrown off the sheet`() {
        val handle = SelectionHandle.TOP_LEFT
        val (hx, hy) = handlePosition(box, handle)
        val m = scaleMatrix(box, handle, hx, hy, hx - 100_000f, hy - 100_000f)

        assertEquals(MAX_SCALE, m.a, eps)
    }

    @Test
    fun `a grab on the anchor itself does nothing at all`() {
        // No distance means no ratio and no angle: the drag has no direction to be about.
        val handle = SelectionHandle.TOP_LEFT
        val (ax, ay) = handleAnchor(box, handle)
        assertTrue(scaleMatrix(box, handle, ax, ay, ax + 50f, ay + 50f).isIdentity)
        assertTrue(rotateMatrix(box, 200f, 300f, 250f, 350f).isIdentity)
    }

    @Test
    fun `a rotation turns about the centre and leaves it fixed`() {
        val cx = 200f
        val cy = 300f
        // A quarter turn: from due right of the centre to due below it. y grows downward, so that
        // is the positive direction — the same convention ShapeSpec's angles use.
        val m = rotateMatrix(box, cx + 100f, cy, cx, cy + 100f)

        assertEquals(cx, m.mapX(cx, cy), eps)
        assertEquals(cy, m.mapY(cx, cy), eps)
        // The top-left corner lands where the top-right one was.
        assertEquals(cx + 100f, m.mapX(box.left, box.top), eps)
        assertEquals(cy - 100f, m.mapY(box.left, box.top), eps)
    }

    @Test
    fun `the rotate grip hangs below the frame and the action bar takes the space above`() {
        val (rx, ry) = handlePosition(box, SelectionHandle.ROTATE)
        assertEquals(200f, rx, eps)
        assertEquals(box.bottom, ry, eps)
    }
}
