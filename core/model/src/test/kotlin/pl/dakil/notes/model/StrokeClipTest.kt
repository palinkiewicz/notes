package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting a stroke at a page boundary.
 *
 * The primitive every page operation is built on, and the one place where "close enough" is
 * visible: a cut that lands on the nearest sample instead of the boundary leaves a stub hanging
 * over the edge of the paper, or a gap before it.
 */
class StrokeClipTest {

    private fun stroke(vararg ys: Float, factors: FloatArray? = null) = Stroke(
        ToolId.PEN, -1, 2f, BlendId.NORMAL,
        xs = FloatArray(ys.size) { 10f },
        ys = ys.toList().toFloatArray(),
        widthFactors = factors,
    )

    @Test
    fun `a stroke entirely inside the band is kept whole`() {
        val s = stroke(10f, 20f, 30f)
        val pieces = s.clippedToBand(0f, 100f)
        assertEquals(1, pieces.size)
        assertEquals(listOf(10f, 20f, 30f), pieces[0].ys.toList())
    }

    @Test
    fun `a stroke entirely outside the band is dropped`() {
        assertTrue(stroke(200f, 210f).clippedToBand(0f, 100f).isEmpty())
    }

    @Test
    fun `a stroke crossing the boundary is cut exactly at it`() {
        // The half that belongs to the next page must not come along.
        val pieces = stroke(50f, 150f).clippedToBand(0f, 100f)
        assertEquals(1, pieces.size)
        assertEquals(listOf(50f, 100f), pieces[0].ys.toList())
    }

    @Test
    fun `a stroke entering the band is cut at the entry`() {
        val pieces = stroke(-50f, 50f).clippedToBand(0f, 100f)
        assertEquals(listOf(0f, 50f), pieces.single().ys.toList())
    }

    @Test
    fun `a stroke passing straight through yields only the crossing`() {
        val pieces = stroke(-40f, 140f).clippedToBand(0f, 100f)
        assertEquals(listOf(0f, 100f), pieces.single().ys.toList())
    }

    @Test
    fun `a stroke that leaves and returns yields two separate pieces`() {
        // One mark on the page becomes two, because that is what is actually on the page.
        val pieces = stroke(50f, 150f, 60f).clippedToBand(0f, 100f)
        assertEquals(2, pieces.size)
        assertEquals(listOf(50f, 100f), pieces[0].ys.toList())
        assertEquals(listOf(100f, 60f), pieces[1].ys.toList())
    }

    @Test
    fun `the two halves of a cut stroke reassemble into the original span`() {
        val s = stroke(50f, 150f)
        val above = s.clippedToBand(-Float.MAX_VALUE, 100f).single()
        val below = s.clippedToBand(100f, Float.MAX_VALUE).single()

        assertEquals(50f, above.ys.first(), 0.01f)
        assertEquals(100f, above.ys.last(), 0.01f)
        assertEquals(100f, below.ys.first(), 0.01f)
        assertEquals(150f, below.ys.last(), 0.01f)
    }

    @Test
    fun `pressure is interpolated at the cut, not snapped to a sample`() {
        // A tapering stroke must keep its taper through the boundary; taking the neighbouring
        // sample's width would put a visible step exactly on the page edge.
        val s = stroke(0f, 100f, factors = floatArrayOf(1f, 0f))
        val piece = s.clippedToBand(0f, 25f).single()
        assertEquals(0.75f, piece.widthFactors!!.last(), 0.001f)
    }

    @Test
    fun `x is interpolated along the cut segment`() {
        val s = Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            xs = floatArrayOf(0f, 100f), ys = floatArrayOf(0f, 100f),
        )
        assertEquals(40f, s.clippedToBand(0f, 40f).single().xs.last(), 0.01f)
    }

    @Test
    fun `a stroke running along the boundary is not lost`() {
        val pieces = stroke(100f, 100f).clippedToBand(0f, 100f)
        assertEquals(listOf(100f, 100f), pieces.single().ys.toList())
    }

    @Test
    fun `channels absent from the source stay absent in the pieces`() {
        val piece = stroke(50f, 150f).clippedToBand(0f, 100f).single()
        assertEquals(null, piece.widthFactors)
        assertEquals(null, piece.tilts)
    }

    @Test
    fun `a stroke grazing the boundary leaves no offcut behind`() {
        // The residue that made a removed page look half-deleted: a segment that barely crosses
        // leaves a piece of no length on the far side, which renders as a dot.
        val pieces = stroke(99.999f, 500f).clippedToBand(0f, 100f)
        assertTrue("a sub-quantisation offcut is not a mark", pieces.isEmpty())
    }

    @Test
    fun `a deliberate dot inside the band is never mistaken for an offcut`() {
        // A tap is two coincident points and has no extent either, so it has to be recognised by
        // the fact that nothing was cut rather than by its size.
        val dot = stroke(50f, 50f)
        assertEquals(listOf(dot), dot.clippedToBand(0f, 100f))
    }

    @Test
    fun `a cut stroke does not belong to the page it only touches`() {
        // What actually put dots on the page below: bounds are inflated by half the stroke width,
        // so a stroke ending exactly on the boundary overlapped the next page and painted the lower
        // half of its end cap there.
        val cut = stroke(50f, 150f).clippedToBand(0f, 100f).single()

        assertTrue("the inked extent does reach past the boundary", cut.bounds.bottom > 100f)
        assertEquals("but the stroke itself stops at it", 100f, cut.coreBounds.bottom, 0.01f)
    }

    @Test
    fun `a filled stroke stays filled on both sides of a cut`() {
        // Unlike the shape spec, which a cut drops. A page break is presentation catching up with
        // the document: each piece is what that page was already showing, and half a filled circle
        // closed by the cut is the half-disc that was painted there.
        val s = Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            xs = floatArrayOf(0f, 40f, 40f, 0f),
            ys = floatArrayOf(50f, 50f, 150f, 150f),
            filled = true,
        )
        val pieces = s.clippedToBand(0f, 100f)
        assertTrue(pieces.isNotEmpty())
        for (piece in pieces) assertTrue("piece must stay filled", piece.filled)
    }

    @Test
    fun `style is carried onto every piece`() {
        val s = Stroke(
            ToolId.HIGHLIGHTER, 0x66FFE14D, 16f, BlendId.MULTIPLY,
            xs = floatArrayOf(0f, 0f, 0f), ys = floatArrayOf(50f, 150f, 60f),
        )
        for (piece in s.clippedToBand(0f, 100f)) {
            assertEquals(ToolId.HIGHLIGHTER, piece.tool)
            assertEquals(0x66FFE14D, piece.color)
            assertEquals(16f, piece.width, 0.01f)
            assertEquals(BlendId.MULTIPLY, piece.blend)
        }
    }
}
