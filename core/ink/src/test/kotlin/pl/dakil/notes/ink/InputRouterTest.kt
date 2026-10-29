package pl.dakil.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.StylusButtonAction
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
        barrelButton: Boolean = false,
    ) = PointerSample(
        x = x, y = y, touchMajor = touchMajor, toolType = toolType,
        timeMs = t, pointerId = id, primaryButton = primaryButton, barrelButton = barrelButton,
    )

    // ---- Stylus ------------------------------------------------------------------------------

    @Test
    fun `a stylus always draws`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false))
        assertEquals(InputIntent.Draw, router.begin(sample(ToolType.STYLUS)).intent)
    }

    // ---- Pen actions: the eraser end and the side button ---------------------------------------

    @Test
    fun `the eraser end of a stylus draws, as an eraser`() {
        val router = InputRouter()
        val decision = router.begin(sample(ToolType.ERASER))
        assertEquals(InputIntent.Draw, decision.intent)
        assertEquals(StylusButtonAction.ERASE, decision.penAction)
        assertEquals(StylusButtonAction.ERASE, router.penActionFor(0))
    }

    @Test
    fun `a stylus with its side button held erases by default`() {
        val router = InputRouter()
        val decision = router.begin(sample(ToolType.STYLUS, barrelButton = true))
        assertEquals(InputIntent.Draw, decision.intent)
        assertEquals(StylusButtonAction.ERASE, decision.penAction)
    }

    @Test
    fun `the side button does what the setting asks`() {
        val router = InputRouter(InputConfig(stylusButtonAction = StylusButtonAction.SELECT))
        val decision = router.begin(sample(ToolType.STYLUS, barrelButton = true))
        assertEquals(InputIntent.Draw, decision.intent)
        assertEquals(StylusButtonAction.SELECT, decision.penAction)
    }

    @Test
    fun `a side button set to nothing leaves the pen drawing with the toolbar's tool`() {
        // Null rather than NONE, so the overlay has one way to hear "use the toolbar", not two.
        val router = InputRouter(InputConfig(stylusButtonAction = StylusButtonAction.NONE))
        val decision = router.begin(sample(ToolType.STYLUS, barrelButton = true))
        assertEquals(InputIntent.Draw, decision.intent)
        assertNull(decision.penAction)
    }

    @Test
    fun `the eraser end erases whatever the side button is set to`() {
        // The setting is about the button. Turning the pen over is a physical eraser, and someone
        // who set the button to select has not asked for the other end to select too.
        for (action in StylusButtonAction.entries) {
            val router = InputRouter(InputConfig(stylusButtonAction = action))
            assertEquals(StylusButtonAction.ERASE, router.begin(sample(ToolType.ERASER)).penAction)
        }
    }

    @Test
    fun `a stylus with no button held draws with the tool that is out`() {
        val router = InputRouter()
        assertNull(router.begin(sample(ToolType.STYLUS)).penAction)
        assertNull(router.penActionFor(0))
    }

    @Test
    fun `the side button is read at pen-down and held for the whole stroke`() {
        // A thumb resting on the barrel brushes the button mid-word. Switching tools there would
        // leave half a line drawn and erase back along the other half.
        val router = InputRouter()
        router.begin(sample(ToolType.STYLUS, t = 1000))
        router.update(sample(ToolType.STYLUS, t = 1010, barrelButton = true))
        assertNull(router.penActionFor(0))

        router.end(sample(ToolType.STYLUS, t = 1020))
        router.begin(sample(ToolType.STYLUS, t = 1100, barrelButton = true))
        router.update(sample(ToolType.STYLUS, t = 1110, barrelButton = false))
        assertEquals(StylusButtonAction.ERASE, router.penActionFor(0))
    }

    @Test
    fun `changing the setting mid-stroke does not change what the pen is doing`() {
        val router = InputRouter()
        router.begin(sample(ToolType.STYLUS, barrelButton = true))
        router.config = InputConfig(stylusButtonAction = StylusButtonAction.SELECT)
        assertEquals(StylusButtonAction.ERASE, router.penActionFor(0))
    }

    @Test
    fun `a hovering pen is judged by the same rule as a landing one`() {
        // The eraser ring shown in the air has to be the tool the pen will have on the glass.
        val router = InputRouter(InputConfig(stylusButtonAction = StylusButtonAction.SELECT))
        assertEquals(StylusButtonAction.SELECT, router.penActionOf(ToolType.STYLUS, barrelButton = true))
        assertEquals(StylusButtonAction.ERASE, router.penActionOf(ToolType.ERASER, barrelButton = false))
        assertNull(router.penActionOf(ToolType.STYLUS, barrelButton = false))
    }

    @Test
    fun `a lifted pen forgets what it was doing`() {
        val router = InputRouter()
        router.begin(sample(ToolType.STYLUS, barrelButton = true))
        router.end(sample(ToolType.STYLUS))
        assertNull(router.penActionFor(0))
    }

    @Test
    fun `a mouse button is never mistaken for the side button`() {
        // Only a pen has a barrel. A right-click is a different gesture, and erasing with it would
        // surprise anyone driving the app from a desktop-mode pointer.
        val router = InputRouter()
        assertNull(router.begin(sample(ToolType.MOUSE, barrelButton = true)).penAction)
    }

    @Test
    fun `a finger never takes up a pen action`() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        assertNull(router.begin(sample(ToolType.FINGER, barrelButton = true)).penAction)
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
    fun `a hand landing while the pen is on the glass is a palm however long the pen has been still`() {
        // The regression test for strokes breaking into pieces mid-line. The palm window is
        // refreshed only by stylus samples, and Android reports no movement while a pointer is
        // stationary — so any deliberate pause outlives the window while the nib is still down.
        // Auto-shape asks for a 500ms hold by default against a 100ms window, so this is the
        // ordinary case rather than a corner of one.
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 100))
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        val palm = router.begin(sample(ToolType.FINGER, id = 1, t = 9000))
        assertEquals(InputIntent.Ignore, palm.intent)
    }

    @Test
    fun `a hand landing under a held pen is a palm even at fingertip size`() {
        // The size rule is the other half of the old defence, and it cannot help here: commit
        // 8948591 raised the threshold to 160f, so a great many real palm contacts read as
        // fingertips. Contact by the pen is what settles it.
        val router = InputRouter(
            InputConfig(palmRejectionWindowMs = 100, palmTouchMajorThreshold = 160f)
        )
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        val palm = router.begin(sample(ToolType.FINGER, id = 1, t = 9000, touchMajor = 20f))
        assertEquals(InputIntent.Ignore, palm.intent)
    }

    @Test
    fun `a hand landing under a held pen cannot take the ink even in finger-drawing mode`() {
        // With finger drawing on, the re-admitted hand used to be given Draw. There is one shared
        // StrokeBuilder, so its pointer-down wiped the pen's stroke and its pointer-up committed
        // what was left — one drawn line arriving as several disjoint strokes.
        val router = InputRouter(
            InputConfig(fingerDrawingEnabled = true, palmRejectionWindowMs = 100)
        )
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        val palm = router.begin(sample(ToolType.FINGER, id = 1, t = 9000))
        assertEquals(InputIntent.Ignore, palm.intent)
        assertTrue(palm.revoked.isEmpty())
        assertTrue(router.isDrawing)
    }

    @Test
    fun `the pen keeps the glass to itself until it actually lifts`() {
        // The suppression is contact-based, so it has to end on the pointer-up rather than on a
        // clock: a hand put down after the pen has gone still pans the page.
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 100))
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        assertEquals(
            InputIntent.Ignore,
            router.begin(sample(ToolType.FINGER, id = 1, t = 5000)).intent,
        )

        router.end(sample(ToolType.STYLUS, id = 0, t = 5100))
        assertEquals(
            InputIntent.Navigate,
            router.begin(sample(ToolType.FINGER, id = 2, t = 5300)).intent,
        )
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
    fun `a stylus arriving after a palm has taken the page revokes that too`() {
        // The same palm, with finger drawing off: it is given the page to pan rather than the ink,
        // and left alone it goes on panning under the stroke that is now being drawn — which is
        // exactly the "the pen just scrolls the page" complaint. The pen outranks it either way.
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false))
        val palm = router.begin(sample(ToolType.FINGER, id = 7, t = 1000))
        assertEquals(InputIntent.Navigate, palm.intent)

        val pen = router.begin(sample(ToolType.STYLUS, id = 8, t = 1030))
        assertEquals(listOf(7), pen.revoked)
        assertEquals(InputIntent.Ignore, router.update(sample(ToolType.FINGER, id = 7, t = 1040)))
    }

    @Test
    fun `a pen still in the air makes the hand that lands under it a palm`() {
        // Hover is what catches the heel of the hand, which otherwise arrives before the nib does
        // and is indistinguishable from a finger asking to scroll. Every pen this is written for
        // reports itself from a centimetre or so out, and gets there first.
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 120))
        router.observeStylusProximity(1000L)
        assertEquals(InputIntent.Ignore, router.begin(sample(ToolType.FINGER, id = 3, t = 1040)).intent)
    }

    @Test
    fun `a pen lifted clear of the screen gives the fingers back`() {
        // Proximity has to expire like contact does, or resting the pen in your hand near the
        // tablet would leave the page unpannable.
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 120))
        router.observeStylusProximity(1000L)
        assertEquals(InputIntent.Navigate, router.begin(sample(ToolType.FINGER, id = 3, t = 1500)).intent)
    }

    @Test
    fun `hover never winds the stylus clock backwards`() {
        // Hover samples and contact samples come from different dispatch paths and can be
        // delivered out of order. An older one must not shorten the window a newer one opened.
        val router = InputRouter(InputConfig(palmRejectionWindowMs = 120))
        router.begin(sample(ToolType.STYLUS, id = 0, t = 1000))
        router.observeStylusProximity(900L)
        assertEquals(InputIntent.Ignore, router.begin(sample(ToolType.FINGER, id = 1, t = 1050)).intent)
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

    // ---- Stream hygiene ----------------------------------------------------------------------

    @Test
    fun `a pointer that never lifts poisons every gesture after it`() {
        // Not a defect in the router but a contract on its caller, and worth pinning down because
        // the symptom is so far from the cause: a gesture declined at ACTION_DOWN never receives
        // its ACTION_UP, so unless the caller clears the router at the start of the next stream,
        // this finger stays live forever and every later touch reads as multi-touch.
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))
        router.begin(sample(ToolType.FINGER, id = 0))

        assertEquals(
            "a phantom finger turns drawing into panning",
            InputIntent.Navigate,
            router.begin(sample(ToolType.FINGER, id = 1)).intent,
        )

        router.cancel()
        assertEquals(
            InputIntent.Draw,
            router.begin(sample(ToolType.FINGER, id = 2)).intent,
        )
    }

    @Test
    fun `cancel leaves no pointers behind`() {
        val router = InputRouter()
        router.begin(sample(ToolType.STYLUS, id = 0))
        router.begin(sample(ToolType.FINGER, id = 1))
        router.cancel()
        assertEquals(0, router.activePointerCount)
    }
}
