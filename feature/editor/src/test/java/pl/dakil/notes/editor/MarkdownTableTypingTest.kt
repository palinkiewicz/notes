package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdEditAt
import pl.dakil.notes.editor.markdown.MdTable

/**
 * Typing into a table cell the way one types into any field: words with spaces between them, and
 * a pipe that is a character rather than a new column.
 */
class MarkdownTableTypingTest {

    private val table = "| h |\n|---|\n| x |"
    private val afterX = table.lastIndexOf('x') + 1

    /** Types [text] at [caret] and moves the caret the way the editor would, returning both. */
    private fun type(source: String, caret: Int, text: String): Pair<String, Int> {
        var doc = source
        var at = caret
        for (c in text) {
            val before = doc
            doc = doc.substring(0, at) + c + doc.substring(at)
            at += 1
            if (!MarkdownStructure.caretPlacedByTyping(before, doc, at)) {
                MarkdownStructure.cellCaret(doc, at)?.let { at = it }
            }
        }
        return doc to at
    }

    @Test
    fun `a space typed at the end of a cell's text stays where it was typed`() {
        // The trailing blank is not on screen, and a caret in the blanks after a cell's text is
        // pulled back to the text — which is right for a tap that missed and wrong for a space the
        // user just typed: the next letter went in front of it, and "x y" came out as "xy ".
        val withSpace = table.substring(0, afterX) + " " + table.substring(afterX)
        assertNotNull(MarkdownStructure.cellCaret(withSpace, afterX + 1))
        assertTrue(MarkdownStructure.caretPlacedByTyping(table, withSpace, afterX + 1))

        val (doc, _) = type(table, afterX, " y z")
        assertTrue(doc, doc.contains("| x y z |"))
    }

    @Test
    fun `a tap in a cell's padding is still pulled back to its text`() {
        val padded = "| h |\n|---|\n| x    |"
        val inPadding = padded.lastIndexOf('|') - 1
        assertTrue(MarkdownStructure.cellCaret(padded, inPadding) != null)
        assertTrue(!MarkdownStructure.caretPlacedByTyping(padded, padded, inPadding))
    }

    @Test
    fun `a pipe typed into a cell is written escaped`() {
        assertEquals("\\|", MarkdownStructure.escapeTypedPipes(table, afterX, "|"))
        assertEquals("a\\|b", MarkdownStructure.escapeTypedPipes(table, afterX, "a|b"))

        val doc = table.substring(0, afterX) + "\\|" + table.substring(afterX)
        // Still one column, and the reader sees the pipe without its backslash.
        assertEquals(2, MarkdownRenderer.plan(doc).decorations.filterIsInstance<MdTable>().single().columnStops.size)
        assertTrue(rendered(doc).lines().last().contains("x|"))
    }

    @Test
    fun `pipes are left alone outside a table, on the delimiter row and after a backslash`() {
        assertNull(MarkdownStructure.escapeTypedPipes("plain text", 5, "|"))
        assertNull(MarkdownStructure.escapeTypedPipes(table, table.indexOf("---") + 1, "|"))
        // A pipe typed after a hand-written backslash is already escaped; a second backslash would
        // escape the first one and turn the pipe back into a column.
        val slashed = table.substring(0, afterX) + "\\" + table.substring(afterX)
        assertNull(MarkdownStructure.escapeTypedPipes(slashed, afterX + 1, "|"))
        // Several lines are a paste of somebody's own structure.
        assertNull(MarkdownStructure.escapeTypedPipes(table, afterX, "a|\nb"))
    }

    @Test
    fun `backspace takes an escaped character's backslash with it`() {
        // Otherwise deleting the pipe leaves a backslash on screen that nobody typed.
        val doc = "| a \\| b |\n|---|\n| x |"
        val pipe = doc.indexOf("\\|") + 1
        assertEquals(MdEditAt(pipe - 1, pipe + 1, "", pipe - 1), MarkdownStructure.backspaceTarget(doc, pipe + 1))
        assertEquals(MdEditAt(0, 2, "", 0), MarkdownStructure.backspaceTarget("\\*x", 2))
    }

    // ---- What a typed space looks like ------------------------------------------------------

    /** What the field shows for [source] with the caret at [caret], and where it draws the caret. */
    private fun shown(source: String, caret: Int): Pair<String, Int> {
        val plan = MarkdownRenderer.plan(source, MarkdownRenderer.activeCell(source, caret))
        val out = StringBuilder(source)
        for (edit in plan.edits.asReversed()) out.replace(edit.start, edit.end, edit.replacement)
        // A caret at an insertion is drawn in front of it, as the text field draws it (start
        // affinity) — the side the paragraph gaps are built around too.
        return out.toString() to MarkdownRenderer.transformedOffset(plan.edits, caret, includeInsertionAt = false)
    }

    @Test
    fun `a space typed after a cell's text is on screen and the caret is drawn after it`() {
        // Squared off, the space gave no feedback at all until the next letter arrived.
        val doc = "| h |\n|---|\n| x  |"
        val caret = doc.lastIndexOf("x") + 2
        assertTrue(MarkdownRenderer.activeCell(doc, caret) >= 0)
        val (text, drawn) = shown(doc, caret)
        assertEquals(" ", text.substring(drawn - 1, drawn))
        assertEquals("x", text.substring(drawn - 2, drawn - 1))
    }

    @Test
    fun `a space typed into a cell written without blanks is on screen too`() {
        val doc = "|h|\n|-|\n|x |"
        val caret = doc.lastIndexOf("x") + 2
        val (text, drawn) = shown(doc, caret)
        assertEquals("x ", text.substring(drawn - 2, drawn))
    }

    @Test
    fun `cells the caret is not in keep their squared-off look`() {
        // Only the cell being typed in is drawn as typed; the rest of the table does not shift.
        val doc = "| h |\n|---|\n| x  |"
        assertEquals(listOf("h", "x"), rendered(doc).lines().map { it.trim() })
        assertEquals(-1, MarkdownRenderer.activeCell(doc, 2))
    }

    @Test
    fun `a cell written the usual way renders exactly as before`() {
        // `| a |` keeps its look: one blank either side is the separator, not content.
        val doc = "| h |\n|---|\n| x |"
        assertEquals(listOf("h", "x"), rendered(doc).lines().map { it.trim() })
        val x = doc.lastIndexOf('x')
        assertTrue(MarkdownRenderer.hidesAnythingIn(doc, x + 1, x + 2))
    }

    @Test
    fun `backspace right after a typed space takes the space, not the letter`() {
        val doc = "| h |\n|---|\n| x  |"
        val caret = doc.lastIndexOf("x") + 2
        val target = MarkdownStructure.backspaceTarget(doc, caret)
        // Either left to the field (null) or aimed at the space — never at the x.
        assertTrue("$target", target == null || target == MdEditAt(caret - 1, caret, "", caret - 1))
        assertTrue(!MarkdownRenderer.hidesAnythingIn(doc, caret - 1, caret))
    }

    // ---- Deleting an escaped character ------------------------------------------------------

    @Test
    fun `deleting an escaped pipe takes its backslash whatever range the keyboard asked for`() {
        // A keyboard that deletes just the `|` left the hidden backslash behind, on screen.
        val doc = "| x\\|z |\n|---|\n| a |"
        val pipe = doc.indexOf('|', 2)
        assertEquals(pipe - 1 to pipe + 1, MarkdownStructure.keepEscapesWhole(doc, pipe, pipe + 1))
        // And one that deletes just the hidden backslash would turn the pipe into a column.
        assertEquals(pipe - 1 to pipe + 1, MarkdownStructure.keepEscapesWhole(doc, pipe - 1, pipe))
        // Already whole, or nothing to do with an escape: left alone.
        assertNull(MarkdownStructure.keepEscapesWhole(doc, pipe - 1, pipe + 1))
        assertNull(MarkdownStructure.keepEscapesWhole(doc, pipe + 1, pipe + 2))
    }

    @Test
    fun `a backslash inside code is not an escape and deletes alone`() {
        val doc = "`a\\|b`"
        val pipe = doc.indexOf('|')
        assertNull(MarkdownStructure.keepEscapesWhole(doc, pipe, pipe + 1))
    }
}
