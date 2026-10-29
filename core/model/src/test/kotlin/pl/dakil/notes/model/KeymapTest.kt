package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapTest {

    private val ctrlZ = KeyChord(Keys.Z, ctrl = true)
    private val ctrlB = KeyChord(Keys.B, ctrl = true)
    private val ctrlShiftB = KeyChord(Keys.B, ctrl = true, shift = true)

    @Test
    fun `the default keymap says nothing when saved, so a better default can still reach everyone`() {
        assertEquals("", Keymap.DEFAULT.encode())
        assertTrue(Keymap.DEFAULT.isDefault)
        assertEquals(Keymap.DEFAULT, Keymap.decode(null))
        assertEquals(Keymap.DEFAULT, Keymap.decode(""))
    }

    @Test
    fun `no two default shortcuts that can be live at once share a key`() {
        val actions = ShortcutAction.entries
        for (a in actions) for (b in actions) {
            if (a == b || !a.overlaps(b)) continue
            val shared = a.defaults.intersect(b.defaults.toSet())
            assertTrue("$a and $b both want $shared", shared.isEmpty())
        }
    }

    @Test
    fun `no default that fires while typing takes a key that types`() {
        for (action in ShortcutAction.entries) for (chord in action.defaults) {
            assertTrue("$action on ${chord.encode()}", Keymap.accepts(action, chord))
        }
    }

    @Test
    fun `every default chord survives being written and read back`() {
        for (action in ShortcutAction.entries) for (chord in action.defaults) {
            assertEquals(chord, KeyChord.decode(chord.encode()))
        }
    }

    @Test
    fun `a changed binding round trips and only it is written`() {
        val map = Keymap.DEFAULT.assign(ShortcutAction.BOLD, ctrlShiftB)
        assertEquals("bold=ctrl+b|ctrl+shift+b", map.encode())
        assertEquals(map, Keymap.decode(map.encode()))
    }

    @Test
    fun `an action with every key removed is written as deliberately empty, not as its default`() {
        val map = Keymap.DEFAULT.unbind(ShortcutAction.LASSO, KeyChord(Keys.L))
        assertEquals("lasso=", map.encode())
        assertTrue(Keymap.decode(map.encode()).bindings(ShortcutAction.LASSO).isEmpty())
    }

    @Test
    fun `a key given to a second action is taken from the first`() {
        val map = Keymap.DEFAULT.assign(ShortcutAction.BOLD, ctrlZ)
        assertEquals(ShortcutAction.BOLD, map.actionFor(ctrlZ, ShortcutContext.TEXT))
        assertFalse(ctrlZ in map.bindings(ShortcutAction.UNDO))
        // Undo still lives in the sheet, but on the sheet Ctrl+Z is now nothing at all rather than
        // half of the old meaning — the user moved the key, not a context of it.
        assertNull(map.actionFor(ctrlZ, ShortcutContext.SHEET))
    }

    @Test
    fun `holderOf names the action a key would be taken from`() {
        assertEquals(ShortcutAction.UNDO, Keymap.DEFAULT.holderOf(ctrlZ, ShortcutAction.BOLD))
        assertNull(Keymap.DEFAULT.holderOf(ctrlZ, ShortcutAction.UNDO))
    }

    @Test
    fun `the same key can mean one thing on the sheet and another in the library`() {
        // They are never live together, so making the user choose between them would be a false clash.
        val ctrlF = KeyChord(Keys.F, ctrl = true)
        val map = Keymap.DEFAULT.assign(ShortcutAction.EXPORT, ctrlF)
        assertEquals(ShortcutAction.EXPORT, map.actionFor(ctrlF, ShortcutContext.SHEET))
        assertEquals(ShortcutAction.SEARCH, map.actionFor(ctrlF, ShortcutContext.LIBRARY))
    }

    @Test
    fun `a key that types is refused for anything that fires while typing`() {
        val p = KeyChord(Keys.P)
        val capitalP = KeyChord(Keys.P, shift = true)
        assertSame(Keymap.DEFAULT, Keymap.DEFAULT.assign(ShortcutAction.BOLD, p))
        assertSame(Keymap.DEFAULT, Keymap.DEFAULT.assign(ShortcutAction.UNDO, capitalP))
        // On the sheet nothing is being typed, so a bare letter is the quickest key there is.
        assertTrue(p in Keymap.DEFAULT.assign(ShortcutAction.PEN, p).bindings(ShortcutAction.PEN))
    }

    @Test
    fun `tab, escape and the function keys are fair game while typing`() {
        assertTrue(Keymap.accepts(ShortcutAction.BOLD, KeyChord(Keys.F1)))
        assertTrue(Keymap.accepts(ShortcutAction.UNDO, KeyChord(Keys.ESCAPE)))
        assertTrue(Keymap.accepts(ShortcutAction.INDENT, KeyChord(Keys.TAB, shift = true)))
        assertFalse(Keymap.accepts(ShortcutAction.BOLD, KeyChord(Keys.ENTER)))
        assertFalse(Keymap.accepts(ShortcutAction.BOLD, KeyChord(Keys.DPAD_LEFT, shift = true)))
    }

    @Test
    fun `resetting an action takes its keys back from whatever was given them`() {
        val moved = Keymap.DEFAULT.assign(ShortcutAction.BOLD, ctrlZ)
        val reset = moved.reset(ShortcutAction.UNDO)
        assertEquals(ShortcutAction.UNDO, reset.actionFor(ctrlZ, ShortcutContext.TEXT))
        assertEquals(listOf(ctrlB), reset.bindings(ShortcutAction.BOLD))
        assertTrue(reset.isDefault)
    }

    @Test
    fun `unknown actions and keys in a saved keymap are dropped rather than failing the read`() {
        val map = Keymap.decode("teleport=ctrl+t;bold=ctrl+hyper+b|ctrl+shift+b|ctrl+nosuchkey;;garbage")
        assertEquals(listOf(ctrlShiftB), map.bindings(ShortcutAction.BOLD))
        assertEquals(Keymap.DEFAULT.bindings(ShortcutAction.ITALIC), map.bindings(ShortcutAction.ITALIC))
    }

    @Test
    fun `a saved key that types is not let back in for an action that fires while typing`() {
        // Hand-edited or written by a future build with looser rules: either way it must not eat a letter.
        val map = Keymap.decode("bold=b")
        assertTrue(map.bindings(ShortcutAction.BOLD).isEmpty())
    }

    @Test
    fun `a chosen key wins over a default that would clash with it`() {
        // As when an action added in a later version comes with a key the user had already used.
        val map = Keymap.decode("bold=ctrl+z")
        assertEquals(ShortcutAction.BOLD, map.actionFor(ctrlZ, ShortcutContext.TEXT))
        assertFalse(ctrlZ in map.bindings(ShortcutAction.UNDO))
    }

    @Test
    fun `modifier order and case in a saved chord do not matter`() {
        assertEquals(ctrlShiftB, KeyChord.decode("SHIFT+Ctrl+B"))
        assertEquals("ctrl+shift+b", ctrlShiftB.encode())
    }

    @Test
    fun `keys outside the table are never turned into a chord`() {
        // Volume, media and the modifiers themselves: none can be written or read back.
        assertFalse(Keys.isKnown(24))
        assertFalse(Keys.isKnown(113))
        assertFalse(Keymap.accepts(ShortcutAction.PEN, KeyChord(24)))
    }
}
