package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.canvas.SheetTransform

/**
 * The pan/zoom arithmetic.
 *
 * Worth pinning down precisely because the failure it replaces was not a crash but a feel: zoom
 * anchored at the top-left of the sheet instead of between the fingers, which reads as the page
 * lurching away from you and is very hard to describe as a bug report.
 */
class SheetTransformTest {

    /**
     * A phone-shaped window with A4 pages at true scale — about 3.7x too wide to fit.
     *
     * Four pages by default. One page fitted to a phone's width is shorter than the window, so a
     * single-page fixture would leave every vertical assertion passing vacuously.
     */
    private fun phone(pages: Int = 4) = SheetTransform().apply {
        setViewport(1080f, 2200f)
        setContent(PAGE_W, PAGE_H * pages)
    }

    @Test
    fun `a new note opens showing the full width of the page`() {
        val t = phone()
        t.fitWidthIfUnset()

        assertTrue("the page should be scaled down to fit", t.zoom < 1f)
        assertTrue("the left edge should be on screen", t.contentToScreenX(0f) >= 0f)
        assertTrue("the right edge should be on screen", t.contentToScreenX(PAGE_W) <= 1080f)
    }

    @Test
    fun `zoom keeps the document under the fingers`() {
        val t = phone()
        t.fitWidthIfUnset()

        // Off-centre, so an implementation anchored at a corner cannot pass by accident, but far
        // enough inside the document that the edge clamp has no say in the result.
        val focusX = 760f
        val focusY = 400f
        val anchorX = t.screenToContentX(focusX)
        val anchorY = t.screenToContentY(focusY)

        t.zoomAround(2.5f, focusX, focusY)

        assertEquals(focusX, t.contentToScreenX(anchorX), 0.01f)
        assertEquals(focusY, t.contentToScreenY(anchorY), 0.01f)
    }

    @Test
    fun `zooming back out returns the anchor to the same place`() {
        val t = phone()
        t.fitWidthIfUnset()
        val start = t.zoom

        val focusX = 300f
        val focusY = 900f
        val anchorX = t.screenToContentX(focusX)
        val anchorY = t.screenToContentY(focusY)

        t.zoomAround(3f, focusX, focusY)
        t.zoomAround(1f / 3f, focusX, focusY)

        assertEquals(start, t.zoom, 1e-4f)
        assertEquals(focusX, t.contentToScreenX(anchorX), 0.01f)
        assertEquals(focusY, t.contentToScreenY(anchorY), 0.01f)
    }

    @Test
    fun `a page narrower than the window is centred and stays centred`() {
        val t = SheetTransform().apply { setViewport(2000f, 2000f); setContent(1000f, 1000f) }

        val centred = (2000f - 1000f) / 2f
        assertEquals(centred, t.contentToScreenX(0f), 0.01f)

        // Dragging sideways must not slide a page that already fits — there is nothing to reveal.
        t.panBy(-500f, 0f)
        assertEquals(centred, t.contentToScreenX(0f), 0.01f)
    }

    @Test
    fun `panning cannot push the sheet off screen`() {
        val t = phone()
        t.fitWidthIfUnset()
        val pad = SheetTransform.EDGE_PAD

        t.panBy(0f, -100_000f)
        assertTrue(
            "the end of the document should stay in view",
            t.contentToScreenY(PAGE_H * 4) >= 2200f - pad - 1f,
        )

        t.panBy(0f, 100_000f)
        assertTrue("the top should stay in view", t.contentToScreenY(0f) <= pad + 0.01f)
    }

    @Test
    fun `a document shorter than the window does not scroll at all`() {
        val t = phone(pages = 1)
        t.fitWidthIfUnset()
        val top = t.contentToScreenY(0f)

        t.panBy(0f, -100_000f)
        assertEquals("there is nothing below to reveal", top, t.contentToScreenY(0f), 0.01f)
    }

    @Test
    fun `the view re-settles by itself when the document grows a page`() {
        val t = phone(pages = 2)
        t.fitWidthIfUnset()
        t.panBy(0f, -100_000f)
        val atBottom = t.contentToScreenY(0f)

        // Typing pushes the text onto a new page while the reader is sitting at the end.
        t.setContent(PAGE_W, PAGE_H * 3)

        assertEquals(
            "growing the sheet should not teleport the view, only free up room below",
            atBottom, t.contentToScreenY(0f), 0.01f,
        )
        t.panBy(0f, -100_000f)
        assertTrue("the new page should now be reachable", t.contentToScreenY(0f) < atBottom)
    }

    @Test
    fun `zoom is bounded`() {
        val t = phone()
        repeat(40) { t.zoomAround(2f, 540f, 1100f) }
        assertEquals(SheetTransform.MAX_ZOOM, t.zoom, 1e-4f)

        repeat(80) { t.zoomAround(0.5f, 540f, 1100f) }
        assertEquals(SheetTransform.MIN_ZOOM, t.zoom, 1e-4f)
    }

    @Test
    fun `a restored view keeps the scale it was saved at`() {
        val t = phone()
        t.fitWidthIfUnset()
        t.zoomAround(2f, 540f, 1100f)

        val restored = SheetTransform.Saver.run {
            val saved = with(TestSaverScope) { save(t) }!!
            restore(saved)!!
        }
        restored.setViewport(1080f, 2200f)
        restored.setContent(PAGE_W, PAGE_H * 4)
        // Re-fitting here would silently undo the user's zoom every time they rotated the phone.
        restored.fitWidthIfUnset()

        assertEquals(t.zoom, restored.zoom, 1e-4f)
    }

    private object TestSaverScope : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private companion object {
        /** A4 at 1:1 on a 3x-density phone, in pixels. */
        const val PAGE_W = 3967f
        const val PAGE_H = 5614f
    }
}
