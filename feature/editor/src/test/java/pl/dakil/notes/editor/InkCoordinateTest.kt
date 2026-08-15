package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.dakil.notes.editor.canvas.SheetPainter
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
}
