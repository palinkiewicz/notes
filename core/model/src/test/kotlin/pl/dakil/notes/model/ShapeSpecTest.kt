package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ShapeSpecTest {

    @Test
    fun `a moved and scaled shape stays the same kind of shape`() {
        val pentagon = ShapeSpec.Ngon(100f, 100f, 40f, 0f, 5)
        val moved = pentagon.transformedBy(
            Affine.scale(2f, 2f).then(Affine.translate(10f, 20f))
        ) as ShapeSpec.Ngon

        assertEquals(5, moved.sides)
        assertEquals(80f, moved.r, 1e-3f)
    }

    @Test
    fun `rotating a shape rotates its own angle with it`() {
        val rect = ShapeSpec.Rect(0f, 0f, 30f, 10f, 0f)
        val turned = rect.transformedBy(Affine.rotate((PI / 2).toFloat())) as ShapeSpec.Rect
        assertEquals((PI / 2).toFloat(), turned.rot, 1e-4f)
        assertEquals("extents are unchanged by a rotation", 30f, turned.hw, 1e-3f)
    }

    @Test
    fun `an arc keeps its sweep through a rotation and takes the turn on its start`() {
        // Sweep is how far round the pen went, which no similarity changes; where it started from
        // is an angle like any other and turns with the shape.
        val arc = ShapeSpec.Arc(0f, 0f, 40f, 0f, 1.2f)
        val turned = arc.transformedBy(
            Affine.rotate((PI / 2).toFloat()).then(Affine.scale(3f, 3f))
        ) as ShapeSpec.Arc
        assertEquals(1.2f, turned.sweep, 1e-4f)
        assertEquals((PI / 2).toFloat(), turned.start, 1e-4f)
        assertEquals(120f, turned.r, 1e-3f)
    }

    @Test
    fun `a shear drops the description rather than recording a lie`() {
        // A sheared circle is an ellipse at an angle its own parameters do not give. Leaving the
        // stroke's ink alone and forgetting what it was is honest; claiming it is still a circle is not.
        val circle = ShapeSpec.Ellipse(0f, 0f, 10f, 10f, 0f, equilateral = true)
        assertNull(circle.transformedBy(Affine(1f, 0f, 0.5f, 1f, 0f, 0f)))
    }

    @Test
    fun `a sheared square is the parallelogram it now is, not a square`() {
        val square = ShapeSpec.Rect(0f, 0f, 10f, 10f, 0f, equilateral = true)
        val sheared = square.transformedBy(Affine(1f, 0f, 0.5f, 1f, 0f, 0f)) as ShapeSpec.Poly
        assertEquals(4, sheared.vertexCount)
    }

    @Test
    fun `a stretch across a turned ellipse drops the description`() {
        // Stretched along the page, an ellipse at 30° is an ellipse at some other angle with other
        // axes, which nothing here works out; the ink is right and the spec would not be.
        val ellipse = ShapeSpec.Ellipse(0f, 0f, 20f, 10f, 0.5f)
        assertNull(ellipse.transformedBy(Affine.scale(2f, 1f)))
    }

    @Test
    fun `a circle stretched along the page becomes an ellipse rather than plain ink`() {
        // What the selection's edge grips do. Losing the handles because the user widened a circle
        // would be a strange price for a perfectly describable result.
        val circle = ShapeSpec.Ellipse(0f, 0f, 10f, 10f, 0f, equilateral = true)
        val wide = circle.transformedBy(Affine.scale(2f, 1f)) as ShapeSpec.Ellipse
        assertEquals(20f, wide.rx, 1e-3f)
        assertEquals(10f, wide.ry, 1e-3f)
        assertTrue("no longer a circle, so it must not snap back to one", !wide.equilateral)
    }

    @Test
    fun `a rectangle turned a quarter takes the stretch on its other side`() {
        // Its own width lies along the page's height, so a vertical stretch lengthens hw.
        val rect = ShapeSpec.Rect(0f, 0f, 30f, 10f, (PI / 2).toFloat())
        val tall = rect.transformedBy(Affine.scale(1f, 2f)) as ShapeSpec.Rect
        assertEquals(60f, tall.hw, 1e-3f)
        assertEquals(10f, tall.hh, 1e-3f)
    }

    @Test
    fun `a turned rectangle stretched along the page is still four editable corners`() {
        val rect = ShapeSpec.Rect(0f, 0f, 30f, 10f, 0.4f)
        val m = Affine.scale(2f, 1f)
        val poly = rect.transformedBy(m) as ShapeSpec.Poly
        assertEquals(4, poly.vertexCount)
        // The first corner is the one the rectangle's own first handle stood on, carried by m.
        val x = -30f * kotlin.math.cos(0.4f) + 10f * kotlin.math.sin(0.4f)
        assertEquals(2f * x, poly.xs[0], 1e-3f)
    }

    @Test
    fun `a stretched regular polygon becomes an irregular one with the same vertices`() {
        val triangle = ShapeSpec.Ngon(0f, 0f, 10f, 0f, 3)
        val poly = triangle.transformedBy(Affine.scale(1f, 3f)) as ShapeSpec.Poly
        assertEquals(3, poly.vertexCount)
        assertEquals(10f, poly.xs[0], 1e-3f)
        assertEquals(0f, poly.ys[0], 1e-3f)
    }

    @Test
    fun `lines and polygons follow even a shear, being nothing but their points`() {
        val line = ShapeSpec.Line(0f, 0f, 10f, 10f)
        val sheared = line.transformedBy(Affine(1f, 0f, 0.5f, 1f, 0f, 0f)) as ShapeSpec.Line
        assertEquals(15f, sheared.x1, 1e-3f)
    }

    @Test
    fun `an arc stretched along the page drops the description`() {
        // An Arc is circular by design; stretched it would be elliptical, which it cannot say.
        assertNull(ShapeSpec.Arc(0f, 0f, 10f, 0f, 1f).transformedBy(Affine.scale(2f, 1f)))
    }

    private val mirrorX = Affine.scale(-1f, 1f)
    private val mirrorY = Affine.scale(1f, -1f)

    @Test
    fun `a mirrored arc runs the other way from the mirrored start`() {
        // Left-right: a direction θ comes out at π − θ, and the pen went round the other way.
        val arc = ShapeSpec.Arc(10f, 0f, 40f, 0.3f, 1.2f)
        val flipped = arc.transformedBy(mirrorX) as ShapeSpec.Arc
        assertEquals(-10f, flipped.cx, 1e-4f)
        assertEquals((PI - 0.3).toFloat(), flipped.start, 1e-4f)
        assertEquals(-1.2f, flipped.sweep, 1e-4f)
        assertEquals(40f, flipped.r, 1e-4f)
    }

    @Test
    fun `a mirrored turned rectangle turns the other way`() {
        val rect = ShapeSpec.Rect(5f, 5f, 30f, 10f, 0.4f, equilateral = false)
        val flipped = rect.transformedBy(mirrorY) as ShapeSpec.Rect
        assertEquals(-0.4f, flipped.rot, 1e-4f)
        assertEquals(-5f, flipped.cy, 1e-4f)
        assertEquals(30f, flipped.hw, 1e-4f)
    }

    @Test
    fun `a mirrored triangle points the other way`() {
        // Its first vertex points right; mirrored left-right it has to point left.
        val triangle = ShapeSpec.Ngon(0f, 0f, 10f, 0f, 3)
        val flipped = triangle.transformedBy(mirrorX) as ShapeSpec.Ngon
        assertEquals(-10f, 10f * kotlin.math.cos(flipped.rot), 1e-3f)
    }

    @Test
    fun `a mirrored scaled ellipse keeps both its axes`() {
        val ellipse = ShapeSpec.Ellipse(0f, 0f, 20f, 10f, 0.7f)
        val flipped = ellipse.transformedBy(Affine.scale(-2f, 2f)) as ShapeSpec.Ellipse
        assertEquals(40f, flipped.rx, 1e-3f)
        assertEquals(20f, flipped.ry, 1e-3f)
        assertEquals((PI - 0.7).toFloat(), flipped.rot, 1e-4f)
    }

    @Test
    fun `a stroke carries its shape through a recolour but not through a cut`() {
        val spec = ShapeSpec.Rect(50f, 50f, 50f, 50f, 0f, equilateral = true)
        val stroke = Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            floatArrayOf(0f, 100f, 100f, 0f, 0f),
            floatArrayOf(0f, 0f, 100f, 100f, 0f),
            shape = spec,
        )
        assertEquals(spec, stroke.withStyle(color = 0xFF00FF00.toInt()).shape)

        // Half a square is not a square.
        val pieces = stroke.clippedToBand(-10f, 50f)
        assertTrue(pieces.isNotEmpty())
        for (piece in pieces) assertNull(piece.shape)
    }

    @Test
    fun `a stroke wholly inside the band keeps its shape, because nothing was cut`() {
        val spec = ShapeSpec.Line(0f, 10f, 100f, 20f)
        val stroke = Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            floatArrayOf(0f, 100f), floatArrayOf(10f, 20f), shape = spec,
        )
        assertSame(stroke, stroke.clippedToBand(0f, 900f).single())
    }
}
