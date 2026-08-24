package pl.dakil.notes.ink

import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the tessellated outline actually covers, rather than how many points it has.
 *
 * The outline is only ever consumed by a non-zero fill, so the honest question to ask of it is the
 * one the rasteriser asks: for a given spot on the page, is there ink there? These tests answer it
 * the same way — by winding number over every contour — which is why they catch the failure the
 * old single-contour outline had at a cusp. It produced plenty of points, all in roughly the right
 * place, wound so that the fill cancelled them against each other and punched a hole through the
 * corner.
 */
class TessellationCoverageTest {

    private fun stroke(
        xs: FloatArray,
        ys: FloatArray,
        width: Float,
        factors: FloatArray = FloatArray(xs.size) { 1f },
    ) = Stroke(ToolId.PEN, -1, width, BlendId.NORMAL, xs, ys, widthFactors = factors)

    /**
     * The non-zero winding number of the whole outline at a point: how many times the contours wind
     * around it, counted with sign. Non-zero means the fill would put ink there.
     */
    private fun windingAt(outline: StrokeOutline, px: Float, py: Float): Int {
        var winding = 0
        for (c in 0 until outline.contourCount) {
            val start = outline.contourStart(c)
            val end = outline.contourEnd(c)
            for (i in start until end) {
                val j = if (i + 1 < end) i + 1 else start
                val x0 = outline.x(i); val y0 = outline.y(i)
                val x1 = outline.x(j); val y1 = outline.y(j)
                // The standard upward/downward crossing test of the ray heading in +x.
                if (y0 <= py) {
                    if (y1 > py && (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0) > 0f) winding++
                } else {
                    if (y1 <= py && (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0) < 0f) winding--
                }
            }
        }
        return winding
    }

    private fun assertInked(outline: StrokeOutline, x: Float, y: Float, where: String) {
        assertTrue("no ink at $where ($x, $y)", windingAt(outline, x, y) != 0)
    }

    private fun assertBare(outline: StrokeOutline, x: Float, y: Float, where: String) {
        assertTrue("unexpected ink at $where ($x, $y)", windingAt(outline, x, y) == 0)
    }

    @Test
    fun `a straight stroke is inked across its width and not beyond it`() {
        val n = 20
        val outline = Tessellator.tessellate(
            stroke(FloatArray(n) { it * 4f }, FloatArray(n), width = 10f)
        )
        assertInked(outline, 40f, 0f, "the centreline")
        assertInked(outline, 40f, 4.5f, "just inside the edge")
        assertBare(outline, 40f, 5.5f, "just outside the edge")
    }

    @Test
    fun `a taper is inked to the local width, not the nominal one`() {
        // The whole point of tessellating: at the thin end the ink has to stop short of the width
        // the tool is set to, or pressure would be recorded and never seen.
        val n = 20
        val outline = Tessellator.tessellate(
            stroke(
                FloatArray(n) { it * 4f }, FloatArray(n), width = 10f,
                factors = FloatArray(n) { 0.2f + 0.8f * it / (n - 1) },
            )
        )
        assertInked(outline, 76f, 4.5f, "the thick end")
        assertBare(outline, 0f, 1.5f, "outside the thin end")
        assertInked(outline, 0f, 0.9f, "inside the thin end")
    }

    @Test
    fun `a turn tighter than the nib stays solid ink`() {
        // The complaint this rewrite came from, in the shape the device actually produces it: a
        // pen 20pt wide changing direction around a 3pt curve, sampled a third of a point apart.
        // Offsetting a single contour by half that width walks the inside of the turn backwards,
        // and the loop it ties is wound against the rest — so the non-zero rule cancels it and
        // eats a hole out of the corner. Nothing here is unusual; it is one letter of handwriting
        // with a marker-sized nib.
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        var t = 0f
        while (t <= 30f) { px.add(-t); py.add(0f); t += 0.35f }
        var a = 0f
        while (a <= Math.PI.toFloat()) { px.add(-3f * sin(a)); py.add(3f - 3f * cos(a)); a += 0.1f }
        t = 0f
        while (t <= 30f) { px.add(-t); py.add(6f); t += 0.35f }

        val n = px.size
        val outline = Tessellator.tessellate(
            stroke(FloatArray(n) { px[it] }, FloatArray(n) { py[it] }, width = 20f)
        )

        // Every spot comfortably inside the swept nib has to be inked, all the way round.
        var hollow = 0
        var gx = -30f
        while (gx <= 12f) {
            var gy = -10f
            while (gy <= 16f) {
                var nearest = Float.MAX_VALUE
                for (i in 0 until n) {
                    val d = (gx - px[i]) * (gx - px[i]) + (gy - py[i]) * (gy - py[i])
                    if (d < nearest) nearest = d
                }
                if (nearest < 64f && windingAt(outline, gx, gy) == 0) hollow++
                gy += 0.5f
            }
            gx += 0.5f
        }
        assertTrue("$hollow spots inside the stroke were left hollow", hollow == 0)
    }

    @Test
    fun `a gentle bend leaves no seam where the samples meet`() {
        // Offsetting each segment along its own normal leaves a wedge at every vertex, open by the
        // nib's width times the angle of the turn. It sounds negligible and is not: half a degree
        // under a wide nib is a crack a fifth of a point wide running most of the way across the
        // stroke, repeated at every sample. On the page it reads as the ink being scratched.
        val n = 40
        val xs = FloatArray(n) { 200f * sin(it * 0.02f) }
        val ys = FloatArray(n) { 200f - 200f * cos(it * 0.02f) }
        val outline = Tessellator.tessellate(
            stroke(xs, ys, width = 20f, factors = FloatArray(n) { 0.6f + 0.4f * it / (n - 1) })
        )

        // Straight out from each sample, along the bisector of the turn there — the exact line any
        // wedge would open along.
        for (i in 1 until n - 1) {
            var bx = xs[i] - xs[i - 1]
            var by = ys[i] - ys[i - 1]
            var cx = xs[i + 1] - xs[i]
            var cy = ys[i + 1] - ys[i]
            val lb = kotlin.math.sqrt(bx * bx + by * by)
            val lc = kotlin.math.sqrt(cx * cx + cy * cy)
            bx /= lb; by /= lb; cx /= lc; cy /= lc
            val sx = -by + -cy
            val sy = bx + cx
            val ls = kotlin.math.sqrt(sx * sx + sy * sy)
            val r = 20f * (0.6f + 0.4f * i / (n - 1)) * 0.5f
            var t = 0.1f
            while (t < r * 0.9f) {
                for (sign in listOf(1f, -1f)) {
                    assertInked(
                        outline,
                        xs[i] + sx / ls * t * sign,
                        ys[i] + sy / ls * t * sign,
                        "sample $i at $t",
                    )
                }
                t += 0.25f
            }
        }
    }

    @Test
    fun `a hairpin is solid ink through the turn`() {
        // A reversal is the other way a single offset contour ties itself up: the two sides swap
        // over at the tip, which hands the filler a bow tie. Underlining a word and coming back
        // along it is the everyday version.
        val xs = FloatArray(41)
        val ys = FloatArray(41)
        for (i in 0..20) { xs[i] = i * 2f; ys[i] = 0f }
        for (i in 21..40) { xs[i] = (40 - i) * 2f; ys[i] = 1.5f }
        val outline = Tessellator.tessellate(stroke(xs, ys, width = 20f))

        assertInked(outline, 40f, 0.75f, "the tip of the turn")
        assertInked(outline, 44f, 0.75f, "the cap past the tip")
        assertInked(outline, 20f, 8f, "one side of the hairpin")
        assertInked(outline, 20f, -7f, "the other side")
        assertBare(outline, 20f, 12f, "clear of the stroke")
    }

    @Test
    fun `a right-angle corner is filled out to the nib, not chipped`() {
        // Two straight pieces meeting at a corner leave a wedge open on the outside of the turn as
        // deep as the nib is wide. On a 20pt pen that is a visible bite out of every corner.
        val xs = FloatArray(21)
        val ys = FloatArray(21)
        for (i in 0..10) { xs[i] = i * 4f; ys[i] = 0f }
        for (i in 11..20) { xs[i] = 40f; ys[i] = (i - 10) * 4f }
        val outline = Tessellator.tessellate(stroke(xs, ys, width = 20f))

        // The outer corner, on the bisector, just inside the nib's reach.
        val d = 9f / kotlin.math.sqrt(2f)
        assertInked(outline, 40f + d, -d, "the outside of the corner")
        assertInked(outline, 40f, -9f, "square above the corner")
        assertInked(outline, 49f, 0f, "square right of the corner")
    }

    @Test
    fun `every contour is wound the same way`() {
        // Non-zero fill adds windings, so a piece wound against the rest subtracts itself from
        // whatever it overlaps — which is a hole exactly where two pieces meet, and the reason the
        // caps and joints cannot simply be drawn the way trigonometry hands them over.
        val n = 30
        val xs = FloatArray(n) { 40f + 30f * cos(it * 0.3f) }
        val ys = FloatArray(n) { 40f + 30f * sin(it * 0.3f) }
        val outline = Tessellator.tessellate(
            stroke(xs, ys, width = 8f, factors = FloatArray(n) { 0.3f + 0.7f * it / (n - 1) })
        )
        assertTrue("expected several contours", outline.contourCount > 1)
        for (c in 0 until outline.contourCount) {
            val start = outline.contourStart(c)
            val end = outline.contourEnd(c)
            var area = 0f
            for (i in start until end) {
                val j = if (i + 1 < end) i + 1 else start
                area += outline.x(i) * outline.y(j) - outline.x(j) * outline.y(i)
            }
            assertTrue("contour $c is wound against the others (area $area)", area < 0f)
        }
    }

    @Test
    fun `coincident samples contribute no ink of their own`() {
        // A pen lifted without moving forces a final sample on top of the last one, and a stroke
        // clipped at a page boundary can graze it. There is no direction to be had from a
        // zero-length step, so those samples must be stepped over rather than normalised — and the
        // ink either side of them has to join up as if they were never there.
        val xs = floatArrayOf(0f, 20f, 20f, 20f, 40f)
        val ys = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        val outline = Tessellator.tessellate(
            stroke(xs, ys, width = 12f, factors = floatArrayOf(1f, 0.8f, 0.8f, 0.8f, 0.5f))
        )
        for (i in 0 until outline.count) {
            assertTrue(
                "outline point ${outline.x(i)}, ${outline.y(i)} strayed off the stroke",
                outline.y(i) in -6.1f..6.1f && outline.x(i) in -6.1f..46.1f,
            )
        }
        assertInked(outline, 20f, 0f, "the middle")
    }

    @Test
    fun `a dot is a disc of the drawn width`() {
        // A tap is stored as two all-but-coincident points, so that it is a mark and not nothing.
        // It has to come out round and the size the pen was set to, in every direction.
        val outline = Tessellator.tessellate(
            stroke(floatArrayOf(10f, 10.01f), floatArrayOf(10f, 10f), width = 6f)
        )
        assertInked(outline, 10f, 10f, "the middle of the dot")
        for (a in 0 until 8) {
            val dx = cos(a * 0.785f)
            val dy = sin(a * 0.785f)
            assertInked(outline, 10f + dx * 2.5f, 10f + dy * 2.5f, "inside the dot at $a")
            assertBare(outline, 10f + dx * 3.6f, 10f + dy * 3.6f, "outside the dot at $a")
        }
    }
}
