package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.ShapeSpec
import pl.dakil.notes.model.ToolId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class ShapeGeometryTest {

    // ---- Outlines ------------------------------------------------------------------------------

    @Test
    fun `a polygon outline repeats its corners so the renderer draws them sharp`() {
        // StrokeRenderer connects samples with quadratics through their midpoints, which rounds a
        // corner by half the sample spacing. Repeating the vertex collapses the control polygon on
        // to it. Without this a snapped square renders with visibly soft corners — the one way a
        // recognised shape can still look hand-drawn after all the work above.
        val square = ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = true)
        val outline = StrokeOutline()
        square.outlineInto(outline)

        val corner = FloatArray(2).also { square.handleInto(1, it) }
        val at = (0 until outline.count).first {
            outline.x(it) == corner[0] && outline.y(it) == corner[1]
        }
        assertEquals(corner[0], outline.x(at + 1), 0f)
        assertEquals(corner[1], outline.y(at + 1), 0f)
        assertEquals(corner[0], outline.x(at + 2), 0f)
    }

    @Test
    fun `a closed outline ends where it started`() {
        for (spec in listOf(
            ShapeSpec.Rect(100f, 100f, 50f, 30f, 0.3f),
            ShapeSpec.Ngon(100f, 100f, 60f, 0.2f, 7),
            ShapeSpec.Ellipse(100f, 100f, 60f, 40f, 0.4f),
        )) {
            val outline = StrokeOutline()
            spec.outlineInto(outline)
            val last = outline.count - 1
            assertEquals("$spec", outline.x(0), outline.x(last), 1e-3f)
            assertEquals("$spec", outline.y(0), outline.y(last), 1e-3f)
        }
    }

    @Test
    fun `outline points are dense enough for a lasso to judge containment`() {
        // Selection tests every point of a stroke, so a shape drawn with only its corners could be
        // caught by a loop that encloses none of its edges.
        val outline = StrokeOutline()
        ShapeSpec.Ngon(200f, 200f, 120f, 0f, 5).outlineInto(outline)
        var worst = 0f
        for (i in 1 until outline.count) {
            worst = maxOf(worst, hypot(outline.x(i) - outline.x(i - 1), outline.y(i) - outline.y(i - 1)))
        }
        assertTrue("largest gap was $worst pt", worst <= 2f)
    }

    @Test
    fun `a huge shape stays within its point budget`() {
        // An A0 poster's worth of circle must not turn into a hundred thousand samples.
        val outline = StrokeOutline()
        ShapeSpec.Rect(0f, 0f, 20_000f, 20_000f, 0f).outlineInto(outline)
        assertTrue("${outline.count} points", outline.count < 2_000)
    }

    @Test
    fun `a shape becomes a stroke of one constant width, carrying its description`() {
        val spec = ShapeSpec.Ngon(100f, 100f, 40f, 0f, 6)
        val stroke = spec.toStroke(ToolId.PEN, color = -1, width = 3.5f, blend = BlendId.NORMAL)
        assertEquals(3.5f, stroke.width, 1e-6f)
        // Constant width is what lets the renderer skip tessellation entirely.
        assertNull(stroke.widthFactors)
        assertEquals(spec, stroke.shape)
        assertTrue(!Tessellator.needsTessellation(stroke))
    }

    // ---- Handles -------------------------------------------------------------------------------

    @Test
    fun `dragging a regular polygon by a vertex spins and resizes it about a fixed centre`() {
        // The gesture that makes the feature feel direct: the vertex the user is holding stays
        // under the pen, so the polygon follows the hand rather than merely growing.
        val pentagon = ShapeSpec.Ngon(100f, 100f, 40f, 0f, 5)
        val moved = pentagon.moveHandle(2, 160f, 100f) as ShapeSpec.Ngon

        assertEquals(100f, moved.cx, 1e-3f)
        assertEquals(100f, moved.cy, 1e-3f)
        assertEquals(60f, moved.r, 1e-3f)
        assertEquals(160f, moved.handleX(2), 1e-2f)
        assertEquals(100f, moved.handleY(2), 1e-2f)
    }

    @Test
    fun `dragging a rectangle corner pins the opposite corner`() {
        val rect = ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = false)
        val pinnedBefore = FloatArray(2).also { rect.handleInto(0, it) }
        val moved = rect.moveHandle(2, 200f, 140f)

        val pinnedAfter = FloatArray(2).also { moved.handleInto(0, it) }
        assertEquals(pinnedBefore[0], pinnedAfter[0], 1e-3f)
        assertEquals(pinnedBefore[1], pinnedAfter[1], 1e-3f)
        assertEquals(200f, moved.handleX(2), 1e-3f)
        assertEquals(140f, moved.handleY(2), 1e-3f)
    }

    @Test
    fun `a square stays square however its corner is dragged`() {
        // equilateral records what the user drew, not what the numbers currently say, so adjusting
        // a square must not quietly turn it into an oblong.
        val square = ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = true)
        val moved = square.moveHandle(2, 220f, 130f) as ShapeSpec.Rect
        assertEquals(moved.hw, moved.hh, 1e-3f)
        assertTrue(moved.equilateral)
    }

    @Test
    fun `dragging a rotated rectangle keeps its rotation`() {
        val rect = ShapeSpec.Rect(100f, 100f, 50f, 30f, 0.5f)
        val moved = rect.moveHandle(1, 180f, 60f) as ShapeSpec.Rect
        assertEquals(0.5f, moved.rot, 1e-6f)
        assertEquals(180f, moved.handleX(1), 1e-3f)
    }

    @Test
    fun `a circle dragged by its rim stays circular and passes through both points`() {
        val circle = ShapeSpec.Ellipse(100f, 100f, 40f, 40f, 0f, equilateral = true)
        val pinned = FloatArray(2).also { circle.handleInto(2, it) }
        val moved = circle.moveHandle(0, 190f, 100f) as ShapeSpec.Ellipse

        assertTrue(moved.equilateral)
        assertEquals(moved.rx, moved.ry, 1e-3f)
        assertEquals(moved.rx, hypot(190f - moved.cx, 100f - moved.cy), 1e-2f)
        assertEquals(moved.rx, hypot(pinned[0] - moved.cx, pinned[1] - moved.cy), 1e-2f)
    }

    @Test
    fun `dragging one axis of an ellipse leaves the other alone`() {
        // Handle 0 sits at (160, 100) and its opposite at (40, 100). Dragging it out to x = 200
        // stretches that axis between the pen and the pinned point, so the ellipse spans 40..200.
        val ellipse = ShapeSpec.Ellipse(100f, 100f, 60f, 30f, 0f, equilateral = false)
        val moved = ellipse.moveHandle(0, 200f, 100f) as ShapeSpec.Ellipse
        assertEquals("the untouched axis", 30f, moved.ry, 1e-3f)
        assertEquals(80f, moved.rx, 1e-3f)
        assertEquals(120f, moved.cx, 1e-3f)
        assertEquals(200f, moved.handleX(0), 1e-3f)
    }

    @Test
    fun `dragging a line moves only the end that was grabbed`() {
        val line = ShapeSpec.Line(0f, 0f, 100f, 0f)
        val moved = line.moveHandle(1, 40f, 70f) as ShapeSpec.Line
        assertEquals(0f, moved.x0, 0f)
        assertEquals(0f, moved.y0, 0f)
        assertEquals(40f, moved.x1, 0f)
        assertEquals(70f, moved.y1, 0f)
    }

    @Test
    fun `a shape cannot be collapsed to nothing`() {
        // The pen can and will land exactly on the centre; every downstream user of these numbers
        // divides by them at some point.
        val pentagon = ShapeSpec.Ngon(100f, 100f, 40f, 0f, 5)
        val collapsed = pentagon.moveHandle(0, 100f, 100f) as ShapeSpec.Ngon
        assertTrue(collapsed.r > 0f)

        val square = ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = true)
        val flattened = square.moveHandle(2, 50f, 50f) as ShapeSpec.Rect
        assertTrue(flattened.hw > 0f && flattened.hh > 0f)
    }

    @Test
    fun `the handle nearest the pen is the one it is resting on`() {
        // A closed stroke ends where it began, so this reliably picks the corner drawn last.
        val hexagon = ShapeSpec.Ngon(100f, 100f, 50f, 0f, 6)
        val third = FloatArray(2).also { hexagon.handleInto(3, it) }
        assertEquals(3, hexagon.nearestHandle(third[0] + 2f, third[1] - 1f))
    }

    @Test
    fun `every handle is a point on the outline`() {
        // Handles and outline have to agree, or the apex the user grabs is not the apex that moves.
        for (spec in listOf(
            ShapeSpec.Rect(100f, 100f, 50f, 30f, 0.4f),
            ShapeSpec.Ngon(100f, 100f, 60f, 0.2f, 8),
            ShapeSpec.Poly(floatArrayOf(0f, 90f, 40f), floatArrayOf(0f, 10f, 80f)),
        )) {
            val outline = StrokeOutline()
            spec.outlineInto(outline)
            val p = FloatArray(2)
            for (h in 0 until spec.handleCount()) {
                spec.handleInto(h, p)
                val onOutline = (0 until outline.count).any {
                    abs(outline.x(it) - p[0]) < 1e-3f && abs(outline.y(it) - p[1]) < 1e-3f
                }
                assertTrue("$spec handle $h is not on its own outline", onOutline)
            }
        }
    }

    @Test
    fun `a rotated regular polygon puts its first vertex where the rotation says`() {
        val ngon = ShapeSpec.Ngon(0f, 0f, 10f, (PI / 2).toFloat(), 4)
        assertEquals(0f, ngon.handleX(0), 1e-3f)
        assertEquals(10f, ngon.handleY(0), 1e-3f)
    }
}
