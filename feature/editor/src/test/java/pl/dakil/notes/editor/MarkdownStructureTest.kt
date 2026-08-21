package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
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
        assertEquals(5, second.caret - row)
        assertEquals(1, first.caret - row)
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
        // Hard against the pipe, which is where a cell's text starts: the blanks an author left
        // inside one are struck out of the rendering and the padding is drawn instead.
        assertEquals(1, MarkdownStructure.cellCaret(source, 4))
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

    @Test
    fun `backspace inside an empty code block removes the block`() {
        // The one place refusing was worse than allowing: a fence with nothing in it shows the user
        // an empty box and no characters, so there is nothing to select and nothing to delete by
        // hand. Every key they pressed inside it was refused and the box stayed for ever.
        val source = "Hello\n```\n\n```\n\n"
        val caret = source.indexOf("```") + 4
        assertEquals(MdBackspace.Remove(6, 15), MarkdownStructure.backspaceAt(source, caret))
    }

    @Test
    fun `backspace inside a code block that has code in it protects the fence`() {
        val source = "Hello\n```\nselect 1\n```\n"
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, source.indexOf("select")))
    }

    @Test
    fun `a code block with a language is not empty even with no code`() {
        // The language word is content the user typed, and losing it to a stray backspace would be
        // the same surprise this is meant to prevent.
        val source = "Hello\n```sql\n\n```\n"
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, source.indexOf("```sql") + 7))
    }

    @Test
    fun `a backspace beside hidden inline syntax means the letter, not the syntax`() {
        // What the field actually deletes for one backspace at the end of `text` is the letter, the
        // closing backtick *and* the space after it — the caret sits among all three and the mapping
        // cannot tell them apart. The span stopped being a span, and the document came out two
        // characters shorter than the keyboard had counted on.
        val source = "Some `text` here"
        assertTrue(MarkdownRenderer.hidesAnythingIn(source, 9, 12))
        assertEquals(9, MarkdownRenderer.lastVisibleBefore(source, 9, 10))
        // The same at the other edge: the space in front of the span goes, the backtick stays.
        assertEquals(4, MarkdownRenderer.lastVisibleBefore(source, 4, 6))
        assertEquals(7, MarkdownRenderer.lastVisibleBefore("a **bold** b", 5, 8))
    }

    @Test
    fun `a marker that is redrawn rather than hidden is still the user's to delete`() {
        // A bullet becomes a glyph and a checkbox becomes a blank: both are on screen, so a
        // backspace aimed at one means it, and the list keys downstream are what handle it.
        assertFalse(MarkdownRenderer.hidesAnythingIn("- milk", 0, 2))
        assertFalse(MarkdownRenderer.hidesAnythingIn("- [ ] milk", 0, 6))
        assertFalse(MarkdownRenderer.hidesAnythingIn("1. milk", 0, 3))
    }

    @Test
    fun `a run that is nothing but hidden syntax has no letter to keep`() {
        // A heading's hashes are hidden whole, so there is no letter on that line to fall back on;
        // what a backspace there means is [MarkdownStructure.backspaceTarget]'s question instead.
        assertNull(MarkdownRenderer.lastVisibleBefore("# Title", 0, 2))
    }

    // ---- What a backspace was aimed at ----------------------------------------------------------

    @Test
    fun `a backspace at the start of a heading takes the blank line and not the heading`() {
        // The bug this is here for: the keyboard asks for `\n### Head` — a break, the markup and
        // four letters of the word — for one press against the front of `Header text`. What the
        // user can see in front of the caret is the paragraph break, and that alone is what goes.
        val source = "Intro paragraph here.\n\n### Header text\n"
        val caret = source.indexOf("Header")
        assertEquals(MdEditAt(22, 23, "", 22), MarkdownStructure.backspaceTarget(source, caret))
    }

    @Test
    fun `a heading joined into the line above loses its hashes rather than showing them`() {
        // With the blank line already gone, the same press joins the two lines — and hashes carried
        // into the middle of a line are no longer markup, so leaving them would spell `###` out in
        // the user's document. They go with the break, the way a merged paragraph loses its style.
        val source = "Intro paragraph here.\n### Header text\n"
        val caret = source.indexOf("Header")
        assertEquals(MdEditAt(21, 26, "", 21), MarkdownStructure.backspaceTarget(source, caret))
        assertEquals(
            "Intro paragraph here.Header text\n",
            apply(source, MarkdownStructure.backspaceTarget(source, caret)!!),
        )
    }

    @Test
    fun `a heading pulled onto a blank line is still a heading`() {
        // Nothing to collide with on an empty line, so the hashes stay where they are and stay
        // markup: the press closes the gap and the heading is still a heading afterwards.
        val source = "Intro.\n\n# Title\n"
        val caret = source.indexOf("Title")
        assertEquals("Intro.\n# Title\n", apply(source, MarkdownStructure.backspaceTarget(source, caret)!!))
    }

    @Test
    fun `a backspace beside inline markup still means the letter next to it`() {
        // Unchanged from what it always did: the letter the caret is against, never the syntax the
        // field swept up on the way to it.
        val source = "Some **bold** and more"
        assertEquals(MdEditAt(10, 11, "", 10), MarkdownStructure.backspaceTarget(source, 11))
        assertEquals(MdEditAt(4, 5, "", 4), MarkdownStructure.backspaceTarget(source, 5))
    }

    @Test
    fun `a backspace with a block behind it is left for the block rules`() {
        // The line below a code block: everything between the caret and the last thing on screen is
        // the block's own hidden machinery, and removing one character of that is not an answer.
        // [backspaceAt] is what handles this edge, so nothing is claimed here.
        val source = "```\ncode\n```\nafter"
        assertNull(MarkdownStructure.backspaceTarget(source, source.indexOf("after")))
    }

    @Test
    fun `a backspace at the very top of a document has nothing to take`() {
        assertNull(MarkdownStructure.backspaceTarget("# Title", 0))
        // In front of the word there is only the heading's own markup, so the answer is an edit
        // that removes nothing rather than one that removes the markup. See the test below.
        assertEquals(MdEditAt(2, 2, "", 2), MarkdownStructure.backspaceTarget("# Title", 2))
    }

    @Test
    fun `a heading at the top of a note has nothing behind it to delete`() {
        // What the user reported: a note opening with a heading, a caret in front of its first
        // word, and one backspace that removed no text and demoted the heading. There is nothing
        // in front of that caret, so the key does nothing — as it does at the start of any note.
        val source = "### Header text\n\nBody."
        assertEquals(MdEditAt(4, 4, "", 4), MarkdownStructure.backspaceTarget(source, 4))
        // The same for a note that opens with a bold word rather than a heading.
        assertEquals(MdEditAt(2, 2, "", 2), MarkdownStructure.backspaceTarget("**Bold**", 2))
    }

    @Test
    fun `a run covering a closing fence is still a caret and not a selection`() {
        // The fence renders as the blank line below the block rather than as nothing at all, which
        // makes it a run of source with one character of rendered text to its name — and still not
        // one character anybody can see. A field reporting a caret there hands back the whole run,
        // and reading that as a selection is what let a backspace eat the fence.
        val source = "```\nx\n```\nafter"
        assertFalse(MarkdownRenderer.coversVisibleText(source, 6, 10))
        assertTrue(MarkdownRenderer.coversVisibleText(source, 4, 5))
    }

    // ---- Markers left holding nothing -------------------------------------------------------------

    @Test
    fun `deleting the last of a bold run takes its markers with it`() {
        // `****` is not an empty bold run to the parser — it is four asterisks — so leaving them
        // behind spells characters into the document that the user never typed.
        val before = "Some **test** here"
        val after = "Some **** here"
        assertEquals(MdEditAt(5, 9, "", 5), MarkdownStructure.strandedMarkers(before, after, 7))
        assertEquals("Some  here", apply(after, MarkdownStructure.strandedMarkers(before, after, 7)!!))
    }

    @Test
    fun `every kind of inline marker is dropped the same way`() {
        assertEquals(MdEditAt(0, 2, "", 0), MarkdownStructure.strandedMarkers("*x*", "**", 1))
        assertEquals(MdEditAt(0, 4, "", 0), MarkdownStructure.strandedMarkers("~~x~~", "~~~~", 2))
        assertEquals(MdEditAt(0, 2, "", 0), MarkdownStructure.strandedMarkers("`x`", "``", 1))
        assertEquals(MdEditAt(0, 6, "", 0), MarkdownStructure.strandedMarkers("***x***", "******", 3))
    }

    @Test
    fun `a run that still has text in it keeps its markers`() {
        // The rule the user asked for, and its whole point: markup goes when the last of the text
        // it covers goes, and never merely because the run got shorter.
        assertNull(MarkdownStructure.strandedMarkers("**ab**", "**a**", 3))
        assertNull(MarkdownStructure.strandedMarkers("**ab**", "**b**", 2))
    }

    @Test
    fun `characters the renderer never hid are the user's own to keep`() {
        // Underscores inside a word are `snake_case` and not emphasis, so the renderer leaves them
        // on screen — which makes them the user's own text, and nothing here has any business
        // tidying them away when what stood between them goes.
        val before = "one_two_three"
        assertNull(MarkdownStructure.strandedMarkers(before, "one__three", 4))
    }

    // ---- Where a caret may rest -----------------------------------------------------------------

    @Test
    fun `a caret dropped in a heading's hashes moves to the word`() {
        // Hidden characters have offsets even though they have no width, so the strip of blank line
        // above a heading maps into the hashes: the caret was drawn up there, the height of that
        // blank line, and every key pressed went somewhere the user could not predict.
        val source = "Intro paragraph here.\n\n### Header text\n"
        assertEquals(27, MarkdownRenderer.visibleCaret(source, 23))
        assertEquals(27, MarkdownRenderer.visibleCaret(source, 25))
        // Where the user can already see it, it stays put.
        assertNull(MarkdownRenderer.visibleCaret(source, 27))
        assertNull(MarkdownRenderer.visibleCaret(source, 22))
        assertNull(MarkdownRenderer.visibleCaret(source, 5))
    }

    @Test
    fun `a caret in the markup a paragraph opens with moves to its first word`() {
        // Not only headings: any line whose first characters render to nothing has the same strip
        // of nowhere above it, and a bold paragraph opens with two of them.
        assertEquals(2, MarkdownRenderer.visibleCaret("**Bold line**\n\nAfter.", 0))
        assertEquals(2, MarkdownRenderer.visibleCaret("~~Struck~~ line", 0))
        assertEquals(1, MarkdownRenderer.visibleCaret("`code` first", 0))
    }

    @Test
    fun `a caret on a line that is markup from end to end is left where it is`() {
        // A closing fence has nowhere better on its own line to put a caret, and moving it off the
        // line would take it out of the block whose edge it is standing on.
        val source = "```\ncode\n```\n"
        assertNull(MarkdownRenderer.visibleCaret(source, 10))
        assertNull(MarkdownRenderer.visibleCaret(source, 11))
    }

    @Test
    fun `a caret behind inline markup is one the user placed`() {
        // In front of a bold run there is a real choice — inside the emphasis or outside it — and
        // both sides of it are beside text the reader can see. Nothing to correct.
        val source = "Some **bold** here"
        assertNull(MarkdownRenderer.visibleCaret(source, 5))
        assertNull(MarkdownRenderer.visibleCaret(source, 11))
    }

    private fun apply(source: String, edit: MdEditAt): String =
        source.substring(0, edit.start) + edit.text + source.substring(edit.end)
}
