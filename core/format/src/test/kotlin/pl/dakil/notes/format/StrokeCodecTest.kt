package pl.dakil.notes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import kotlin.math.abs
import kotlin.random.Random

class StrokeCodecTest {

    /** Half a quantisation step is the worst-case rounding error. */
    private val tolerance = 0.5f / StrokeCodec.QUANT

    @Test
    fun `geometry round-trips within the quantisation bound`() {
        val random = Random(20260814)
        val n = 5_000
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var x = 100f
        var y = 200f
        for (i in 0 until n) {
            x += random.nextFloat() * 6f - 3f
            y += random.nextFloat() * 6f - 3f
            xs[i] = x
            ys[i] = y
        }
        val original = Stroke(ToolId.PEN, 0xFF112233.toInt(), 2.5f, BlendId.NORMAL, xs, ys)

        val decoded = StrokeCodec.decode(StrokeCodec.encode(listOf(original))).single()

        assertEquals(n, decoded.pointCount)
        for (i in 0 until n) {
            assertTrue("x[$i] drifted", abs(decoded.xs[i] - xs[i]) <= tolerance)
            assertTrue("y[$i] drifted", abs(decoded.ys[i] - ys[i]) <= tolerance)
        }
    }

    @Test
    fun `style fields round-trip exactly`() {
        val s = Stroke(
            tool = ToolId.HIGHLIGHTER,
            color = 0x80FFE14D.toInt(),
            width = 16.5f,
            blend = BlendId.MULTIPLY,
            xs = floatArrayOf(0f, 10f),
            ys = floatArrayOf(0f, 10f),
        )
        val decoded = StrokeCodec.decode(StrokeCodec.encode(listOf(s))).single()
        assertEquals(ToolId.HIGHLIGHTER, decoded.tool)
        assertEquals(0x80FFE14D.toInt(), decoded.color)
        assertEquals(16.5f, decoded.width, 1e-4f)
        assertEquals(BlendId.MULTIPLY, decoded.blend)
    }

    @Test
    fun `width factors and timestamps survive when present`() {
        val s = Stroke(
            tool = ToolId.PEN, color = -1, width = 2f, blend = BlendId.NORMAL,
            xs = floatArrayOf(0f, 1f, 2f),
            ys = floatArrayOf(0f, 1f, 2f),
            widthFactors = floatArrayOf(0f, 0.5f, 1f),
            times = intArrayOf(0, 8, 21),
        )
        val decoded = StrokeCodec.decode(StrokeCodec.encode(listOf(s))).single()
        // Width factors are stored as a byte, so tolerance is one 255th.
        assertEquals(0f, decoded.widthFactors!![0], 1f / 255f)
        assertEquals(0.5f, decoded.widthFactors!![1], 1f / 255f)
        assertEquals(1f, decoded.widthFactors!![2], 1f / 255f)
        assertEquals(listOf(0, 8, 21), decoded.times!!.toList())
    }

    @Test
    fun `optional channels stay absent when unused`() {
        val s = Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            floatArrayOf(0f, 1f), floatArrayOf(0f, 1f),
        )
        val decoded = StrokeCodec.decode(StrokeCodec.encode(listOf(s))).single()
        assertNull(decoded.widthFactors)
        assertNull(decoded.times)
        assertNull(decoded.tilts)
    }

    @Test
    fun `encoding is compact enough to justify a binary format`() {
        // The whole reason ink is not JSON. A 10k-point stroke at ~20 bytes per point as JSON
        // floats would be ~200 KB; delta-varint must land far below that.
        val n = 10_000
        val xs = FloatArray(n) { 100f + it * 0.7f }
        val ys = FloatArray(n) { 200f + kotlin.math.sin(it / 40f) * 30f }
        val s = Stroke(ToolId.PEN, -1, 2f, BlendId.NORMAL, xs, ys, FloatArray(n) { 0.6f })
        val bytes = StrokeCodec.encode(listOf(s)).size
        val perPoint = bytes.toDouble() / n
        assertTrue("$perPoint bytes/point is above the 5 byte budget", perPoint < 5.0)
    }

    @Test
    fun `an unknown tool ordinal degrades instead of failing`() {
        // A stroke drawn with a tool a future release adds must still load and render.
        val raw = StrokeCodec.encode(
            listOf(Stroke(ToolId.PEN, -1, 2f, BlendId.NORMAL, floatArrayOf(0f), floatArrayOf(0f)))
        )
        // Overwrite the tool ordinal (first byte after magic, flags and stroke count) with 99.
        raw[6] = 99
        assertEquals(ToolId.PEN, StrokeCodec.decode(raw).single().tool)
    }

    @Test
    fun `truncated data yields no strokes rather than an exception`() {
        val full = StrokeCodec.encode(
            listOf(Stroke(ToolId.PEN, -1, 2f, BlendId.NORMAL, FloatArray(100), FloatArray(100)))
        )
        assertEquals(emptyList<Stroke>(), StrokeCodec.readLenient(full.copyOf(full.size / 2)))
    }

    @Test
    fun `an empty stroke list round-trips`() {
        assertEquals(emptyList<Stroke>(), StrokeCodec.decode(StrokeCodec.encode(emptyList())))
    }
}
