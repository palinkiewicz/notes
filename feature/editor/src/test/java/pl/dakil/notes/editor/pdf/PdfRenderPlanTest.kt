package pl.dakil.notes.editor.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.Rect

class PdfRenderPlanTest {

    @Test
    fun `the zoom is followed in half-octave steps, so a pinch re-renders a few times, not every frame`() {
        assertEquals(1f, PdfRenderPlan.bucket(1.1f), 1e-4f)
        assertEquals(1.4142f, PdfRenderPlan.bucket(1.3f), 1e-3f)
        assertEquals(2f, PdfRenderPlan.bucket(2.2f), 1e-4f)
        assertEquals(PdfRenderPlan.bucket(2.05f), PdfRenderPlan.bucket(1.95f))
    }

    @Test
    fun `a base keeps the page's shape and never exceeds the cap however deep the zoom`() {
        val base = PdfRenderPlan.baseSize(595f, 842f, 40f)
        assertEquals(PdfRenderPlan.MAX_BASE_PX, base[1])
        assertEquals(595f / 842f, base[0].toFloat() / base[1], 0.01f)
    }

    @Test
    fun `a tile is wanted only once the glass outgrows the base`() {
        val fit = PdfRenderPlan.baseSize(595f, 842f, 1.8f)
        assertFalse(PdfRenderPlan.needsDetail(595f, fit[0], 1.8f))
        val deep = PdfRenderPlan.baseSize(595f, 842f, 16f)
        assertTrue(PdfRenderPlan.needsDetail(595f, deep[0], 16f))
    }

    @Test
    fun `a tile covers only what of the page is on screen, at the glass's resolution`() {
        val page = Rect(0f, 842f, 595f, 1684f)
        val tile = PdfRenderPlan.tile(page, Rect(100f, 1000f, 200f, 1100f), 10f)
        assertNotNull(tile)
        tile!!
        assertEquals(1000, tile.widthPx)
        assertEquals(1000, tile.heightPx)
        assertEquals(100f / 595f, tile.region[0], 1e-5f)
        assertEquals(158f / 842f, tile.region[1], 1e-5f)
        assertEquals(Rect(100f, 1000f, 200f, 1100f), tile.rect)
    }

    @Test
    fun `no tile for a page that is off screen`() {
        assertNull(PdfRenderPlan.tile(Rect(0f, 0f, 595f, 842f), Rect(0f, 900f, 595f, 1200f), 4f))
    }
}
