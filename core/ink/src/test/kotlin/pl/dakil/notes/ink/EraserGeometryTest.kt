package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId

class EraserGeometryTest {

    /** A horizontal line from (0,0) to (100,0), sampled every point. */
    private fun line(width: Float = 2f, n: Int = 101): Stroke = Stroke(
        ToolId.PEN, -1, width, BlendId.NORMAL,
        FloatArray(n) { it.toFloat() },
        FloatArray(n),
    )

    // ---- Point eraser --------------------------------------------------------------------------

    @Test
    fun `erasing the middle of a line leaves two independent fragments`() {
        // The behaviour that makes a vector eraser feel real: a rubbed-out middle must leave two
        // separate strokes, not one stroke with a gap and not a whole line disappearing.
        val fragments = PathSplitter.erase(line(), cx = 50f, cy = 0f, radius = 5f)

        assertEquals(2, fragments.size)
        assertTrue(fragments[0].xs.last() < 46f)
        assertTrue(fragments[1].xs.first() > 54f)
        assertEquals(0f, fragments[0].xs.first(), 1e-4f)
        assertEquals(100f, fragments[1].xs.last(), 1e-4f)
    }

    @Test
    fun `erasing an end trims rather than splits`() {
        val fragments = PathSplitter.erase(line(), cx = 0f, cy = 0f, radius = 10f)
        assertEquals(1, fragments.size)
        assertTrue(fragments.single().xs.first() > 10f)
    }

    @Test
    fun `a miss returns the original instance untouched`() {
        // Identity matters: the caller uses it to skip a costly layer rebuild.
        val original = line()
        val result = PathSplitter.erase(original, cx = 50f, cy = 500f, radius = 5f)
        assertSame(original, result.single())
    }

    @Test
    fun `covering the whole stroke removes it`() {
        assertTrue(PathSplitter.erase(line(), cx = 50f, cy = 0f, radius = 200f).isEmpty())
    }

    @Test
    fun `a wide stroke is bitten into by an eraser that only grazes its edge`() {
        // The eraser cuts the drawn shape, not the invisible centreline: touching the edge of a
        // 40pt highlighter has to take something off it.
        val fat = line(width = 40f)
        val grazing = PathSplitter.erase(fat, cx = 50f, cy = 19f, radius = 2f)
        assertTrue("expected the fat stroke to be cut", grazing.size > 1 || grazing.single().pointCount < fat.pointCount)
    }

    @Test
    fun `fragments inherit style and per-point data`() {
        val n = 101
        val original = Stroke(
            ToolId.HIGHLIGHTER, 0x66FFE14D, 12f, BlendId.MULTIPLY,
            FloatArray(n) { it.toFloat() }, FloatArray(n),
            widthFactors = FloatArray(n) { 0.5f + it / 400f },
            times = IntArray(n) { it * 4 },
        )
        val fragments = PathSplitter.erase(original, cx = 50f, cy = 0f, radius = 5f)
        assertEquals(2, fragments.size)
        for (f in fragments) {
            assertEquals(ToolId.HIGHLIGHTER, f.tool)
            assertEquals(BlendId.MULTIPLY, f.blend)
            assertEquals(12f, f.width, 1e-4f)
            assertNotNull(f.widthFactors)
            assertEquals(f.pointCount, f.widthFactors!!.size)
            assertEquals(f.pointCount, f.times!!.size)
        }
        // The second fragment must carry the later part of the profile, not a copy of the first.
        assertTrue(fragments[1].widthFactors!!.first() > fragments[0].widthFactors!!.first())
    }

    @Test
    fun `single orphaned points are dropped rather than left as specks`() {
        val stroke = Stroke(
            ToolId.PEN, -1, 1f, BlendId.NORMAL,
            floatArrayOf(0f, 50f, 100f), floatArrayOf(0f, 0f, 0f),
        )
        // A disc over the middle leaves one isolated point on each side.
        val fragments = PathSplitter.erase(stroke, cx = 50f, cy = 0f, radius = 5f)
        assertTrue(fragments.isEmpty())
    }

    @Test
    fun `sweeping the eraser erases the whole swept path`() {
        // Sampling only at frame positions makes a fast swipe skip; the swept capsule fixes it.
        val fragments = PathSplitter.eraseAlongSegment(line(), 20f, 0f, 80f, 0f, radius = 3f)
        assertEquals(2, fragments.size)
        assertTrue(fragments[0].xs.last() < 20f)
        assertTrue(fragments[1].xs.first() > 80f)
    }

    // ---- Stroke eraser and hit testing ----------------------------------------------------------

    @Test
    fun `the stroke eraser reports only the strokes it crosses`() {
        val strokes = listOf(
            line(),
            Stroke(ToolId.PEN, -1, 2f, BlendId.NORMAL, floatArrayOf(0f, 100f), floatArrayOf(200f, 200f)),
        )
        val hits = HitTester.strokesTouchedBySegment(strokes, 50f, -10f, 50f, 10f, radius = 1f)
        assertEquals(listOf(0), hits)
    }

    @Test
    fun `hit testing accounts for stroke width`() {
        val fat = listOf(line(width = 30f))
        // 12pt away from the centreline is outside a thin line but inside a 30pt one.
        assertEquals(listOf(0), HitTester.strokesTouchedBySegment(fat, 50f, 12f, 55f, 12f, radius = 1f))
        assertTrue(HitTester.strokesTouchedBySegment(listOf(line(width = 2f)), 50f, 12f, 55f, 12f, 1f).isEmpty())
    }

    // ---- Lasso ---------------------------------------------------------------------------------

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float) =
        floatArrayOf(x0, x1, x1, x0) to floatArrayOf(y0, y0, y1, y1)

    @Test
    fun `a lasso selects only strokes it fully encloses`() {
        val strokes = listOf(
            Stroke(ToolId.PEN, -1, 1f, BlendId.NORMAL, floatArrayOf(10f, 20f), floatArrayOf(10f, 20f)),
            Stroke(ToolId.PEN, -1, 1f, BlendId.NORMAL, floatArrayOf(10f, 900f), floatArrayOf(10f, 900f)),
        )
        val (px, py) = box(0f, 0f, 100f, 100f)
        assertEquals(listOf(0), HitTester.strokesInPolygon(strokes, px, py, 4))
    }

    @Test
    fun `partial selection is available for people who want it`() {
        // Full containment is the default because a loop around a word should not swallow the line
        // above it, but the looser rule is one flag away.
        val strokes = listOf(
            Stroke(ToolId.PEN, -1, 1f, BlendId.NORMAL, floatArrayOf(50f, 900f), floatArrayOf(50f, 900f)),
        )
        val (px, py) = box(0f, 0f, 100f, 100f)
        assertTrue(HitTester.strokesInPolygon(strokes, px, py, 4).isEmpty())
        assertEquals(
            listOf(0),
            HitTester.strokesInPolygon(strokes, px, py, 4, requireFullyInside = false),
        )
    }

    @Test
    fun `a degenerate lasso selects nothing`() {
        val strokes = listOf(line())
        assertTrue(HitTester.strokesInPolygon(strokes, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f), 2).isEmpty())
    }

    @Test
    fun `point in polygon handles concave shapes`() {
        // A C-shape: the notch must read as outside.
        val xs = floatArrayOf(0f, 100f, 100f, 40f, 40f, 100f, 100f, 0f)
        val ys = floatArrayOf(0f, 0f, 30f, 30f, 70f, 70f, 100f, 100f)
        assertTrue(HitTester.pointInPolygon(20f, 50f, xs, ys, 8))
        assertTrue(!HitTester.pointInPolygon(70f, 50f, xs, ys, 8))
    }
}
