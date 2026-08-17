package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownActions.BlockStyle
import pl.dakil.notes.editor.text.ContinueList

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
