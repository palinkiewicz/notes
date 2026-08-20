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
    fun `a shear drops the description rather than recording a lie`() {
        // Every shape here is defined by extents and one angle, so a sheared square is a
        // parallelogram none of them can express. Leaving the stroke's ink alone and forgetting
        // what it was is honest; claiming it is still a square is not.
        val square = ShapeSpec.Rect(0f, 0f, 10f, 10f, 0f, equilateral = true)
        assertNull(square.transformedBy(Affine(1f, 0f, 0.5f, 1f, 0f, 0f)))
    }

    @Test
    fun `a non-uniform scale drops the description`() {
        val circle = ShapeSpec.Ellipse(0f, 0f, 10f, 10f, 0f, equilateral = true)
        assertNull(circle.transformedBy(Affine.scale(2f, 1f)))
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
