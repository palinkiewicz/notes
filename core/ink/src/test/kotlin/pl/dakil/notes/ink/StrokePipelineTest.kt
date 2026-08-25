package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.PressureCurve
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.ToolType
import kotlin.math.abs

class StrokePipelineTest {

    private fun stylus(x: Float, y: Float, t: Long, pressure: Float = 0.5f) = PointerSample(
        x = x, y = y, pressure = pressure, toolType = ToolType.STYLUS, timeMs = t,
    )

    private fun drawLine(
        spec: ToolSpec,
        config: InputConfig = InputConfig(),
        n: Int = 60,
        stepMs: Long = 8,
        pressure: (Int) -> Float = { 0.5f },
    ): Stroke {
        val builder = StrokeBuilder()
        builder.start(spec, config, stylus(0f, 0f, 0, pressure(0)))
        for (i in 1 until n) {
            builder.add(stylus(i * 3f, 0f, i * stepMs, pressure(i)))
        }
        return builder.finish(null)!!
    }

    // ---- Pressure curve ------------------------------------------------------------------------

    @Test
    fun `the linear curve is the identity`() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(p, PressureCurve.LINEAR.apply(p), 1e-3f)
        }
    }

    @Test
    fun `curves are monotone and pinned at both ends`() {
        for (curve in listOf(PressureCurve.LINEAR, PressureCurve.SOFT, PressureCurve.FIRM)) {
            assertEquals(0f, curve.apply(0f), 1e-3f)
            assertEquals(1f, curve.apply(1f), 1e-3f)
            var previous = -1f
            var p = 0f
            while (p <= 1f) {
                val v = curve.apply(p)
                assertTrue("$curve is not monotone at $p", v >= previous - 1e-3f)
                previous = v
                p += 0.02f
            }
        }
    }

    @Test
    fun `soft reaches full width sooner than firm`() {
        // The whole point of exposing the curve: a light hand and a heavy hand need opposite shapes.
        assertTrue(PressureCurve.SOFT.apply(0.3f) > PressureCurve.LINEAR.apply(0.3f))
        assertTrue(PressureCurve.FIRM.apply(0.3f) < PressureCurve.LINEAR.apply(0.3f))
    }

    @Test
    fun `curve input is clamped`() {
        assertEquals(0f, PressureCurve.SOFT.apply(-5f), 1e-3f)
        assertEquals(1f, PressureCurve.SOFT.apply(5f), 1e-3f)
    }

    // ---- One Euro filter -----------------------------------------------------------------------

    @Test
    fun `the first sample passes through unchanged`() {
        // A stroke has to start exactly where the pen touched down, or taps land off-target.
        val filter = OneEuroFilter()
        filter.filter(123.45f, 67.89f, 0f)
        assertEquals(123.45f, filter.x, 1e-4f)
        assertEquals(67.89f, filter.y, 1e-4f)
    }

    @Test
    fun `jitter around a stationary point is attenuated`() {
        val filter = OneEuroFilter.forSmoothing(0.8f)
        var t = 0f
        var output = 0f
        var maxDeviation = 0f
        repeat(80) { i ->
            val noisy = 100f + if (i % 2 == 0) 1.5f else -1.5f
            filter.filter(noisy, 0f, t)
            output = filter.x
            if (i > 20) maxDeviation = maxOf(maxDeviation, abs(output - 100f))
            t += 1f / 120f
        }
        assertTrue("residual jitter $maxDeviation was not attenuated", maxDeviation < 0.75f)
    }

    @Test
    fun `fast movement is not smothered by the filter`() {
        // Adaptivity is the reason for choosing One Euro over a fixed low-pass: a quick flick must
        // not lag behind the pen.
        val filter = OneEuroFilter.forSmoothing(0.8f)
        var t = 0f
        var output = 0f
        repeat(40) { i ->
            filter.filter(i * 40f, 0f, t)
            output = filter.x
            t += 1f / 120f
        }
        val target = 39 * 40f
        assertTrue("output $output lagged too far behind $target", output > target * 0.9f)
    }

    @Test
    fun `less smoothing tracks the input more closely`() {
        fun lag(smoothing: Float): Float {
            val filter = OneEuroFilter.forSmoothing(smoothing)
            var t = 0f
            var out = 0f
            repeat(30) { i ->
                filter.filter(i * 10f, 0f, t)
                out = filter.x
                t += 1f / 120f
            }
            return abs(29 * 10f - out)
        }
        assertTrue(lag(0f) < lag(1f))
    }

    @Test
    fun `a circle drawn at a steady pace does not come out as a rounded square`() {
        // The filter used to run once per axis, each choosing its cutoff from its own speed. At the
        // top of a circle x is at full speed and y is at a standstill, so y — sitting exactly on
        // its turning point — got the heaviest smoothing available and the apex was clipped flat.
        // Four flats and four corners, from an input with no corners in it at all.
        //
        // Measured as roundness: every filtered point should stay near the true radius. Lag shrinks
        // the circle a little, which is fine and even; flattening does not, which is not.
        val filter = OneEuroFilter.forSmoothing(0.65f)
        val radius = 200f
        val samples = 240
        var minRadius = Float.MAX_VALUE
        var maxRadius = 0f

        for (i in 0..samples) {
            val angle = i * 2.0 * Math.PI / samples
            val t = i / 240f
            filter.filter(
                (radius * Math.cos(angle)).toFloat(),
                (radius * Math.sin(angle)).toFloat(),
                t,
            )
            // The first turn settles the filter; measure once it is tracking.
            if (i < samples / 4) continue
            val r = fastDistance(filter.x, filter.y)
            minRadius = minOf(minRadius, r)
            maxRadius = maxOf(maxRadius, r)
        }

        // A rounded square inscribed this way varies by tens of points between flat and corner.
        val variation = maxRadius - minRadius
        assertTrue(
            "radius varied by $variation points, so the circle is being distorted, not just lagged",
            variation < radius * 0.02f,
        )
    }

    @Test
    fun `smoothing is the same whichever way the pen is travelling`() {
        // The isotropy that the circle test measures indirectly, stated directly: a stroke drawn
        // along an axis and the same stroke drawn diagonally must be smoothed by the same amount,
        // or the filter is imposing a preferred direction on the user's hand.
        fun lagAlong(dx: Float, dy: Float): Float {
            val filter = OneEuroFilter.forSmoothing(0.65f)
            var t = 0f
            repeat(30) { i ->
                filter.filter(i * dx, i * dy, t)
                t += 1f / 240f
            }
            return fastDistance(29 * dx - filter.x, 29 * dy - filter.y)
        }

        val axis = lagAlong(10f, 0f)
        val diagonal = lagAlong(7.0711f, 7.0711f)
        assertEquals("the same travel should lag the same amount", axis, diagonal, axis * 0.05f)
    }

    // ---- Stroke builder ------------------------------------------------------------------------

    @Test
    fun `a stroke starts at the exact touch-down position`() {
        val builder = StrokeBuilder()
        builder.start(ToolSpec.PEN, InputConfig(), stylus(42.5f, 17.25f, 0))
        val stroke = builder.finish(null)!!
        assertEquals(42.5f, stroke.xs[0], 1e-4f)
        assertEquals(17.25f, stroke.ys[0], 1e-4f)
    }

    @Test
    fun `a tap still produces a visible mark`() {
        val builder = StrokeBuilder()
        builder.start(ToolSpec.PEN, InputConfig(), stylus(10f, 10f, 0))
        val stroke = builder.finish(null)!!
        assertTrue("a tap must not vanish", stroke.pointCount >= 2)
    }

    @Test
    fun `samples too close together are decimated away`() {
        // Digitisers report far more points than the geometry needs; this shows up directly in
        // file size and tessellation cost.
        val builder = StrokeBuilder()
        builder.start(ToolSpec.PEN, InputConfig(), stylus(0f, 0f, 0))
        var accepted = 0
        repeat(200) { i ->
            if (builder.add(stylus(i * 0.02f, 0f, i.toLong()))) accepted++
        }
        assertTrue("decimation kept $accepted of 200 samples", accepted < 20)
    }

    @Test
    fun `real movement is not decimated away`() {
        val builder = StrokeBuilder()
        builder.start(ToolSpec.PEN, InputConfig(), stylus(0f, 0f, 0))
        var accepted = 0
        repeat(50) { i -> if (builder.add(stylus(i * 5f, 0f, i * 8L))) accepted++ }
        assertTrue(accepted > 40)
    }

    @Test
    fun `a constant-pressure pen produces no per-point width data`() {
        // Storing a flat array of identical factors would waste a byte per point and force the
        // renderer down the slow tessellated path for nothing.
        val stroke = drawLine(ToolSpec.PEN.copy(pressureInfluence = 0f, speedInfluence = 0f))
        assertNull(stroke.widthFactors)
        assertTrue(!Tessellator.needsTessellation(stroke))
    }

    @Test
    fun `pressure modulates width when the tool asks for it`() {
        val stroke = drawLine(ToolSpec.PEN.copy(pressureInfluence = 0.8f)) { i -> i / 60f }
        assertNotNull(stroke.widthFactors)
        val factors = stroke.widthFactors!!
        assertTrue("width did not rise with pressure", factors.last() > factors.first())
        assertTrue(Tessellator.needsTessellation(stroke))
    }

    @Test
    fun `a firm hand draws the width the user asked for`() {
        // The number on the width slider is the width at full pressure. It used to be renormalised
        // against the tool's own ceiling on the way to storage, which quietly capped the pen at
        // five eighths of its setting however hard anyone pressed — and made the whole pressure
        // range read as a narrow band somewhere in the middle.
        val stroke = drawLine(ToolSpec.PEN.copy(width = 4f)) { 1f }
        assertEquals(4f, stroke.width, 1e-4f)
        assertEquals(4f, stroke.widthAt(stroke.pointCount - 1), 1e-3f)
    }

    @Test
    fun `a light hand draws a small fraction of it`() {
        // A range anyone can see. A digitiser's usable pressure band is narrow, so a tool whose
        // extremes are 30% and 60% of the width feels like it has no pressure response at all.
        val stroke = drawLine(ToolSpec.PEN.copy(width = 4f)) { 0f }
        assertTrue(
            "a feather touch drew ${stroke.widthAt(0)} of 4",
            stroke.widthAt(0) <= 4f * 0.15f,
        )
    }

    @Test
    fun `the pressure range spans the tool's own bounds end to end`() {
        val spec = ToolSpec.PEN.copy(width = 10f)
        val light = drawLine(spec) { 0f }.widthAt(5) / 10f
        val firm = drawLine(spec) { 1f }.widthAt(5) / 10f
        assertEquals(spec.minWidthFactor, light, 1e-2f)
        assertEquals(spec.maxWidthFactor, firm, 1e-2f)
    }

    @Test
    fun `width factors stay inside the storable range`() {
        val stroke = drawLine(ToolSpec.FOUNTAIN_PEN) { i -> if (i % 2 == 0) 0f else 1f }
        for (f in stroke.widthFactors!!) {
            assertTrue("factor $f is outside (0,1]", f > 0f && f <= 1f)
        }
    }

    @Test
    fun `the highlighter keeps a constant width regardless of pressure`() {
        val stroke = drawLine(ToolSpec.HIGHLIGHTER) { i -> i / 60f }
        assertNull(stroke.widthFactors)
        assertEquals(BlendId.MULTIPLY, stroke.blend)
    }

    @Test
    fun `the fountain pen thins on a fast flick`() {
        // Speed thinning is what makes it read as ink rather than a uniform ribbon.
        val slow = drawLine(ToolSpec.FOUNTAIN_PEN, n = 40, stepMs = 40)
        val fast = drawLine(ToolSpec.FOUNTAIN_PEN, n = 40, stepMs = 2)
        assertTrue(
            "fast ${fast.widthFactors!!.last()} was not thinner than slow ${slow.widthFactors!!.last()}",
            fast.widthFactors!!.last() < slow.widthFactors!!.last(),
        )
    }

    @Test
    fun `a finger stroke carries no pressure modulation`() {
        // Capacitive touch reports a constant 1.0; treating that as pressure would give a
        // suspiciously perfect line.
        val builder = StrokeBuilder()
        val touch = { x: Float, t: Long ->
            PointerSample(x = x, y = 0f, toolType = ToolType.FINGER, timeMs = t)
        }
        builder.start(ToolSpec.PEN, InputConfig(), touch(0f, 0))
        repeat(30) { i -> builder.add(touch(i * 4f, i * 8L)) }
        assertNull(builder.finish(null)!!.widthFactors)
    }

    @Test
    fun `tool style is carried onto the finished stroke`() {
        val spec = ToolSpec.PEN.copy(color = 0xFF336699.toInt(), width = 4.5f, opacity = 0.5f)
        val stroke = drawLine(spec)
        assertEquals(ToolId.PEN, stroke.tool)
        assertEquals(4.5f, stroke.width, 1e-4f)
        assertEquals(0x80, (stroke.color ushr 24) and 0xFF)
    }

    @Test
    fun `timestamps are relative to the start of the stroke`() {
        val stroke = drawLine(ToolSpec.PEN, n = 10, stepMs = 16)
        assertEquals(0, stroke.times!!.first())
        assertTrue(stroke.times!!.last() > 0)
    }

    // ---- Tessellation --------------------------------------------------------------------------

    @Test
    fun `a flat-width stroke skips tessellation`() {
        val stroke = Stroke(
            ToolId.PEN, -1, 2f, BlendId.NORMAL,
            floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 0f, 0f),
            widthFactors = floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        assertTrue(!Tessellator.needsTessellation(stroke))
    }

    @Test
    fun `the outline of a tapered line is closed and spans its width`() {
        val n = 20
        val stroke = Stroke(
            ToolId.PEN, -1, 10f, BlendId.NORMAL,
            FloatArray(n) { it * 5f }, FloatArray(n),
            widthFactors = FloatArray(n) { 0.2f + 0.8f * it / (n - 1) },
        )
        val outline = Tessellator.tessellate(stroke)

        // Two offset walks plus two caps.
        assertTrue(outline.count > 2 * n)

        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until outline.count) {
            minY = minOf(minY, outline.y(i))
            maxY = maxOf(maxY, outline.y(i))
        }
        // The thick end is 10pt wide, so the outline must span about ±5pt.
        assertEquals(10f, maxY - minY, 1.0f)
    }

    @Test
    fun `the two caps sit at opposite ends`() {
        // A sign error here makes the start cap retrace the far one, leaving a visibly clipped tip.
        val stroke = Stroke(
            ToolId.PEN, -1, 8f, BlendId.NORMAL,
            floatArrayOf(0f, 50f), floatArrayOf(0f, 0f),
            widthFactors = floatArrayOf(0.4f, 1f),
        )
        val outline = Tessellator.tessellate(stroke)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (i in 0 until outline.count) {
            minX = minOf(minX, outline.x(i))
            maxX = maxOf(maxX, outline.x(i))
        }
        // The rounded caps must extend past both endpoints, not just the far one.
        assertTrue("start cap missing: minX=$minX", minX < -1f)
        assertTrue("end cap missing: maxX=$maxX", maxX > 51f)
    }

    @Test
    fun `a single-point stroke tessellates into a dot`() {
        val stroke = Stroke(
            ToolId.PEN, -1, 6f, BlendId.NORMAL,
            floatArrayOf(10f), floatArrayOf(10f),
            widthFactors = floatArrayOf(1f),
        )
        val outline = Tessellator.tessellate(stroke)
        assertTrue(outline.count >= 8)
    }

    @Test
    fun `tessellating into a reused outline does not accumulate`() {
        val stroke = Stroke(
            ToolId.PEN, -1, 4f, BlendId.NORMAL,
            FloatArray(10) { it.toFloat() }, FloatArray(10),
            widthFactors = FloatArray(10) { 0.1f + it * 0.09f },
        )
        val reused = StrokeOutline()
        val first = Tessellator.tessellate(stroke, reused).count
        val second = Tessellator.tessellate(stroke, reused).count
        assertEquals(first, second)
    }
}
