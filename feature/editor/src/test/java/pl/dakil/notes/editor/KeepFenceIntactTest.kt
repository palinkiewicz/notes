package pl.dakil.notes.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.text.KeepFenceIntact

/**
 * The one place a caret lands on the wrong side of what it is editing.
 *
 * The transformation itself needs a `TextFieldBuffer` and so belongs on a device; the decision it
 * makes does not, and that is the part that can be wrong.
 */
class KeepFenceIntactTest {

    @Test
    fun `text typed in front of a fence opener is caught`() {
        // Which is what the caret does on an empty header line, every time.
        assertTrue(KeepFenceIntact.opensFenceAt("s```", 0, 1))
        assertTrue(KeepFenceIntact.opensFenceAt("code\nsql```\nbody", 5, 8))
    }

    @Test
    fun `a fence in the middle of a line is not a fence`() {
        // `a ```b``` c` is inline code in prose, and moving anything past it would be vandalism.
        assertFalse(KeepFenceIntact.opensFenceAt("a x```b", 2, 3))
    }

    @Test
    fun `typing anywhere else is left alone`() {
        assertFalse(KeepFenceIntact.opensFenceAt("hello", 0, 1))
        assertFalse(KeepFenceIntact.opensFenceAt("x``", 0, 1))
        // Right at the end of the document, with no room for a fence after it.
        assertFalse(KeepFenceIntact.opensFenceAt("x", 0, 1))
    }

    @Test
    fun `an empty insertion is not an insertion`() {
        assertFalse(KeepFenceIntact.opensFenceAt("```", 0, 0))
    }
}
