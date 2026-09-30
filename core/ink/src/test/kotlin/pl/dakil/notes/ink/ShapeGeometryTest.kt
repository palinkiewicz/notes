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
        assertTrue("unfilled unless asked", !stroke.filled)
    }

    @Test
    fun `a snapped shape drawn with a filling pen commits filled`() {
        val spec = ShapeSpec.Ellipse(100f, 100f, 40f, 25f, 0f, equilateral = false)
        val stroke = spec.toStroke(ToolId.PEN, -1, 2f, BlendId.NORMAL, filled = true)
        assertTrue(stroke.filled)
        assertEquals("the fill is metadata, not geometry", spec, stroke.shape)
    }

    // ---- Handles -------------------------------------------------------------------------------

    @Test
    fun `dragging a regular polygon by a vertex spins and resizes it about a fixed centre`() {
        // The gesture that makes the feature feel direct: the vertex the user is holding stays
        // under the pen, so the polygon follows the hand rather than merely growing.
        val pentagon = ShapeSpec.Ngon(100f, 100f, 40f, 0f, 5)
        val moved = pentagon.dragHandle(2, 160f, 100f).spec as ShapeSpec.Ngon

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
        val moved = rect.dragHandle(2, 200f, 140f).spec

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
        val moved = square.dragHandle(2, 220f, 130f).spec as ShapeSpec.Rect
        assertEquals(moved.hw, moved.hh, 1e-3f)
        assertTrue(moved.equilateral)
    }

    @Test
    fun `dragging a rotated rectangle keeps its rotation`() {
        val rect = ShapeSpec.Rect(100f, 100f, 50f, 30f, 0.5f)
        val moved = rect.dragHandle(1, 180f, 60f).spec as ShapeSpec.Rect
        assertEquals(0.5f, moved.rot, 1e-6f)
        assertEquals(180f, moved.handleX(1), 1e-3f)
    }

    @Test
    fun `a circle dragged by its rim stays circular and passes through both points`() {
        val circle = ShapeSpec.Ellipse(100f, 100f, 40f, 40f, 0f, equilateral = true)
        val pinned = FloatArray(2).also { circle.handleInto(2, it) }
        val moved = circle.dragHandle(0, 190f, 100f).spec as ShapeSpec.Ellipse

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
        val moved = ellipse.dragHandle(0, 200f, 100f).spec as ShapeSpec.Ellipse
        assertEquals("the untouched axis", 30f, moved.ry, 1e-3f)
        assertEquals(80f, moved.rx, 1e-3f)
        assertEquals(120f, moved.cx, 1e-3f)
        assertEquals(200f, moved.handleX(0), 1e-3f)
    }

    @Test
    fun `dragging a line moves only the end that was grabbed`() {
        val line = ShapeSpec.Line(0f, 0f, 100f, 0f)
        val moved = line.dragHandle(1, 40f, 70f).spec as ShapeSpec.Line
        assertEquals(0f, moved.x0, 0f)
        assertEquals(0f, moved.y0, 0f)
        assertEquals(40f, moved.x1, 0f)
        assertEquals(70f, moved.y1, 0f)
    }

    @Test
    fun `a rectangle dragged past the corner it pins mirrors about that corner`() {
        // The gesture a rubber band would make: pull the top-left corner out beyond the right edge
        // and the shape turns over, so the old top-right corner is the new top-left. The apex the
        // user is holding is then a different one — handle 1, not 0 — and saying so is what keeps
        // the *same* corner pinned for the rest of the drag.
        val rect = ShapeSpec.Rect(100f, 100f, 50f, 25f, 0f)
        val pinned = FloatArray(2).also { rect.handleInto(2, it) }
        val dragged = rect.dragHandle(0, 250f, 60f)

        assertEquals("the pen is on the other top corner now", 1, dragged.handle)
        assertEquals(250f, dragged.spec.handleX(1), 1e-3f)
        assertEquals(60f, dragged.spec.handleY(1), 1e-3f)
        // Still hinged on the corner the user was not touching.
        assertEquals(pinned[0], dragged.spec.handleX(3), 1e-3f)
        assertEquals(pinned[1], dragged.spec.handleY(3), 1e-3f)
    }

    @Test
    fun `a square that cannot follow the pen still hands over the corner the pen is nearest to`() {
        // A square dragged off its own diagonal leaves every corner some way from the pen, so which
        // one the user is holding cannot be read off by proximity: straight out along one axis, the
        // nearest corner is an adjacent one, and pinning its opposite would fold the square away
        // from the hand. Which side of the pinned corner the pen is on, per axis, is the answer.
        val square = ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = true)
        val pinned = FloatArray(2).also { square.handleInto(2, it) }
        val dragged = square.dragHandle(0, 260f, 150f)

        assertEquals(2, dragged.handle)
        assertEquals(pinned[0], dragged.spec.handleX(0), 1e-3f)
        assertEquals(pinned[1], dragged.spec.handleY(0), 1e-3f)
        val moved = dragged.spec as ShapeSpec.Rect
        assertEquals(moved.hw, moved.hh, 1e-3f)
    }

    @Test
    fun `an ellipse axis dragged through its far end turns the ellipse over`() {
        // Handle 0 sits at (160, 100) and its opposite at (40, 100); dragging it out to x = 0 puts
        // the pen past the pinned end, so the axis now runs the other way and the pen is holding
        // handle 2.
        val ellipse = ShapeSpec.Ellipse(100f, 100f, 60f, 30f, 0f)
        val dragged = ellipse.dragHandle(0, 0f, 100f)

        assertEquals(2, dragged.handle)
        assertEquals(0f, dragged.spec.handleX(2), 1e-3f)
        assertEquals("the untouched axis", 30f, (dragged.spec as ShapeSpec.Ellipse).ry, 1e-3f)
        assertEquals("still pinned to where the other end was", 40f, dragged.spec.handleX(0), 1e-3f)
    }

    @Test
    fun `a circle dragged sideways keeps the pen on its rim handle`() {
        // A circle's axes are invisible, so they are turned to face the pen. Leaving them alone
        // instead leaves the handle pointing off in the old direction, and the point it pins walks
        // round the rim a little further with every sample — a circle that slides away from the
        // hand for no reason the user can see.
        val circle = ShapeSpec.Ellipse(100f, 100f, 40f, 40f, 0f, equilateral = true)
        val dragged = circle.dragHandle(0, 100f, 180f)

        assertEquals(0, dragged.handle)
        assertEquals(100f, dragged.spec.handleX(0), 1e-2f)
        assertEquals(180f, dragged.spec.handleY(0), 1e-2f)
        assertEquals(60f, dragged.spec.handleX(2), 1e-2f)
        assertEquals(100f, dragged.spec.handleY(2), 1e-2f)
    }

    @Test
    fun `wherever a drag wanders, the point it started out pinning stays put`() {
        // Each sample rebuilds the shape from the point opposite the handle being held, so an index
        // that stops naming the apex under the pen quietly re-pins the shape to something else, and
        // it lurches about instead of following the hand. This is that gesture, sample by sample,
        // crossing the pinned point on every axis.
        // Clear of the pinned points themselves: a shape squashed flat on to one is held open at
        // MIN_EXTENT, which is a separate rule and moves the pin by half a point.
        val path = listOf(220f to 60f, 20f to 44f, 30f to 220f, 260f to 240f, 90f to 30f)
        for ((spec, grabbed) in listOf(
            ShapeSpec.Rect(100f, 100f, 50f, 25f, 0f) to 0,
            ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = true) to 0,
            ShapeSpec.Rect(100f, 100f, 50f, 25f, 0.7f) to 1,
            ShapeSpec.Ellipse(100f, 100f, 60f, 30f, 0f) to 0,
            ShapeSpec.Ellipse(100f, 100f, 40f, 40f, 0f, equilateral = true) to 0,
        )) {
            val pinned = FloatArray(2).also { spec.handleInto((grabbed + 2) % 4, it) }
            var live = DraggedShape(spec, grabbed)
            for ((x, y) in path) live = live.spec.dragHandle(live.handle, x, y)

            val opposite = (live.handle + 2) % 4
            assertEquals("$spec x", pinned[0], live.spec.handleX(opposite), 1e-2f)
            assertEquals("$spec y", pinned[1], live.spec.handleY(opposite), 1e-2f)
        }
    }

    // ---- Arcs ----------------------------------------------------------------------------------

    @Test
    fun `an arc outline runs from one end to the other through the crown`() {
        val arc = ShapeSpec.Arc(100f, 100f, 60f, 0f, (PI / 2).toFloat())
        val outline = StrokeOutline()
        arc.outlineInto(outline)
        val p = FloatArray(2)
        arc.handleInto(0, p)
        assertEquals(p[0], outline.x(0), 1e-3f)
        assertEquals(p[1], outline.y(0), 1e-3f)
        arc.handleInto(2, p)
        assertEquals(p[0], outline.x(outline.count - 1), 1e-3f)
        assertEquals(p[1], outline.y(outline.count - 1), 1e-3f)
        // Open, unlike every other shape: the ends must not be joined up.
        assertTrue("an arc must not close", hypot(outline.x(0) - p[0], outline.y(0) - p[1]) > 1f)
    }

    @Test
    fun `dragging an arc by its crown deepens the bow and leaves the ends alone`() {
        val arc = ShapeSpec.Arc(100f, 100f, 50f, PI.toFloat(), (PI / 2).toFloat())
        val ends = endsOf(arc)
        val deeper =
            arc.dragHandle(1, arc.handleX(1) + 20f, arc.handleY(1) + 20f).spec as ShapeSpec.Arc
        val moved = endsOf(deeper)
        for (i in 0 until 4) assertEquals("end $i", ends[i], moved[i], 1e-2f)
        assertTrue("the bow should have changed", abs(deeper.sweep - arc.sweep) > 0.1f)
    }

    @Test
    fun `sliding the crown along the chord does nothing, because a bow has only depth`() {
        val arc = ShapeSpec.Arc(100f, 100f, 50f, 0f, 1.4f)
        val ends = endsOf(arc)
        // Straight along the chord from the crown: no component across it, so nothing to change.
        val along = hypot(ends[2] - ends[0], ends[3] - ends[1])
        val same = arc.dragHandle(
            1,
            arc.handleX(1) + (ends[2] - ends[0]) / along * 15f,
            arc.handleY(1) + (ends[3] - ends[1]) / along * 15f,
        ).spec as ShapeSpec.Arc
        assertEquals(arc.r, same.r, 1e-2f)
        assertEquals(arc.sweep, same.sweep, 1e-3f)
    }

    @Test
    fun `stretching an arc by an end scales its bow instead of flattening it`() {
        // The alternative — holding the crown still — turns every resize into a straightening,
        // and the user has to re-bow the curve after every adjustment.
        val arc = ShapeSpec.Arc(100f, 100f, 50f, 0f, 1.2f)
        val ends = endsOf(arc)
        val stretched = arc.dragHandle(
            2,
            ends[0] + (ends[2] - ends[0]) * 2f,
            ends[1] + (ends[3] - ends[1]) * 2f,
        ).spec as ShapeSpec.Arc
        assertEquals("the same curve, twice the size", arc.sweep, stretched.sweep, 0.02f)
        assertEquals(arc.r * 2f, stretched.r, 1f)
    }

    @Test
    fun `an arc dragged flat stays an arc rather than losing a handle`() {
        // A shape that changed its handle count mid-drag would leave the controller holding an
        // index into something else, and the pen would jump to a different part of the shape.
        val arc = ShapeSpec.Arc(100f, 100f, 50f, 0f, 1.2f)
        val ends = endsOf(arc)
        val flat = arc.dragHandle(1, (ends[0] + ends[2]) / 2f, (ends[1] + ends[3]) / 2f).spec
        assertTrue("got $flat", flat is ShapeSpec.Arc)
        assertEquals(3, flat.handleCount())
    }

    @Test
    fun `an arc drawn the other way round is the mirror of the first, not the same shape`() {
        val up = arcFromChord(0f, 0f, 100f, 0f, 30f)
        val down = arcFromChord(0f, 0f, 100f, 0f, -30f)
        assertEquals(up.r, down.r, 1e-3f)
        assertEquals(-up.sweep, down.sweep, 1e-3f)
        assertEquals(30f, up.handleY(1), 1e-2f)
        assertEquals(-30f, down.handleY(1), 1e-2f)
    }

    @Test
    fun `a bow deeper than half its chord is the major arc, running the long way round`() {
        // Past a semicircle the crown is on the far side of the centre from the chord, and taking
        // the short way round would silently mirror the shape the user drew.
        val major = arcFromChord(0f, 0f, 100f, 0f, 140f)
        assertTrue("${major.sweep}", abs(major.sweep) > PI.toFloat())
        assertEquals(140f, major.handleY(1), 1e-2f)
        assertEquals(0f, major.handleX(0), 1e-2f)
        assertEquals(100f, major.handleX(2), 1e-2f)
    }

    private fun endsOf(arc: ShapeSpec.Arc): FloatArray {
        val p = FloatArray(2)
        arc.handleInto(0, p)
        val out = floatArrayOf(p[0], p[1], 0f, 0f)
        arc.handleInto(2, p)
        out[2] = p[0]
        out[3] = p[1]
        return out
    }

    @Test
    fun `a shape cannot be collapsed to nothing`() {
        // The pen can and will land exactly on the centre; every downstream user of these numbers
        // divides by them at some point.
        val pentagon = ShapeSpec.Ngon(100f, 100f, 40f, 0f, 5)
        val collapsed = pentagon.dragHandle(0, 100f, 100f).spec as ShapeSpec.Ngon
        assertTrue(collapsed.r > 0f)

        val square = ShapeSpec.Rect(100f, 100f, 50f, 50f, 0f, equilateral = true)
        val flattened = square.dragHandle(2, 50f, 50f).spec as ShapeSpec.Rect
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
            ShapeSpec.Arc(100f, 100f, 60f, 0.3f, 2.2f),
            ShapeSpec.Arc(100f, 100f, 60f, -1f, -4.4f),
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
