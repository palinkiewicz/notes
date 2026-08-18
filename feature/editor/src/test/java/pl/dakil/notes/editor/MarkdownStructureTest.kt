package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdBackspace
import pl.dakil.notes.editor.markdown.MdBlockKind
import pl.dakil.notes.editor.markdown.MdEditAt

/**
 * What a keystroke at the edge of a block means.
 *
 * All of this is invisible on screen — the characters these decisions are about are exactly the
 * ones the formatted view hides — so it is tested here, where the document can be written out in
 * full and the offsets counted.
 */
class MarkdownStructureTest {

    // ---- Backspace ------------------------------------------------------------------------------

    @Test
    fun `backspacing through a language stops at the backticks`() {
        val source = "```sql\nx\n```"
        // Deleting the `l`, the `q` and the `s` is editing the language and is allowed.
        assertEquals(MdBackspace.Allow, MarkdownStructure.backspaceAt(source, 6))
        assertEquals(MdBackspace.Allow, MarkdownStructure.backspaceAt(source, 4))
        // With the word gone the caret sits against the fence itself, and there it stops. The
        // alternative is one backtick fewer and a block that is no longer a block.
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, 3))
    }

    @Test
    fun `backspacing under a code block takes the whole block`() {
        // The bug this replaced: the closing fence went, the table below lost a few characters to
        // the same keystroke, and what was left parsed as neither.
        val source = "```\nx\n```\n| a |\n| --- |"
        val table = source.indexOf("| a |")
        assertEquals(MdBackspace.Remove(0, table), MarkdownStructure.backspaceAt(source, table))
    }

    @Test
    fun `backspacing under a rule takes the rule`() {
        val source = "para\n---\nnext"
        val next = source.indexOf("next")
        assertEquals(MdBackspace.Remove(5, next), MarkdownStructure.backspaceAt(source, next))
    }

    @Test
    fun `backspacing under a table takes the table`() {
        val source = "| a |\n| --- |\n| 1 |\nafter"
        val after = source.indexOf("after")
        assertEquals(MdBackspace.Remove(0, after), MarkdownStructure.backspaceAt(source, after))
    }

    @Test
    fun `a block cannot be dismantled from above either`() {
        // Joining a fence's opening line onto the paragraph above it makes the backticks inline
        // code in the middle of a sentence, which is a stranger outcome than nothing happening.
        val source = "para\n```\nx\n```"
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, 5))
    }

    @Test
    fun `two lines of code can still be joined`() {
        // Inside a fence the lines are the user's own text, and joining them is an ordinary edit.
        val source = "```\none\ntwo\n```"
        assertEquals(MdBackspace.Allow, MarkdownStructure.backspaceAt(source, source.indexOf("two")))
        // The line that would swallow the closing fence is not ordinary, though.
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, source.lastIndexOf("```")))
    }

    @Test
    fun `a table's pipes are borders rather than characters`() {
        val source = "| ab |\n| --- |"
        // On screen there is a drawn line where the pipe is; backspacing over it would delete a
        // column boundary the user cannot see.
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, 1))
        // The cell's own text is text.
        assertEquals(MdBackspace.Allow, MarkdownStructure.backspaceAt(source, 4))
    }

    @Test
    fun `ordinary prose is left entirely alone`() {
        val source = "# Heading\n\nSome **bold** words."
        for (caret in 1..source.length) {
            assertEquals("at $caret", MdBackspace.Allow, MarkdownStructure.backspaceAt(source, caret))
        }
    }

    // ---- Enter in a table ------------------------------------------------------------------------

    @Test
    fun `enter in a row opens a blank one under it`() {
        val source = "| a | bb |\n| --- | --- |\n| 1 | 2 |"
        val caret = source.lastIndexOf("2")
        val edit = MarkdownStructure.rowBelow(source, caret)!!
        assertEquals(
            listOf("| a | bb |", "| --- | --- |", "| 1 | 2 |", "|   |   |"),
            apply(source, edit).lines(),
        )
        // The new row is a blank copy of the old, so its pipes stand in the same columns.
        assertEquals(source.lines()[2].length, apply(source, edit).lines()[3].length)
    }

    @Test
    fun `enter lands the caret in the column it was pressed in`() {
        val source = "| a | bb |\n| --- | --- |\n| 1 | 2 |"
        val second = MarkdownStructure.rowBelow(source, source.lastIndexOf("2"))!!
        val first = MarkdownStructure.rowBelow(source, source.lastIndexOf("1"))!!
        val row = apply(source, second).lastIndexOf('\n') + 1

        // The caret lands in the cell under the one it was in, where that cell's text starts.
        assertEquals(6, second.caret - row)
        assertEquals(2, first.caret - row)
    }

    @Test
    fun `enter in the header opens the first body row`() {
        // Not a row between the header and the dashes, which would stop the table parsing.
        val source = "| a | b |\n| --- | --- |"
        val edit = MarkdownStructure.rowBelow(source, 3)!!
        assertEquals(listOf("| a | b |", "| --- | --- |", "|   |   |"), apply(source, edit).lines())
    }

    @Test
    fun `enter outside a table is not our business`() {
        assertNull(MarkdownStructure.rowBelow("just a paragraph", 4))
        // Nor is a line that merely has a pipe in it.
        assertNull(MarkdownStructure.rowBelow("a | b\nmore", 3))
    }

    // ---- The plus buttons on a border --------------------------------------------------------

    @Test
    fun `a column is added to every row at once`() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val table = MarkdownStructure.blocks(source).single()
        assertEquals(MdBlockKind.TABLE, table.kind)

        // Border 1 is the line between the two columns, so the new one opens there.
        val middle = MarkdownStructure.addColumn(source, table, 1)
        assertEquals(
            listOf("| a | | b |", "| --- | --- | --- |", "| 1 | | 2 |"),
            apply(source, middle).lines(),
        )
        // The dashes matter: a delimiter row one cell short stops the table being a table.
        val end = MarkdownStructure.addColumn(source, table, 2)
        assertEquals(
            listOf("| a | b | |", "| --- | --- | --- |", "| 1 | 2 | |"),
            apply(source, end).lines(),
        )
    }

    @Test
    fun `a column added at the edge lands the caret in it`() {
        val source = "| a | b |\n| --- | --- |"
        val table = MarkdownStructure.blocks(source).single()
        val edit = MarkdownStructure.addColumn(source, table, 0)
        // One space in, where the cell's text goes.
        assertEquals(2, edit.caret)
        assertEquals(listOf("| | a | b |", "| --- | --- | --- |"), apply(source, edit).lines())
    }

    @Test
    fun `a row can be added above the header or under any row`() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val table = MarkdownStructure.blocks(source).single()

        assertEquals(
            listOf("|   |   |", "| a | b |", "| --- | --- |", "| 1 | 2 |"),
            apply(source, MarkdownStructure.addRow(source, table, 0)).lines(),
        )
        // Border 1 is the line under the header, and the dashes are not a row it can go above.
        assertEquals(
            listOf("| a | b |", "| --- | --- |", "|   |   |", "| 1 | 2 |"),
            apply(source, MarkdownStructure.addRow(source, table, 1)).lines(),
        )
        assertEquals(
            listOf("| a | b |", "| --- | --- |", "| 1 | 2 |", "|   |   |"),
            apply(source, MarkdownStructure.addRow(source, table, 2)).lines(),
        )
    }

    // ---- Cells as fields ---------------------------------------------------------------------

    @Test
    fun `a caret dropped in a cell's padding lands in its text`() {
        val source = "| a   | b |\n| --- | --- |"
        // Two columns of padding to the right of `a`: a caret there reads as a cursor floating in
        // the middle of an empty field, and types a character that then jumps left.
        assertEquals(3, MarkdownStructure.cellCaret(source, 5))
        // Already on the text, so nothing to correct.
        assertNull(MarkdownStructure.cellCaret(source, 2))
        assertNull(MarkdownStructure.cellCaret(source, 3))
    }

    @Test
    fun `an empty cell takes the caret at its left edge`() {
        val source = "|     | b |\n| --- | --- |"
        // One space in from the pipe, which is where a cell's text starts.
        assertEquals(2, MarkdownStructure.cellCaret(source, 4))
    }

    @Test
    fun `a caret outside a table is left where it was`() {
        assertNull(MarkdownStructure.cellCaret("plain text", 4))
        // The delimiter row is not on screen, so a caret there was not placed by anybody.
        val source = "| a | b |\n| --- | --- |"
        assertNull(MarkdownStructure.cellCaret(source, source.indexOf("---") + 1))
    }

    @Test
    fun `a cell knows its row and column`() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val header = MarkdownStructure.cellAt(source, source.indexOf("b"))!!
        assertEquals(0, header.row)
        assertEquals(1, header.column)

        val body = MarkdownStructure.cellAt(source, source.indexOf("1"))!!
        // Row 1 is the first body row: the delimiter between them is not a row.
        assertEquals(1, body.row)
        assertEquals(0, body.column)
        assertNull(MarkdownStructure.cellAt("no table here", 3))
    }

    // ---- Taking rows and columns away --------------------------------------------------------

    @Test
    fun `removing a row leaves the rest of the table standing`() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"
        val table = MarkdownStructure.blocks(source).single()
        assertEquals(
            listOf("| a | b |", "| --- | --- |", "| 3 | 4 |"),
            apply(source, MarkdownStructure.removeRow(source, table, 1)).lines(),
        )
    }

    @Test
    fun `removing the header promotes the row under it`() {
        // The dashes have to stay under whichever row is first, or the table stops parsing. Cutting
        // the header's line on its own would leave them on top.
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val table = MarkdownStructure.blocks(source).single()
        assertEquals(
            listOf("| 1 | 2 |", "| --- | --- |"),
            apply(source, MarkdownStructure.removeRow(source, table, 0)).lines(),
        )
    }

    @Test
    fun `removing a column rewrites every row`() {
        val source = "| a | b | c |\n| --- | --- | --- |\n| 1 | 2 | 3 |"
        val table = MarkdownStructure.blocks(source).single()
        assertEquals(
            listOf("| a | c |", "| --- | --- |", "| 1 | 3 |"),
            apply(source, MarkdownStructure.removeColumn(source, table, 1)).lines(),
        )
    }

    @Test
    fun `the last row or column takes the whole table with it`() {
        // A table of nothing is not a table, and leaving the dashes behind as a paragraph of
        // hyphens would be worse than either.
        val single = "| a |\n| --- |"
        val table = MarkdownStructure.blocks(single).single()
        assertEquals("", apply(single, MarkdownStructure.removeRow(single, table, 0)))
        assertEquals("", apply(single, MarkdownStructure.removeColumn(single, table, 0)))

        val kept = "before\n| a |\n| --- |\nafter"
        val only = MarkdownStructure.blocks(kept).single()
        assertEquals("before\nafter", apply(kept, MarkdownStructure.removeTable(kept, only)))
    }

    // ---- Blocks ------------------------------------------------------------------------------

    @Test
    fun `an unclosed fence still ends somewhere`() {
        // Every document is half-typed at some point, and a span that ran past the end would take
        // the offsets of everything measured from it with it.
        val source = "```kotlin\nval x = 1"
        val fence = MarkdownStructure.blocks(source).single()
        assertEquals(MdBlockKind.FENCE, fence.kind)
        assertEquals(source.length, fence.end)
        assertEquals(false, fence.closed)
    }

    @Test
    fun `blocks do not overlap and stay inside the document`() {
        val source = """
            para
            ---
            ```sql
            select 1
            ```
            | a | b |
            | --- | --- |
            | 1 | 2 |
            tail
        """.trimIndent()
        var previous = 0
        for (block in MarkdownStructure.blocks(source)) {
            assert(block.start >= previous) { "$block overlaps what came before it" }
            assert(block.end <= source.length) { "$block runs past the end" }
            previous = block.end
        }
        assertEquals(
            listOf(MdBlockKind.RULE, MdBlockKind.FENCE, MdBlockKind.TABLE),
            MarkdownStructure.blocks(source).map { it.kind },
        )
    }

    private fun apply(source: String, edit: MdEditAt): String =
        source.substring(0, edit.start) + edit.text + source.substring(edit.end)
}
