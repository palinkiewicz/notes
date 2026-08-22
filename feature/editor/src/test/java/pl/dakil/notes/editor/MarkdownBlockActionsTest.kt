package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownActions.BlockStyle
import pl.dakil.notes.editor.markdown.ContinueList

/**
 * The formatting bar's own transformations.
 *
 * These are what the user is actually pressing — the bar itself only decides which one to call — so
 * "does the numbered-list button produce a numbered list" is answerable here rather than on a
 * device.
 */
class MarkdownBlockActionsTest {

    @Test
    fun `setting a heading style adds its marker`() {
        val result = MarkdownActions.setBlockStyle("hello", 0, 0, BlockStyle.H2)
        assertEquals("## hello", result.text)
    }

    @Test
    fun `setting a style replaces the one already there`() {
        // A menu, not a stack: picking Heading 2 on a quote gives a heading, not a quoted heading.
        val result = MarkdownActions.setBlockStyle("> quoted", 0, 0, BlockStyle.H2)
        assertEquals("## quoted", result.text)
    }

    @Test
    fun `a heading keeps the list marker it was applied to`() {
        // `- # Alpha` is what CommonMark calls a bulleted heading, and it renders as one wherever
        // the file is opened. Picking H2 used to throw the bullet away, which is not what anybody
        // means by "make this line a heading".
        assertEquals("- ## hello", MarkdownActions.setBlockStyle("- hello", 4, 4, BlockStyle.H2).text)
        assertEquals("1. ## hello", MarkdownActions.setBlockStyle("1. hello", 5, 5, BlockStyle.H2).text)
        assertEquals(
            "  - [ ] ### hello",
            MarkdownActions.setBlockStyle("  - [ ] hello", 9, 9, BlockStyle.H3).text,
        )
    }

    @Test
    fun `a list marker keeps the heading it was applied to`() {
        assertEquals("- ## hello", MarkdownActions.toggleBlockStyle("## hello", 4, 4, BlockStyle.BULLET).text)
        // And taking the list back off leaves the heading standing.
        assertEquals(
            "## hello",
            MarkdownActions.toggleBlockStyle("- ## hello", 6, 6, BlockStyle.BULLET).text,
        )
    }

    @Test
    fun `a bulleted heading is reported to both menus at once`() {
        val source = "- # Alpha"
        // The bar's label names the heading, because that is what changed how the line reads.
        assertEquals(BlockStyle.H1, MarkdownActions.blockStyleAt(source, 5))
        assertEquals(BlockStyle.H1, MarkdownActions.paragraphStyleAt(source, 5))
        assertEquals(BlockStyle.BULLET, MarkdownActions.listStyleAt(source, 5))
    }

    @Test
    fun `setting the body style strips whatever marker was there`() {
        assertEquals("hello", MarkdownActions.setBlockStyle("### hello", 0, 0, BlockStyle.PARAGRAPH).text)
        assertEquals("hello", MarkdownActions.setBlockStyle("- hello", 0, 0, BlockStyle.PARAGRAPH).text)
        assertEquals("hello", MarkdownActions.setBlockStyle("- [ ] hello", 0, 0, BlockStyle.PARAGRAPH).text)
    }

    @Test
    fun `a numbered list across several lines actually counts`() {
        val source = "milk\neggs\nbread"
        val result = MarkdownActions.setBlockStyle(source, 0, source.length, BlockStyle.ORDERED)
        assertEquals("1. milk\n2. eggs\n3. bread", result.text)
    }

    @Test
    fun `applying a block style keeps the caret among the words`() {
        // Selecting the whole line and bolding it must not drop the caret in front of the marker.
        val result = MarkdownActions.setBlockStyle("hello", 5, 5, BlockStyle.H1)
        assertEquals("# hello", result.text)
        assertEquals(7, result.selectionStart)
    }

    @Test
    fun `indentation survives a style change`() {
        val result = MarkdownActions.setBlockStyle("    - nested", 0, 0, BlockStyle.ORDERED)
        assertEquals("    1. nested", result.text)
    }

    @Test
    fun `toggling a style twice returns to a plain paragraph`() {
        val once = MarkdownActions.toggleBlockStyle("milk", 0, 0, BlockStyle.BULLET)
        assertEquals("- milk", once.text)
        val twice = MarkdownActions.toggleBlockStyle(once.text, 2, 2, BlockStyle.BULLET)
        assertEquals("milk", twice.text)
    }

    @Test
    fun `the current block style is reported for the caret's own line`() {
        val source = "# Title\n- item\n> quote\n1. one\n- [ ] task\nplain"
        assertEquals(BlockStyle.H1, MarkdownActions.blockStyleAt(source, 2))
        assertEquals(BlockStyle.BULLET, MarkdownActions.blockStyleAt(source, 10))
        assertEquals(BlockStyle.QUOTE, MarkdownActions.blockStyleAt(source, 18))
        assertEquals(BlockStyle.ORDERED, MarkdownActions.blockStyleAt(source, 26))
        assertEquals(BlockStyle.TASK, MarkdownActions.blockStyleAt(source, 34))
        assertEquals(BlockStyle.PARAGRAPH, MarkdownActions.blockStyleAt(source, source.length))
    }

    @Test
    fun `a task item is reported as a task rather than a bullet`() {
        // It matches the bullet pattern too, so the order the two are tested in is load-bearing.
        assertEquals(BlockStyle.TASK, MarkdownActions.blockStyleAt("- [ ] buy milk", 8))
    }

    // ---- Indentation --------------------------------------------------------------------------

    @Test
    fun `indenting moves list items and leaves everything else alone`() {
        val source = "- milk\nplain\n- eggs"
        val result = MarkdownActions.indentList(source, 0, source.length)
        assertEquals("  - milk\nplain\n  - eggs", result.text)
        assertEquals(source, MarkdownActions.outdentList(result.text, 0, result.text.length).text)
    }

    @Test
    fun `outdenting never takes a marker off`() {
        // The marker goes only on a backspace, and only once there is no nesting left to undo.
        assertEquals("- milk", MarkdownActions.outdentList("- milk", 0, 6).text)
    }

    @Test
    fun `the indent buttons know when they would do nothing`() {
        assertTrue(MarkdownActions.canIndent("- milk", 0, 6))
        assertFalse(MarkdownActions.canIndent("plain", 0, 5))
        assertFalse(MarkdownActions.canOutdent("- milk", 0, 6))
        assertTrue(MarkdownActions.canOutdent("  - milk", 0, 8))
    }

    @Test
    fun `backspace at a marker unwinds one level at a time and then unlists`() {
        // Where the caret is: between the marker and the label, on every one of these.
        assertEquals(6, MarkdownActions.listMarkerEnd("    - milk", 6))
        assertEquals(null, MarkdownActions.listMarkerEnd("plain", 3))

        val once = MarkdownActions.unindentOrUnlist("    - milk", 6)
        assertEquals("  - milk", once.text)
        val twice = MarkdownActions.unindentOrUnlist(once.text, 4)
        assertEquals("- milk", twice.text)
        val thrice = MarkdownActions.unindentOrUnlist(twice.text, 2)
        assertEquals("milk", thrice.text)
        // And the caret stays on the label rather than jumping anywhere.
        assertEquals(0, thrice.selectionStart)
    }

    // ---- Insertions ---------------------------------------------------------------------------

    @Test
    fun `a link built from the dialog carries both halves`() {
        val result = MarkdownActions.insertLink("see ", 4, 4, "the docs", "https://example.com")
        assertEquals("see [the docs](https://example.com)", result.text)
        assertEquals(result.text.length, result.selectionStart)
    }

    @Test
    fun `an image is a link with a bang`() {
        val result = MarkdownActions.insertImage("", 0, 0, "a cat", "cat.png")
        assertEquals("![a cat](cat.png)", result.text)
    }

    @Test
    fun `a link replaces the selection it was invoked on`() {
        val result = MarkdownActions.insertLink("see docs", 4, 8, "docs", "https://example.com")
        assertEquals("see [docs](https://example.com)", result.text)
    }

    @Test
    fun `a rule and a table start on lines of their own`() {
        assertEquals("text\n---\n", MarkdownActions.insertRule("text", 4).text)
        assertTrue(MarkdownActions.insertTable("text", 4).text.startsWith("text\n| Column |"))
    }

    @Test
    fun `a rule inserted on an empty line does not add a blank one`() {
        assertEquals("---\n", MarkdownActions.insertRule("", 0).text)
    }

    // ---- The bar's lit buttons -----------------------------------------------------------------

    @Test
    fun `styles stack instead of replacing each other`() {
        // Pressing bold then italic used to read the `**` as the italic button's own marker and
        // remove it, so the word ended up italic and no longer bold.
        val bold = MarkdownActions.toggleWrap("word", 0, 4, "**")
        assertEquals("**word**", bold.text)
        val italic = MarkdownActions.toggleWrap(bold.text, bold.selectionStart, bold.selectionEnd, "*")
        assertEquals("***word***", italic.text)
        val struck = MarkdownActions.toggleWrap(italic.text, italic.selectionStart, italic.selectionEnd, "~~")
        assertEquals("***~~word~~***", struck.text)
    }

    @Test
    fun `each button turns off only its own share of the run`() {
        val italicOff = MarkdownActions.toggleWrap("***word***", 3, 7, "*")
        assertEquals("**word**", italicOff.text)
        val boldOff = MarkdownActions.toggleWrap("***word***", 3, 7, "**")
        assertEquals("*word*", boldOff.text)
    }

    @Test
    fun `order of application does not matter`() {
        val italicFirst = MarkdownActions.toggleWrap("word", 0, 4, "*")
        val thenBold = MarkdownActions.toggleWrap(
            italicFirst.text, italicFirst.selectionStart, italicFirst.selectionEnd, "**",
        )
        assertEquals("***word***", thenBold.text)
    }

    @Test
    fun `markers selected along with the text still toggle off`() {
        // Someone who selected `**word**` by dragging over it means the same thing as someone who
        // selected just the word.
        val result = MarkdownActions.toggleWrap("**word**", 0, 8, "**")
        assertEquals("word", result.text)
    }

    @Test
    fun `an inline style wraps each list item's own words`() {
        // Dragging over two items and pressing strikethrough used to give `- ~~alpha\n- beta~~`,
        // which renders as two literal pairs of tildes: inline syntax does not cross a line break.
        val source = "- alpha\n- beta"
        val struck = MarkdownActions.toggleWrap(source, 2, source.length, "~~")
        assertEquals("- ~~alpha~~\n- ~~beta~~", struck.text)
        // And pressing it again with the selection it left behind takes it off both.
        val plain = MarkdownActions.toggleWrap(struck.text, struck.selectionStart, struck.selectionEnd, "~~")
        assertEquals(source, plain.text)
    }

    @Test
    fun `an inline style never swallows a list marker`() {
        // Selecting a whole item used to give `~~- alpha~~`, which stops being a list item at all.
        assertEquals("- ~~alpha~~", MarkdownActions.toggleWrap("- alpha", 0, 7, "~~").text)
        assertEquals("1. ~~one~~", MarkdownActions.toggleWrap("1. one", 0, 6, "~~").text)
        assertEquals("# ~~Title~~", MarkdownActions.toggleWrap("# Title", 0, 7, "~~").text)
    }

    @Test
    fun `a caret in a list still opens a pair of markers to type into`() {
        val result = MarkdownActions.toggleWrap("- alpha", 4, 4, "**")
        assertEquals("- al****pha", result.text)
        assertEquals(6, result.selectionStart)
    }

    @Test
    fun `a stacked word reports every style it has`() {
        val active = MarkdownActions.activeInlineMarkers("***word***", 3, 7)
        assertTrue("**" in active)
        assertTrue("*" in active)
    }

    @Test
    fun `a caret inside bold reports bold`() {
        val active = MarkdownActions.activeInlineMarkers("**bold** text", 4, 4)
        assertTrue("**" in active)
    }

    @Test
    fun `a caret outside every marker reports nothing`() {
        assertEquals(emptySet<String>(), MarkdownActions.activeInlineMarkers("**bold** text", 12, 12))
    }

    // ---- Enter in a list -----------------------------------------------------------------------

    @Test
    fun `pressing enter in a list continues it`() {
        assertEquals("- ", ContinueList.continuationOf("- milk"))
        assertEquals("* ", ContinueList.continuationOf("* milk"))
        assertEquals("  - ", ContinueList.continuationOf("  - nested"))
    }

    @Test
    fun `a numbered list counts on`() {
        assertEquals("2. ", ContinueList.continuationOf("1. first"))
        assertEquals("10. ", ContinueList.continuationOf("9. ninth"))
    }

    @Test
    fun `a new task starts unticked`() {
        // Carrying the tick over would mark work done that nobody has done.
        assertEquals("- [ ] ", ContinueList.continuationOf("- [x] shopping"))
    }

    @Test
    fun `an ordinary paragraph continues as an ordinary paragraph`() {
        assertEquals(null, ContinueList.continuationOf("just a sentence"))
        assertEquals(null, ContinueList.continuationOf("# a heading"))
    }
}
