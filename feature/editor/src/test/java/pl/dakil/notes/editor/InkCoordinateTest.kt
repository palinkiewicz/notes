package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.editor.canvas.documentPointAt
import pl.dakil.notes.model.PageFormat

/**
 * Where a touch lands on the page.
 *
 * This mapping has now been wrong twice, both times silently: strokes appear, they are simply in
 * the wrong place, and no layer of the app is in a position to notice. The round-trip is cheap to
 * assert and is the only thing standing between a refactor and ink that drifts under the pen.
 */
class InkCoordinateTest {

    private val format = PageFormat.DEFAULT
    private val ptToPx = 6.67f

    /**
     * The inverse of what the overlay does: document point -> the coordinate `pointerInteropFilter`
     * would hand us for it, including the placement scale it does not account for itself.
     */
    private fun pointerCoordFor(docX: Float, docY: Float, zoom: Float): Pair<Float, Float> =
        docX * ptToPx * zoom to
            SheetPainter.documentYToStripPx(docY, format, ptToPx, paged = true) * zoom

    @Test
    fun `a touch maps back to the document point it was made at`() {
        for (zoom in listOf(0.26f, 1f, 3.5f)) {
            val (px, py) = pointerCoordFor(300f, 500f, zoom)
            val point = documentPointAt(px, py, ptToPx, zoom, format, paged = true)

            assertEquals("x at zoom $zoom", 300f, point.x, 0.01f)
            assertEquals("y at zoom $zoom", 500f, point.y, 0.01f)
        }
    }

    @Test
    fun `a touch on the third page maps past the inter-page gaps`() {
        // Paged view is the one place the two views are not the same geometry: the gaps between
        // sheets are screen furniture and must not accumulate into the stored coordinate.
        val docY = format.height * 2 + 120f
        val (px, py) = pointerCoordFor(100f, docY, zoom = 0.4f)

        val point = documentPointAt(px, py, ptToPx, 0.4f, format, paged = true)
        assertEquals(docY, point.y, 0.01f)
    }

    @Test
    fun `continuous view has no gaps to account for`() {
        val docY = format.height * 2 + 120f
        val py = SheetPainter.documentYToStripPx(docY, format, ptToPx, paged = false) * 0.4f

        val point = documentPointAt(0f, py, ptToPx, 0.4f, format, paged = false)
        assertEquals(docY, point.y, 0.01f)
    }

    @Test
    fun `zoom is what separates a correct mapping from a plausible one`() {
        // Guards the specific regression: ignoring the placement scale still yields a believable
        // point, just one pulled towards the origin in proportion to the zoom.
        val zoom = 0.25f
        val (px, py) = pointerCoordFor(400f, 400f, zoom)

        val ignoringZoom = documentPointAt(px, py, ptToPx, 1f, format, paged = true)
        assertEquals(100f, ignoringZoom.x, 0.5f)

        val correct = documentPointAt(px, py, ptToPx, zoom, format, paged = true)
        assertEquals(400f, correct.x, 0.01f)
    }

    @Test
    fun `a touch in the gap between pages stays on the page above instead of the one below`() {
        // Carried on past the edge, the gap read as the top band of the next page: ink and lassos
        // drawn on the desk landed on that page, out of sight of the pen that drew them.
        val footPx = SheetPainter.documentYToStripPx(format.height, format, ptToPx, paged = true)
        val inGap = footPx - SheetPainter.PAGE_GAP_PT * ptToPx * 0.5f
        val y = SheetPainter.stripPxToDocumentY(inGap, format, ptToPx, paged = true)
        assertEquals(format.height, y, 0.01f)
        assertTrue("must stay on the first page, got $y", y < format.height)
    }

    @Test
    fun `dragging down across a page break never moves backwards`() {
        // A selection follows this mapping sample by sample; any step back is the selection
        // jumping back under a pen that only ever moved forward.
        val start = SheetPainter.documentYToStripPx(format.height - 50f, format, ptToPx, paged = true)
        val end = SheetPainter.documentYToStripPx(format.height + 50f, format, ptToPx, paged = true)
        var previous = -Float.MAX_VALUE
        var py = start
        while (py <= end) {
            val y = SheetPainter.stripPxToDocumentY(py, format, ptToPx, paged = true)
            assertTrue("went back from $previous to $y at $py", y >= previous)
            previous = y
            py += 0.5f
        }
    }
}
