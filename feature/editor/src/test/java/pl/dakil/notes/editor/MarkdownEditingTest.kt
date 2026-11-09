package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdEditAt
import pl.dakil.notes.editor.markdown.minimalEdit

/**
 * The editor's own moves — where it puts the caret, and how it writes an action's result back —
 * judged as the pure decisions they are.
 */
class MarkdownEditingTest {

    // ---- Pulling the caret back inside a run ----------------------------------------------------

    @Test
    fun `a caret left past a closing pair the user just typed stays there`() {
        // Typing the last asterisk of `**bold**` by hand leaves the caret past the pair, which is
        // where the user meant it to be. The snap would have pulled it back inside — and everything
        // typed after that, the space included, carried the bold on.
        val before = "**bold*"
        val after = "**bold**"
        assertEquals(6, MarkdownStructure.runCaret(after, 8))
        assertFalse(MarkdownStructure.shouldSnapIntoRun(before, after, 8))
    }

    @Test
    fun `a caret placed by a tap still snaps inside the run`() {
        assertTrue(MarkdownStructure.shouldSnapIntoRun("**bold** x", "**bold** x", 8))
    }

    @Test
    fun `backspacing to the end of a styled word still carries on in its style`() {
        // What every word processor does: the next letter wears what the letter to its left wears.
        assertTrue(MarkdownStructure.shouldSnapIntoRun("**bold**x", "**bold**", 8))
    }

    @Test
    fun `a keyboard committing several characters at once counts as typing`() {
        // A composing keyboard hands over the end of a word and the closer in one commit.
        assertFalse(MarkdownStructure.shouldSnapIntoRun("x **bo", "x **bold**", 10))
    }

    @Test
    fun `with nothing to compare against the caret snaps as before`() {
        assertTrue(MarkdownStructure.shouldSnapIntoRun(null, "**a**", 5))
    }

    // ---- Writing an action back -----------------------------------------------------------------

    @Test
    fun `an action that changes one line hands the field one line's change`() {
        assertEquals(MdEditAt(2, 2, "X", 3), minimalEdit("abc", "abXc"))
        assertEquals(MdEditAt(5, 11, "", 5), minimalEdit("hello world", "hello"))
        assertEquals(MdEditAt(0, 3, "xyz", 3), minimalEdit("abc", "xyz"))
    }

    @Test
    fun `repeated characters come out as one insertion, not two overlapping ranges`() {
        assertEquals(MdEditAt(2, 2, "a", 3), minimalEdit("aa", "aaa"))
        assertEquals(MdEditAt(1, 2, "", 1), minimalEdit("aa", "a"))
    }

    @Test
    fun `no change is no edit`() {
        assertNull(minimalEdit("same", "same"))
    }
}
