package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.ToolType

/**
 * The full input decision table.
 *
 * These are the rules users feel most sharply — a rejected pen stroke or a palm that draws is
 * immediately disqualifying — and they are pure logic, so they belong in fast JVM tests rather
 * than in a manual pass on a device nobody has to hand.
 */
class InputRouterTest {

    private fun sample(
        toolType: ToolType,
        id: Int = 0,
        t: Long = 1000L,
        touchMajor: Float = 10f,
        x: Float = 0f,
        y: Float = 0f,
        primaryButton: Boolean = true,
    ) = PointerSample(
        x = x, y = y, touchMajor = touchMajor, toolType = toolType,
        timeMs = t, pointerId = id, primaryButton = primaryButton,
    )

    // ---- Stylus ------------------------------------------------------------------------------

    @Test
    fun `a stylus always draws`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false))
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.STYLUS)).intent)
    }

    @Test
    fun `the eraser end of a stylus draws too, and the tool layer decides it erases`() {
        val router = InputRouter()
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.ERASER)).intent)
    }

    // ---- Palm rejection ----------------------------------------------------------------------

    @Test
    fun `a touch landing just after a stylus sample is rejected as a palm`() {
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 120))
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        val palm = router.begin(sample(ToolType.FINGER, id = 1, t = 1050))
        assertEquals(InputIntent.Ignore, palm.intent)
    }

    @Test
    fun `a touch well after the stylus has stopped is no longer suppressed`() {
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 120))
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        router.end(sample(ToolType.STYLUS, id = 0, t = 1010))
        val touch = router.begin(sample(ToolType.FINGER, id = 1, t = 1500))
        assertEquals(InputIntent.Navigate, touch.intent)
    }

    @Test
    fun `a large contact patch is rejected even with no stylus activity`() {
        val router = InputRouter(InputConfig(palmTouchMajorThreshold = 90f))
        val palm = router.begin(sample(ToolType.FINGER, touchMajor = 140f))
        assertEquals(InputIntent.Ignore, palm.intent)
    }

    @Test
    fun `a normal fingertip is not mistaken for a palm`() {
        val router = InputRouter(InputConfig(palmTouchMajorThreshold = 90f))
        assertEquals(InputIntent.Navigate, router.begin(sample(ToolType.FINGER, touchMajor = 35f)).intent)
    }

    @Test
    fun `a stylus arriving after a palm has already started drawing revokes it`() {
        // The time-window rule cannot catch a palm that lands first. Revocation is what stops the
        // resulting streak from surviving on the page.
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        val palm = router.begin(sample(ToolType.FINGER, id = 7, t = 1000))
        assertEquals(InputIntent.Draw, palm.intent)

        val pen = router.begin(sample(ToolType.STYLUS, id = 8, t = 1030))
        assertEquals(InputIntent.Draw, pen.intent)
        assertEquals(listOf(7), pen.revoked)
        assertEquals(InputIntent.Ignore, router.update(sample(ToolType.FINGER, id = 7, t = 1040)))
    }

    @Test
    fun `a stylus does not revoke another stylus`() {
        val router = InputRouter()
        router.begin(sample(ToolType.STYLUS, id = 1, t = 1000))
        val second = router.begin(sample(ToolType.STYLUS, id = 2, t = 1010))
        assertTrue(second.revoked.isEmpty())
    }

    // ---- Finger drawing toggle ------------------------------------------------------------------

    @Test
    fun `a single finger pans when finger drawing is off`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false))
        assertEquals(InputIntent.Navigate, router.begin(sample(ToolType.FINGER)).intent)
    }

    @Test
    fun `a single finger draws when finger drawing is on`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.FINGER)).intent)
    }

    @Test
    fun `a second finger navigates and revokes the first finger's stroke`() {
        // Without this, a stylus-less phone in finger-drawing mode would have no way to pan, and
        // pinching to zoom would scrawl across the page.
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.FINGER, id = 1)).intent)

        val second = router.begin(sample(ToolType.FINGER, id = 2))
        assertEquals(InputIntent.Navigate, second.intent)
        assertEquals(listOf(1), second.revoked)
        assertFalse(router.isDrawing)
    }

    @Test
    fun `two fingers both navigate when finger drawing is off`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false))
        assertEquals(InputIntent.Navigate, router.begin(sample(ToolType.FINGER, id = 1)).intent)
        val second = router.begin(sample(ToolType.FINGER, id = 2))
        assertEquals(InputIntent.Navigate, second.intent)
        assertTrue(second.revoked.isEmpty())
    }

    @Test
    fun `a finger alongside a drawing stylus does not hijack it into a pan`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false, palmRejectionWindowMs = 120))
        router.begin(sample(ToolType.STYLUS, id = 1, t = 1000))
        val touch = router.begin(sample(ToolType.FINGER, id = 2, t = 1010))
        assertEquals(InputIntent.Ignore, touch.intent)
        assertEquals(InputIntent.Draw, router.intentFor(1))
        assertTrue(router.isDrawing)
    }

    // ---- Mouse -------------------------------------------------------------------------------

    @Test
    fun `a mouse draws with its primary button and pans without it`() {
        val router = InputRouter()
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.MOUSE, id = 1, primaryButton = true)).intent)
        assertEquals(InputIntent.Navigate, router.begin(sample(ToolType.MOUSE, id = 2, primaryButton = false)).intent)
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    @Test
    fun `intent is fixed at pointer down and held for the whole gesture`() {
        // A stroke must never turn into a pan halfway through, whatever later samples look like.
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true, palmTouchMajorThreshold = 90f))
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.FINGER, id = 1, touchMajor = 20f)).intent)
        // The contact patch grows past the palm threshold mid-stroke, as a fingertip flattens.
        assertEquals(InputIntent.Draw, router.update(sample(ToolType.FINGER, id = 1, touchMajor = 200f)))
    }

    @Test
    fun `changing configuration mid-stroke does not reinterpret the live pointer`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        router.begin(sample(ToolType.FINGER, id = 1))
        router.config = InputConfig(fingerDrawingEnabled = false)
        assertEquals(InputIntent.Draw, router.update(sample(ToolType.FINGER, id = 1)))
    }

    @Test
    fun `pointers are released on up and on cancel`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        router.begin(sample(ToolType.FINGER, id = 1))
        router.begin(sample(ToolType.FINGER, id = 2))
        assertEquals(2, router.activePointerCount)

        router.end(sample(ToolType.FINGER, id = 1))
        assertEquals(1, router.activePointerCount)

        router.cancel()
        assertEquals(0, router.activePointerCount)
        assertFalse(router.isDrawing)
    }

    @Test
    fun `samples for a revoked pointer are ignored rather than resumed`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        router.begin(sample(ToolType.FINGER, id = 1))
        router.begin(sample(ToolType.FINGER, id = 2))
        assertEquals(InputIntent.Ignore, router.update(sample(ToolType.FINGER, id = 1)))
    }

    @Test
    fun `palm rejection can be disabled entirely by threshold`() {
        val router = InputRouter(
            InputConfig(
                fingerDrawingEnabled = true,
                palmRejectionWindowMs = 0,
                palmTouchMajorThreshold = 0f,
            )
        )
        router.begin(sample(ToolType.STYLUS, id = 1, t = 1000))
        assertEquals(
            InputIntent.Draw,
            router.begin(sample(ToolType.FINGER, id = 2, t = 1000, touchMajor = 500f)).intent,
        )
    }
}
