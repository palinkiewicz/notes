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
import pl.dakil.notes.editor.markdown.MdPending

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

    // ---- One character, or a request ------------------------------------------------------------

    @Test
    fun `the range a field widens for one backspace still takes one visible character`() {
        // Measured from the field itself. A caret at the end of the bold word in
        // `Apple **is red** now.` is offset 14, and the deletion that comes back for one press is
        // [13, 16) — the "d" the user pointed at and the two closing markers behind it, because a
        // caret standing against a hidden run maps back to the whole of it. One letter goes.
        val source = "Apple **is red** now."
        assertFalse(MarkdownRenderer.takesMoreThanOneVisibleCharacter(source, 13, 16))
        // And the same shape at a heading: the line break above plus the hashes nobody can see.
        assertFalse(MarkdownRenderer.takesMoreThanOneVisibleCharacter("Line.\n## Head", 5, 9))
        // A closing fence renders as the blank line below it, so the run holding it counts once.
        assertFalse(MarkdownRenderer.takesMoreThanOneVisibleCharacter("```\nx\n```\n\nafter", 6, 11))
    }

    @Test
    fun `a deletion that clears several visible characters is a request and not a keystroke`() {
        // What an AOSP-derived keyboard sends for backspace-with-a-selection: the selection is
        // collapsed first and then as many characters asked back as it covered, so this arrives
        // looking like a bare caret's keystroke. Judged by what it takes, it plainly is not one.
        val source = "Apple **is red** now."
        assertTrue(MarkdownRenderer.takesMoreThanOneVisibleCharacter(source, 0, source.length))
        assertTrue(MarkdownRenderer.takesMoreThanOneVisibleCharacter(source, 12, 16))
        // Two visible characters is already more than one press can mean, markers between them or not:
        // the "d", the closing pair, the space and the "n" after it.
        assertTrue(MarkdownRenderer.takesMoreThanOneVisibleCharacter(source, 13, 18))
    }

    @Test
    fun `a range granted as aimed still gives back the syntax hanging off its ends`() {
        // A selection dragged to just past the bold word comes back with the closing markers on it,
        // because a caret against a hidden run maps to the whole run. Deleting it as given would
        // take a pair the user never saw and unstyle whatever was left wearing it.
        val source = "Apple **is red** now."
        assertEquals(8 to 14, MarkdownRenderer.visibleSpan(source, 6, 16))
        // Syntax *between* the first and last visible character is theirs to lose: they selected
        // across it, and what is left of a run whose middle has gone is not a run.
        assertEquals(4 to 18, MarkdownRenderer.visibleSpan(source, 4, 18))
        // Nothing on screen in the range at all — no span to grant.
        assertNull(MarkdownRenderer.visibleSpan(source, 6, 8))
    }

    @Test
    fun `syntax on its own is not a visible character at all`() {
        // The markers alone, and then a range with nothing in it: neither takes anything off screen.
        assertTrue(MarkdownRenderer.takesMoreThanOneVisibleCharacter("**bold**", 2, 6))
        assertFalse(MarkdownRenderer.takesMoreThanOneVisibleCharacter("**bold**", 0, 2))
        assertFalse(MarkdownRenderer.takesMoreThanOneVisibleCharacter("**bold**", 4, 4))
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

    // ---- A line break through the middle of formatting --------------------------------------

    @Test
    fun `a break inside a bold run ends it and starts it again`() {
        // `**bo` and `ld**` on two lines is not bold text broken in half — it is two plain lines
        // wearing four asterisks that were invisible until the moment Enter was pressed.
        val fix = MarkdownStructure.lineBreak("**bold**", 4, "\n")!!
        assertEquals("**bo**\n**ld**", apply("**bold**", fix.edit))
        // Inside the reopened markers, where what is typed next is still bold.
        assertEquals(9, fix.edit.caret)
        assertTrue(fix.carry.isEmpty())
    }

    @Test
    fun `a break at the end of a run leaves no empty pair behind`() {
        // `**bold**\n****` would be the naive answer, and `****` is not markup at all: it styles
        // nothing, so nothing hides it and four asterisks appear on the new line.
        val fix = MarkdownStructure.lineBreak("**bold**", 6, "\n")!!
        assertEquals("**bold**\n", apply("**bold**", fix.edit))
        // Nothing was written down, so the boldness rides on the caret instead.
        assertEquals(listOf(MdPending.Wrap("**")), fix.carry)
    }

    @Test
    fun `a break at the start of a run moves the whole run down`() {
        val fix = MarkdownStructure.lineBreak("**bold**", 2, "\n")!!
        assertEquals("\n**bold**", apply("**bold**", fix.edit))
        assertTrue(fix.carry.isEmpty())
    }

    @Test
    fun `every run open at the break is closed and reopened, innermost first`() {
        // Closing them in the order they were opened would cross the markers over —
        // `**bo*ld**` closes the italic with the bold's asterisks and neither survives.
        val source = "**bold *and italic* text**"
        val fix = MarkdownStructure.lineBreak(source, 12, "\n")!!
        // The closers land against "and" rather than against the space after it. `*and ***` closes
        // nothing — emphasis does not end on a marker preceded by whitespace — so the space stays
        // behind on the line and the markers go in front of it.
        assertEquals("**bold *and*** \n***italic* text**", apply(source, fix.edit))
    }

    @Test
    fun `a break inside a code span reopens the backticks`() {
        val fix = MarkdownStructure.lineBreak("`co de`", 3, "\n")!!
        assertEquals("`co`\n` de`", apply("`co de`", fix.edit))
    }

    @Test
    fun `a break inside a sized run tags both halves`() {
        // Not a marker that can be doubled up: the closing half carries the size, so both halves
        // have to be written out in full or the second one is at the document's own size.
        val source = "[big text]{size=24}"
        val fix = MarkdownStructure.lineBreak(source, 5, "\n")!!
        assertEquals("[big ]{size=24}\n[text]{size=24}", apply(source, fix.edit))
    }

    @Test
    fun `the new line's list marker comes before the reopened styling`() {
        // A bullet has to start its line to be a bullet at all, so whatever `ContinueList` wrote
        // goes down first and the markers follow it.
        val source = "- **bold**"
        val fix = MarkdownStructure.lineBreak(source, 6, "\n- ")!!
        assertEquals("- **bo**\n- **ld**", apply(source, fix.edit))
    }

    @Test
    fun `a break in plain text is the field's own business`() {
        assertNull(MarkdownStructure.lineBreak("plain text", 5, "\n"))
        // Beside a run rather than inside it: the whole run goes down to the next line intact.
        assertNull(MarkdownStructure.lineBreak("**bold** here", 8, "\n"))
    }

    @Test
    fun `a break inside nested runs keeps every one of them`() {
        // The reported case, built with the formatting bar one style at a time.
        val source = "test**1*2~3`4`~***"
        val fix = MarkdownStructure.lineBreak(source, 9, "\n")!!
        assertEquals("test**1*2***\n***~3`4`~***", apply(source, fix.edit))
        // And every marker is still doing a job, so every one of them is still off screen. That is
        // the whole complaint: a keystroke that added a line put ten asterisks on the page.
        val shown = MarkdownRenderer.render(apply(source, fix.edit))
        assertFalse('*' in shown)
        assertFalse('`' in shown)
    }

    // ---- Text typed out of the run it was typed into ------------------------------------------

    @Test
    fun `a run split round typed text closes before it and opens after it`() {
        // The middle is handed over as-is: what it says is the caller's business, and all this does
        // is make sure it stands outside the run rather than inside it.
        val fix = MarkdownStructure.splitOpenRuns("**boXld**", 4, 5, "X", 1)!!
        assertEquals("**bo**X**ld**", apply("**boXld**", fix))
        assertEquals(7, fix.caret)
    }

    @Test
    fun `typed text at the end of a run is left standing outside it`() {
        // The whole complaint: wrapping it where it stands gives `**bold**X****`, and `****` is
        // four asterisks on screen rather than an empty bold run.
        val fix = MarkdownStructure.splitOpenRuns("**boldX**", 6, 7, "X", 1)!!
        assertEquals("**bold**X", apply("**boldX**", fix))
        assertEquals(9, fix.caret)
    }

    @Test
    fun `typed text at the start of a run is left in front of it`() {
        val fix = MarkdownStructure.splitOpenRuns("**Xbold**", 2, 3, "X", 1)!!
        assertEquals("X**bold**", apply("**Xbold**", fix))
        assertEquals(1, fix.caret)
    }

    @Test
    fun `text typed where no run is open is the field's own business`() {
        assertNull(MarkdownStructure.splitOpenRuns("plain text", 5, 6, "X", 1))
        // Markers typed into a run change what the run *is* — here they close it early, so the two
        // ends no longer stand inside the same one and there is no pair of halves to make.
        assertNull(MarkdownStructure.splitOpenRuns("**bo**ld**", 4, 6, "**", 2))
    }

    @Test
    fun `the styles in force at a caret name themselves`() {
        // Read off the parse, so `***` answers as the one span it is rather than as two.
        assertEquals(listOf(MdPending.Wrap("***")), MarkdownStructure.stylesOpenAt("***a***", 4))
        assertEquals(listOf(MdPending.Wrap("`")), MarkdownStructure.stylesOpenAt("`c`", 2))
        assertEquals(listOf(MdPending.Size(24)), MarkdownStructure.stylesOpenAt("[a]{size=24}", 2))
        assertEquals(emptyList<MdPending>(), MarkdownStructure.stylesOpenAt("plain", 3))
    }

    // ---- The space that ends a word ------------------------------------------------------------

    @Test
    fun `a space typed against a closing marker goes past it`() {
        // `**test **` closes nothing — emphasis does not end on a marker preceded by whitespace —
        // so the space that ends a bold word used to unbold the word.
        val fix = MarkdownStructure.spaceOutsideRun("**test**", 6, " ")!!
        assertEquals("**test** ", apply("**test**", fix))
        assertEquals(9, fix.caret)
    }

    @Test
    fun `a space goes past every marker that ends where it was typed`() {
        // Leaving it between two closers would only move the problem out to the wider one.
        val source = "**a *b***"
        val fix = MarkdownStructure.spaceOutsideRun(source, 6, " ")!!
        assertEquals("**a *b*** ", apply(source, fix))
    }

    @Test
    fun `a space in the middle of a run is an ordinary space`() {
        // Nothing to protect and nothing to move: rewriting this would be rewriting what somebody
        // wrote.
        assertNull(MarkdownStructure.spaceOutsideRun("**test**", 4, " "))
        assertNull(MarkdownStructure.spaceOutsideRun("plain text", 5, " "))
        // A code span may hold a space against its backtick, so it is left out of this.
        assertNull(MarkdownStructure.spaceOutsideRun("`code`", 5, " "))
    }

    // ---- The space a deletion uncovers -----------------------------------------------------------

    @Test
    fun `backspacing the last letter off a word exposes the space in front of it`() {
        // "**Apple is red**" with "red" backspaced down to "r" and then that "r" removed too — the
        // last of those keystrokes is the one that would otherwise leave "**Apple is **", a closer
        // preceded by whitespace and therefore not a closer at all.
        val source = "**Apple is r**"
        val fix = MarkdownStructure.spaceOutsideRunAfterDeletion(source, 11, 12)!!
        assertEquals("**Apple is** ", apply(source, fix))
        assertEquals(13, fix.caret)
    }

    @Test
    fun `a whole word deleted at once uncovers the space the same way`() {
        // One selection delete rather than three backspaces, landing on the same result: the space
        // that used to separate the two words is what a closer now touches, wherever the deletion
        // that exposed it started.
        val source = "**Apple is red**"
        val fix = MarkdownStructure.spaceOutsideRunAfterDeletion(source, 11, 14)!!
        assertEquals("**Apple is** ", apply(source, fix))
    }

    @Test
    fun `a deletion that does not reach a closer is an ordinary deletion`() {
        assertNull(MarkdownStructure.spaceOutsideRunAfterDeletion("**Apple is red**", 10, 11))
        assertNull(MarkdownStructure.spaceOutsideRunAfterDeletion("plain text", 5, 6))
    }

    @Test
    fun `a deletion that does not expose whitespace is left alone`() {
        // Backspacing "d" off "red" leaves "re", not a space — the run is merely shorter.
        assertNull(MarkdownStructure.spaceOutsideRunAfterDeletion("**Apple is red**", 13, 14))
    }

    @Test
    fun `deleting the first word of a run moves its opener past the space left behind`() {
        // `** my string**` opens on a space, which CommonMark does not read as emphasis at all — so
        // the run the user meant to shorten stops being a run.
        val source = "**This is my string**"
        val fix = MarkdownStructure.openerOutsideRunAfterDeletion(source, 2, 9)!!
        assertEquals(" **my string**", apply(source, fix))
        // Inside the run, ready to carry on in the same style.
        assertEquals(3, fix.caret)
    }

    @Test
    fun `an opener steps past every marker it is nested in`() {
        // The same rule as its mirror: leaving the space between two openers only moves the problem.
        val source = "**_this word_**"
        val fix = MarkdownStructure.openerOutsideRunAfterDeletion(source, 3, 7)!!
        assertEquals(" **_word_**", apply(source, fix))
    }

    @Test
    fun `a deletion that leaves an opener against text is an ordinary deletion`() {
        // "This" out of the middle leaves `**is my string**` — an opener against a letter, which is
        // exactly where an opener belongs.
        assertNull(MarkdownStructure.openerOutsideRunAfterDeletion("**This is my string**", 2, 7))
        assertNull(MarkdownStructure.openerOutsideRunAfterDeletion("plain text here", 0, 6))
    }

    @Test
    fun `a deletion that empties a run leaves the pair for the stranding rule`() {
        // "a b" out of `**a b**` closes the markers up against each other, and `****` is four
        // asterisks rather than an empty run. Nothing here to relocate — see [strandedMarkers].
        assertNull(MarkdownStructure.openerOutsideRunAfterDeletion("**a b**", 2, 5))
    }

    @Test
    fun `nothing opens when the deletion's own document never had a run to begin with`() {
        // "** w**" never parses as bold at all — the opener is followed by whitespace, which is not
        // a place emphasis can open — so there is no span here for the function to have opinions
        // about, whatever the deletion.
        assertNull(MarkdownStructure.spaceOutsideRunAfterDeletion("** w**", 4, 5))
    }

    @Test
    fun `the run a caret has just stepped out of is the one it can carry on`() {
        val span = MarkdownStructure.extendableRun("**test** m", 9, "**")!!
        assertEquals(6, span.closeStart)
        // Only across whitespace, and only for the same marker.
        assertNull(MarkdownStructure.extendableRun("**test** and m", 13, "**"))
        assertNull(MarkdownStructure.extendableRun("**test** m", 9, "*"))
        // And never across a line break, which inline markup cannot cross: the run would end up
        // opened on one line and closed on the next, which unstyles both.
        assertNull(MarkdownStructure.extendableRun("**test**\nm", 9, "**"))
    }

    // ---- A caret at the end of a run -----------------------------------------------------------

    @Test
    fun `a caret past a run's closing markers belongs inside them`() {
        // Both offsets are the end of the bold word as far as the reader is concerned, and which
        // one a tap produces is not something anybody can aim at. Inside is the one that behaves:
        // what is typed wears what the character to its left wears.
        assertEquals(7, MarkdownStructure.runCaret("**Apple**", 9))
        // Stranded between the two asterisks, which an arrow key can manage at the end of a line.
        assertEquals(7, MarkdownStructure.runCaret("**Apple**", 8))
        // Already there.
        assertNull(MarkdownStructure.runCaret("**Apple**", 7))
    }

    @Test
    fun `the caret comes to rest in the innermost run it was standing at the end of`() {
        // Pulling back past the bold closer lands it on the italic one, and the walk repeats.
        assertEquals(6, MarkdownStructure.runCaret("**a *b***", 9))
    }

    @Test
    fun `a caret with no run behind it is left where it was put`() {
        assertNull(MarkdownStructure.runCaret("plain text", 5))
        // A heading's marker is hidden too, and is nothing to do with this: moving a caret back
        // over `# ` would put what came next in front of the hash and take the heading apart.
        assertNull(MarkdownStructure.runCaret("# Head", 2))
        // Well past the run, with visible text since.
        assertNull(MarkdownStructure.runCaret("**Apple** more", 12))
    }

    @Test
    fun `the space parked outside a run is not a place the caret is pulled back from`() {
        // [KeepSpaceOutside] leaves the caret exactly here, and dragging it back inside the markers
        // would undo the move on the very next frame.
        assertNull(MarkdownStructure.runCaret("**test** ", 9))
    }

    // ---- An edit that reached over syntax nobody can see ---------------------------------------

    @Test
    fun `an edit that would eat a hidden marker is pulled clear of it`() {
        // What a keyboard asks for when it corrects the one word it can see inside `**Aple**`: the
        // offsets it names are mapped back over the markers at both ends.
        val fix = MarkdownStructure.keepMarkers("**Aple**", 0, 8, "Apple")!!
        assertEquals("**Apple**", apply("**Aple**", fix))
        // Between the word and the closing marker, or the next keystroke leaves the run.
        assertEquals(7, fix.caret)
    }

    @Test
    fun `the space a suggestion brings with it is left outside the markers`() {
        // Accepting a correction writes the word and the space after it together. Putting that
        // back between the markers gives `**Apple **`, and a closing pair against whitespace is
        // not a closing pair — the same rule that keeps a space out of what the bar wraps.
        val fix = MarkdownStructure.keepMarkers("**Aple**", 0, 8, "Apple ")!!
        assertEquals("**Apple** ", apply("**Aple**", fix))
        // Where the keyboard thinks it left the caret: past the space it just wrote.
        assertEquals(10, fix.caret)
    }

    @Test
    fun `an edit clear of hidden markers is left exactly as it was`() {
        assertNull(MarkdownStructure.keepMarkers("**Aple**", 2, 6, "Apple"))
        assertNull(MarkdownStructure.keepMarkers("plain text", 0, 5, "X"))
    }

    @Test
    fun `an edit covering nothing but markers writes its text and takes none of them`() {
        // The keyboard asked to replace the closing pair, which it cannot see and cannot have
        // meant. It gets to write what it wrote; the markers are the app's.
        val fix = MarkdownStructure.keepMarkers("**Aple**", 6, 8, "X")!!
        assertEquals("**Aple**X", apply("**Aple**", fix))
    }

    @Test
    fun `an edit may not reach past a marker into the line below`() {
        // The reported case: correcting "Aple" in `**Aple**\nPineapple` came back as a range over
        // the closing pair, the newline *and* the first letter of the next line, and accepting the
        // correction gave `**Appleineapple`. A correction is one word, on one line.
        val source = "**Aple**\nPineapple"
        val fix = MarkdownStructure.keepMarkers(source, 2, 10, "Apple")!!
        assertEquals("**Apple**\nPineapple", apply(source, fix))
        assertEquals(7, fix.caret)
    }

    @Test
    fun `a correction that brings a space with it does not reach past the line either`() {
        val source = "**Aple**\nPineapple"
        val fix = MarkdownStructure.keepMarkers(source, 2, 10, "Apple ")!!
        assertEquals("**Apple** \nPineapple", apply(source, fix))
    }

    @Test
    fun `a hidden heading prefix is not something an edit may swallow`() {
        // The same widening, on a run that is hidden for a quite different reason.
        val fix = MarkdownStructure.keepMarkers("### Head", 0, 8, "Chapter")!!
        assertEquals("### Chapter", apply("### Head", fix))
    }

    private fun apply(source: String, edit: MdEditAt): String =
        source.substring(0, edit.start) + edit.text + source.substring(edit.end)
}
