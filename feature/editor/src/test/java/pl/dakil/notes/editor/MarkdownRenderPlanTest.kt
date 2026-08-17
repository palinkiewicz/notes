package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * What the WYSIWYG editor actually shows.
 *
 * Written against the rendered string rather than against the plan's internals, because "what does
 * the user see" is the only claim worth pinning down — the offsets are a means to it. The invariant
 * test at the end guards the part that would fail loudly instead: a text field handed overlapping or
 * out-of-range edits throws rather than mis-renders.
 */
class MarkdownRenderPlanTest {

    // ---- What the reader sees ------------------------------------------------------------------

    @Test
    fun `a heading loses its hashes and keeps its words`() {
        assertEquals("Chapter one", render("# Chapter one"))
        assertEquals(listOf("Chapter one"), styled("# Chapter one", MdStyle.H1))
    }

    @Test
    fun `heading level picks the style, not the visible text`() {
        assertEquals("Deep", render("#### Deep"))
        assertEquals(listOf("Deep"), styled("#### Deep", MdStyle.H4))
    }

    @Test
    fun `bold markers disappear and what they wrapped is bold`() {
        assertEquals("bold", render("**bold**"))
        assertEquals(listOf("bold"), styled("**bold**", MdStyle.BOLD))
    }

    @Test
    fun `emphasis nested in bold gets both styles`() {
        val source = "**a *b* c**"
        assertEquals("a b c", render(source))
        assertEquals(listOf("a b c"), styled(source, MdStyle.BOLD))
        assertEquals(listOf("b"), styled(source, MdStyle.ITALIC))
    }

    @Test
    fun `triple markers are bold and italic at once`() {
        // Matching the `**` first would eat two of the three asterisks and strand the last one in
        // the middle of the text, where the whole point is that markers are invisible.
        assertEquals("both", render("***both***"))
        assertEquals(listOf("both"), styled("***both***", MdStyle.BOLD))
        assertEquals(listOf("both"), styled("***both***", MdStyle.ITALIC))
    }

    @Test
    fun `underscores are emphasis too`() {
        assertEquals("strong", render("__strong__"))
        assertEquals(listOf("strong"), styled("__strong__", MdStyle.BOLD))
        assertEquals(listOf("soft"), styled("_soft_", MdStyle.ITALIC))
    }

    @Test
    fun `an underscore inside a word is part of the word`() {
        // Otherwise every `snake_case_name` in a note turns into italics.
        assertEquals("snake_case_name", render("snake_case_name"))
    }

    @Test
    fun `an unclosed marker stays visible`() {
        // Someone halfway through typing `**bold**` must still see what they have typed, or the
        // characters appear to be swallowed.
        assertEquals("**bold", render("**bold"))
        assertEquals(emptyList<String>(), styled("**bold", MdStyle.BOLD))
    }

    @Test
    fun `a link shows its label and hides its target`() {
        assertEquals("the docs", render("[the docs](https://example.com)"))
        assertEquals(listOf("the docs"), styled("[the docs](https://example.com)", MdStyle.LINK))
    }

    @Test
    fun `an image leaves a glyph where its markers were`() {
        // An image cannot be drawn inside a text field, and an empty alt text would otherwise leave
        // nothing at all on the line.
        assertEquals("🖼 a cat", render("![a cat](cat.png)"))
    }

    @Test
    fun `inline code hides its backticks`() {
        assertEquals("call me", render("`call` me"))
        assertEquals(listOf("call"), styled("`call` me", MdStyle.CODE))
    }

    @Test
    fun `a bullet becomes a bullet`() {
        assertEquals("•  milk", render("- milk"))
    }

    @Test
    fun `a numbered item keeps its number`() {
        // The number is content, not syntax: hiding it would leave an unnumbered list.
        assertEquals("1. first", render("1. first"))
        assertEquals(listOf("1."), styled("1. first", MdStyle.MARKER))
    }

    @Test
    fun `a task shows a box, and a done task is struck through`() {
        assertEquals("☐  buy milk", render("- [ ] buy milk"))
        assertEquals("☑  buy milk", render("- [x] buy milk"))
        assertEquals(listOf("buy milk"), styled("- [x] buy milk", MdStyle.STRIKE))
    }

    @Test
    fun `a quote marker becomes a bar`() {
        assertEquals("▏ to be", render("> to be"))
        assertEquals(listOf("▏ to be"), styled("> to be", MdStyle.QUOTE))
    }

    @Test
    fun `a thematic break becomes a drawn line`() {
        assertEquals("──────────", render("---"))
    }

    @Test
    fun `a fence keeps its language as a header and drops the backticks`() {
        // The label the reader sees is the language in the source, so editing one edits the other.
        assertEquals("kotlin\nval x = 1\n", render("```kotlin\nval x = 1\n```"))
        assertEquals(listOf("kotlin"), styled("```kotlin\nval x = 1\n```", MdStyle.FENCE_HEADER))
    }

    @Test
    fun `a fence with no language has no header line`() {
        assertEquals("val x = 1\n", render("```\nval x = 1\n```"))
    }

    @Test
    fun `every line of a block is padded to the same width`() {
        // A background only paints behind glyphs, so without this a two-character line would draw a
        // two-character box instead of a block.
        val lines = renderRaw("```\nab\nlonger line\n```").lines()
        assertEquals(lines[0].length, lines[1].length)
        assertTrue("expected padding, got '${lines[0]}'", lines[0].startsWith("ab "))
    }

    @Test
    fun `a blank line inside a block is still part of the block`() {
        // Its source range is zero characters long, so without care it gets no style at all and the
        // block's background breaks in half wherever the code has a blank line.
        val blank = styledRaw("```\nab\n\ncd\n```", MdStyle.FENCE).filter { it.isBlank() }
        assertTrue("the blank line was left unstyled", blank.isNotEmpty())
        assertEquals(renderRaw("```\nab\n\ncd\n```").lines()[0].length, blank.first().length)
    }

    @Test
    fun `the header is padded to the block width too`() {
        val lines = renderRaw("```kotlin\nval something = 1\n```").lines()
        assertEquals(lines[1].length, lines[0].length)
    }

    @Test
    fun `markdown inside a fence is not interpreted`() {
        // The whole point of a code block is that its contents are quoted, not parsed.
        val source = "```\n# not a heading **not bold**\n```"
        assertEquals("# not a heading **not bold**\n", render(source))
        assertEquals(emptyList<String>(), styled(source, MdStyle.BOLD))
    }

    @Test
    fun `code inside a fence is coloured by language`() {
        val source = "```kotlin\nval n = 42 // note\n```"
        assertEquals(listOf("val"), styled(source, MdStyle.CODE_KEYWORD))
        assertEquals(listOf("42"), styled(source, MdStyle.CODE_NUMBER))
        assertEquals(listOf("// note"), styled(source, MdStyle.CODE_COMMENT))
    }

    @Test
    fun `strings are coloured whole`() {
        val source = "```python\nname = \"Adam P\"\n```"
        assertEquals(listOf("\"Adam P\""), styled(source, MdStyle.CODE_STRING))
    }

    @Test
    fun `a call site is coloured as a function`() {
        val source = "```python\ntotal = compute(items)\n```"
        assertEquals(listOf("compute"), styled(source, MdStyle.CODE_FUNCTION))
    }

    @Test
    fun `a language's own builtins stay keywords rather than functions`() {
        // `print(...)` is a call, but it is also Python's own word — the keyword colour says more.
        val source = "```python\nprint(total)\n```"
        assertEquals(listOf("print"), styled(source, MdStyle.CODE_KEYWORD))
    }

    @Test
    fun `a number inside a name is not a number`() {
        val source = "```python\nutf8 = 8\n```"
        assertEquals(listOf("8"), styled(source, MdStyle.CODE_NUMBER))
    }

    @Test
    fun `an unknown language still renders, just without colour`() {
        val source = "```klingon\nnuqneH\n```"
        assertEquals("klingon\nnuqneH\n", render(source))
        assertEquals(emptyList<String>(), styled(source, MdStyle.CODE_KEYWORD))
    }

    // ---- Tables --------------------------------------------------------------------------------

    @Test
    fun `a table is ruled and its columns line up`() {
        val source = "| a | long header |\n| --- | --- |\n| 1 | 2 |"
        val lines = renderRaw(source).lines()
        assertTrue("expected box rules, got '${lines[0]}'", lines[0].startsWith("│"))
        // Every row the same width is the whole visible promise of a table.
        assertEquals(lines[0].length, lines[1].length)
        assertEquals(lines[0].length, lines[2].length)
    }

    @Test
    fun `a table keeps every character the user typed`() {
        // Cells are padded, never rewritten — a table whose rows were replaced wholesale would
        // render beautifully and be impossible to type in.
        val source = "| a | b |\n| --- | --- |\n| one | two |"
        val rendered = renderRaw(source)
        for (word in listOf("a", "b", "one", "two")) {
            assertTrue("lost '$word'", rendered.contains(word))
        }
    }

    @Test
    fun `a pipe becomes exactly one rule character`() {
        val plan = MarkdownRenderer.plan("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val pipeEdits = plan.edits.filter { it.end - it.start == 1 && it.replacement.length == 1 }
        assertTrue("expected one-for-one pipe swaps", pipeEdits.isNotEmpty())
    }

    @Test
    fun `a line of pipes without a delimiter row is not a table`() {
        assertEquals("| not | a table |", render("| not | a table |"))
    }

    @Test
    fun `display math is preserved between its delimiters`() {
        assertEquals("e = mc^2\n", render("$$\ne = mc^2\n$$"))
    }

    @Test
    fun `a blank document renders blank`() {
        assertEquals("", render(""))
    }

    @Test
    fun `text with no markup is left exactly as it is`() {
        val plain = "Just a sentence.\n\nAnd another one."
        assertEquals(plain, render(plain))
    }

    // ---- Invariants the text field depends on -------------------------------------------------

    @Test
    fun `edits are ordered, disjoint and inside the document`() {
        val plan = MarkdownRenderer.plan(KITCHEN_SINK)
        var previousEnd = 0
        for (edit in plan.edits) {
            assertTrue("edit starts before the previous one ends: $edit", edit.start >= previousEnd)
            assertTrue("edit is inverted: $edit", edit.end >= edit.start)
            assertTrue("edit runs past the end: $edit", edit.end <= KITCHEN_SINK.length)
            previousEnd = edit.end
        }
    }

    @Test
    fun `style ranges land inside the rendered text`() {
        val plan = MarkdownRenderer.plan(KITCHEN_SINK)
        val rendered = renderRaw(KITCHEN_SINK)
        for (range in plan.styles) {
            assertTrue("style is inverted: $range", range.end >= range.start)
            assertTrue("style runs past the end: $range", range.end <= rendered.length)
        }
    }

    @Test
    fun `the offset mapping never goes backwards`() {
        // A text field rejects a non-monotonic mapping outright, so this is a crash guard rather
        // than a cosmetic one.
        val edits = MarkdownRenderer.plan(KITCHEN_SINK).edits
        var previous = -1
        for (offset in 0..KITCHEN_SINK.length) {
            val mapped = MarkdownRenderer.transformedOffset(edits, offset)
            assertTrue("mapping went backwards at $offset", mapped >= previous)
            previous = mapped
        }
    }

    private companion object {
        val KITCHEN_SINK = """
            # Title

            Some **bold** and *italic* and `code` and ~~gone~~.

            > A quote with a [link](https://example.com).

            - milk
            - [ ] eggs
            - [x] bread
            1. first
            2. second

            ---

            ```kotlin
            fun main() = println("# not a heading")
            ```

            | a | b |
            | --- | --- |
            | 1 | 2 |

            An ![image](pic.png) and an unclosed **marker.
        """.trimIndent()
    }

    /** Applies the plan the way `MarkdownOutputTransformation` does, so the test sees what it does. */
    private fun renderRaw(markdown: String): String {
        val plan = MarkdownRenderer.plan(markdown)
        val out = StringBuilder(markdown)
        for (edit in plan.edits.asReversed()) out.replace(edit.start, edit.end, edit.replacement)
        return out.toString()
    }

    /**
     * The rendering with each line's trailing padding removed.
     *
     * Code blocks and tables are padded out so their backgrounds and columns line up, which is
     * tested on its own; everywhere else it would only make the expected strings unreadable.
     */
    private fun render(markdown: String): String =
        renderRaw(markdown).lines().joinToString("\n") { it.trimEnd() }

    /**
     * The text each [style] covers, with block padding trimmed off the end.
     *
     * A span reaching the end of a line inside a padded block absorbs that line's padding, because
     * the padding is inserted at exactly the offset the span ends on and the mapping cannot tell the
     * two apart. It is invisible — spaces carry no ink, and none of these styles paint a background
     * — so the trim keeps the expected strings honest rather than papering over anything. Where the
     * padding *must* be covered, see [styledRaw].
     */
    private fun styled(markdown: String, style: MdStyle): List<String> =
        styledRaw(markdown, style).map { it.trimEnd() }

    private fun styledRaw(markdown: String, style: MdStyle): List<String> {
        val plan = MarkdownRenderer.plan(markdown)
        val rendered = renderRaw(markdown)
        return plan.styles
            .filter { it.style == style && it.end > it.start }
            .map { rendered.substring(it.start, it.end) }
    }
}
