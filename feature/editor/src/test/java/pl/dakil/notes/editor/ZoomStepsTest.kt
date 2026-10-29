package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.dakil.notes.editor.canvas.zoomStep

class ZoomStepsTest {

    @Test
    fun `zooming in from between two stops goes to the next one up`() {
        assertEquals(1.25f, zoomStep(1.1f, emptyList(), zoomIn = true)!!, 0.0001f)
        assertEquals(1f, zoomStep(1.1f, emptyList(), zoomIn = false)!!, 0.0001f)
    }

    @Test
    fun `a press at a stop moves off it rather than to it`() {
        // Fitting a page often lands a hair off 100 %, and a press there must still do something.
        assertEquals(1.25f, zoomStep(1.001f, emptyList(), zoomIn = true)!!, 0.0001f)
        assertEquals(0.75f, zoomStep(0.999f, emptyList(), zoomIn = false)!!, 0.0001f)
    }

    @Test
    fun `the user's pinned zooms are stops too`() {
        assertEquals(1.1f, zoomStep(1f, listOf(110), zoomIn = true)!!, 0.0001f)
    }

    @Test
    fun `past the last stop there is nowhere to go`() {
        assertNull(zoomStep(8f, emptyList(), zoomIn = true))
        assertNull(zoomStep(0.1f, emptyList(), zoomIn = false))
    }
}
