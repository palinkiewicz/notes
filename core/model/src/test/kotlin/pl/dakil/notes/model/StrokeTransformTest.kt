package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StrokeTransformTest {

    /** A tap, as StrokeBuilder commits one: two points a hundredth of a point apart. */
    private fun dot(width: Float = 4f) = Stroke(
        ToolId.PEN, 0xFF000000.toInt(), width, BlendId.NORMAL,
        floatArrayOf(100f, 100.01f), floatArrayOf(50f, 50f),
    )

    @Test
    fun `a dot stretched along one axis comes out long that way and as thick as before`() {
        // What the edge grips preview: an oval three times as wide as it is tall. A stroke has one
        // width, so without this the dot came out a circle of the averaged size instead.
        val stretched = dot().transformed(Affine.scale(3f, 1f, pivotX = 100f, pivotY = 50f))
        assertEquals(4f, stretched.width, 1e-3f)
        val inked = stretched.bounds.inflate(-0.5f)
        assertEquals(12f, inked.width, 0.05f)
        assertEquals(4f, inked.height, 0.05f)
    }

    @Test
    fun `a dot stretched downwards lies along the page's height`() {
        val stretched = dot().transformed(Affine.scale(1f, 2f, pivotX = 100f, pivotY = 50f))
        val inked = stretched.bounds.inflate(-0.5f)
        assertEquals(4f, inked.width, 0.05f)
        assertEquals(8f, inked.height, 0.05f)
        // Stretched about its own centre, so it stays where it was.
        assertEquals(50f, (inked.top + inked.bottom) / 2f, 0.05f)
    }

    @Test
    fun `an even scale leaves a dot to the ordinary rule`() {
        // Nothing to lengthen: a scaled circle is a circle, and the width alone says so.
        val scaled = dot().transformed(Affine.scale(2f, 2f))
        assertEquals(8f, scaled.width, 1e-3f)
        assertEquals(2, scaled.pointCount)
    }

    @Test
    fun `a stroke longer than its own width is never mistaken for a dot`() {
        val line = Stroke(
            ToolId.PEN, 0, 4f, BlendId.NORMAL, floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 0f, 0f),
        )
        val stretched = line.transformed(Affine.scale(1f, 3f))
        assertEquals(3, stretched.pointCount)
        assertSame(line.widthFactors, stretched.widthFactors)
    }
}
