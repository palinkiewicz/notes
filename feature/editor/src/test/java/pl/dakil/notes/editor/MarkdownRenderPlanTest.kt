package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdCodeBlock
import pl.dakil.notes.editor.markdown.MdDecoration
import pl.dakil.notes.editor.markdown.MdQuote
import pl.dakil.notes.editor.markdown.MdRule
import pl.dakil.notes.editor.markdown.MdStyle
import pl.dakil.notes.editor.markdown.MdTable
import pl.dakil.notes.editor.markdown.MdTask

/**
 * What the WYSIWYG editor actually shows.
 *
 * Written against the rendered string rather than against the plan's internals, because "what does
 * the user see" is the only claim worth pinning down — the offsets are a means to it. Block shape is
 * the exception: a box, a grid and a rule are *drawn*, so the only thing a test on this side of the
 * device can check is that they are aimed at the right characters.
 *
 * The invariant tests at the end guard the part that would fail loudly instead: a text field handed
 * overlapping or out-of-range edits throws rather than mis-renders.
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
    fun `italics started inside bold text take the front of the closing run`() {
        // What the formatting bar writes when somebody bolds a word and then asks for italics
        // partway through it. The three asterisks at the end are one run carrying two closes, and
        // reading the first two of them as the bold close left an asterisk on screen in the middle
        // of the text and no italics at all.
        val source = "**test*2***"
        assertEquals("test2", render(source))
        assertEquals(listOf("test2"), styled(source, MdStyle.BOLD))
        assertEquals(listOf("2"), styled(source, MdStyle.ITALIC))
    }

    @Test
    fun `an asterisk inside a word is emphasis, the way every other renderer reads it`() {
        // Not `snake_case`'s problem: `*` is not a word character anywhere, and GitHub, CommonMark
        // and Obsidian all italicise this. Hiding the difference here would mean a `.md` file that
        // renders one way in this app and another way everywhere it is opened.
        assertEquals("test2", render("test*2*"))
        assertEquals(listOf("2"), styled("test*2*", MdStyle.ITALIC))
    }

    @Test
    fun `a lone asterisk with space around it is arithmetic, not emphasis`() {
        // A marker only opens emphasis where it is up against the text it styles.
        assertEquals("2 * 3 * 4", render("2 * 3 * 4"))
        assertEquals(emptyList<String>(), styled("2 * 3 * 4", MdStyle.ITALIC))
    }

    @Test
    fun `a wide marker keeps clear of whitespace too, the way a narrow one does`() {
        // `** bold**` is bold in no other Markdown reader: emphasis opens only on a marker up
        // against the text it styles. Rendering it as bold here would make this app the only
        // program that agreed with the file, which is the one thing a plain-text format may not do.
        assertEquals("** bold**", render("** bold**"))
        assertEquals("**bold **", render("**bold **"))
        assertEquals("~~struck ~~", render("~~struck ~~"))
        // And the well-formed ones are untouched.
        assertEquals("bold", render("**bold**"))
        assertEquals("struck", render("~~struck~~"))
    }

    @Test
    fun `a marker against a space still closes a run somewhere else on the line`() {
        // The scan does not give up at the first candidate it turns down: `**a ** b**` closes on
        // the second pair, which is the one against the text.
        assertEquals("a ** b", render("**a ** b**"))
    }

    @Test
    fun `a bold word inside an italic one closes on the right asterisks`() {
        // Nesting the *wider* marker inside the narrower one is the case the scan used to get
        // backwards: it took the `**` for the italic's closer, which left the run at the end with
        // two asterisks nothing had asked for, in full view. CommonMark's rule of three is what
        // says the middle run belongs to what comes after it — 1 + 2 is a multiple of three.
        assertEquals("ab", render("*a**b***"))
        assertEquals(listOf("ab"), styled("*a**b***", MdStyle.ITALIC))
        assertEquals(listOf("b"), styled("*a**b***", MdStyle.BOLD))
        // And the other way round, which always worked and has to go on working.
        assertEquals(listOf("ab"), styled("**a*b***", MdStyle.BOLD))
        assertEquals(listOf("b"), styled("**a*b***", MdStyle.ITALIC))
    }

    @Test
    fun `a bold run inside an italic one does not end the italic with it`() {
        // The bold closes on its own pair and the italic carries on to the end of the line. Read
        // as the italic's closer, the run after "b" ended the span half a sentence early and the
        // rest of its markers were left on screen.
        assertEquals("a b c", render("*a **b** c*"))
        assertEquals(listOf("a b c"), styled("*a **b** c*", MdStyle.ITALIC))
        assertEquals(listOf("b"), styled("*a **b** c*", MdStyle.BOLD))
        assertEquals("ab c", render("*a**b** c*"))
        assertEquals(listOf("ab c"), styled("*a**b** c*", MdStyle.ITALIC))
    }

    @Test
    fun `three markers each side stay one span of two styles`() {
        // The rule of three excuses itself when both widths are multiples of it, which is what
        // keeps a bold-italic run from being read as a bold one with a stray asterisk beside it.
        assertEquals("both", render("***both***"))
        assertEquals(listOf("both"), styled("***both***", MdStyle.BOLD))
        assertEquals(listOf("both"), styled("***both***", MdStyle.ITALIC))
        assertEquals("test2", render("**test*2***"))
        assertEquals(listOf("2"), styled("**test*2***", MdStyle.ITALIC))
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
    fun `a task marker becomes the blank its checkbox stands in`() {
        // Nothing is spelled out where the box goes. It is a real control, floated over this gap,
        // so all the text has to do is leave room for one.
        assertEquals("   buy milk", render("- [ ] buy milk"))
        assertEquals("   buy milk", render("- [x] buy milk"))
        assertEquals(listOf("buy milk"), styled("- [x] buy milk", MdStyle.STRIKE))
    }

    @Test
    fun `a task names the one character a tap has to flip`() {
        val open = decorations<MdTask>("- [ ] buy milk").single()
        assertFalse(open.checked)
        assertEquals(0, open.offset)
        assertEquals(' ', "- [ ] buy milk"[open.sourceMark])

        // Any of the markers CommonMark allows, upper or lower case, and indented.
        val source = "  * [X] buy milk"
        val done = decorations<MdTask>(source).single()
        assertTrue(done.checked)
        assertEquals('X', source[done.sourceMark])
        // A nested item's indent is redrawn as one monospace step per level — two source spaces
        // are a few pixels of proportional space, which read as no nesting at all — so the box
        // stands after that step rather than after the two characters that spell it.
        assertEquals(3, done.offset)
    }

    // ---- Quotes --------------------------------------------------------------------------------

    @Test
    fun `a quote marker disappears entirely and leaves a bar to be drawn`() {
        // The marker leaves nothing in its place: the bar is drawn out in the margin, so the
        // quoted text keeps the column the rest of the page is set in — and so do the lines it
        // wraps onto, which a run of spaces could never have reached.
        assertEquals("to be", render("> to be"))
        assertEquals(listOf("to be"), styled("> to be", MdStyle.QUOTE))
        assertEquals(listOf(MdQuote(0, 5)), decorations<MdQuote>("> to be"))
    }

    @Test
    fun `adjacent quote lines share one bar`() {
        // Three stacked bars with seams between them is not what a quotation looks like.
        val source = "> one\n> two\n> three"
        assertEquals(1, decorations<MdQuote>(source).size)
    }

    @Test
    fun `a paragraph between two quotes breaks the bar in two`() {
        assertEquals(2, decorations<MdQuote>("> one\n\n> two").size)
    }

    // ---- Rules ---------------------------------------------------------------------------------

    @Test
    fun `a thematic break empties its line for a rule to be drawn across`() {
        // No run of characters reaches both margins, so the line carries none: it is there to hold
        // the height, and the line itself is drawn.
        assertEquals("", renderRaw("---"))
        assertEquals(listOf(MdRule(0)), decorations<MdRule>("---"))
    }

    @Test
    fun `a rule between paragraphs keeps its own line`() {
        // Two blank lines round it, which is the margin a drawn block gets: the rule itself has
        // no characters left, so the line it stands on is the third of them.
        assertEquals("a\n\n\n\nb", renderRaw("a\n---\nb"))
        assertEquals(listOf(MdRule(3)), decorations<MdRule>("a\n---\nb"))
    }

    // ---- Fenced code ---------------------------------------------------------------------------

    @Test
    fun `a fence keeps its language as a header and drops the backticks`() {
        // The label the reader sees is the language in the source, so editing one edits the other.
        // Indented by a column, the same step the blank left by a table's opening pipe holds its
        // first cell off its own border: both boxes are drawn flush with the text column.
        assertEquals(" kotlin\n val x = 1\n", render("```kotlin\nval x = 1\n```"))
        assertEquals(listOf("kotlin"), styled("```kotlin\nval x = 1\n```", MdStyle.FENCE_HEADER))
    }

    @Test
    fun `a block is not padded out with spaces`() {
        // It used to be: a background only paints behind glyphs, so a rectangle had to be spelled
        // out. The rectangle is drawn now, and every one of those spaces was a character the user
        // could put a caret in the middle of.
        val rendered = renderRaw("```kotlin\nab\nlonger line\n```")
        for (line in rendered.lines()) {
            assertEquals("padded: '$line'", line.trimEnd(), line)
        }
    }

    @Test
    fun `the block covers every line between the fences`() {
        val source = "```\nab\n\ncd\n```"
        val block = decorations<MdCodeBlock>(source).single()
        val rendered = renderRaw(source)
        // Including the blank line: a box drawn round the code has to reach the last line of it,
        // and a blank line in the middle used to break the background in half.
        assertEquals(" \n ab\n \n cd", rendered.substring(block.start, block.end))
    }

    @Test
    fun `a fence with no language leaves an empty header line to type into`() {
        // The line stays so the caret has somewhere to sit; the placeholder over it is drawn, not
        // typed, so a word entered there lands in the source as the fence's language.
        assertEquals(" \n val x = 1\n", renderRaw("```\nval x = 1\n```"))
        val block = decorations<MdCodeBlock>("```\nval x = 1\n```").single()
        assertEquals(block.headerStart, block.headerEnd)
    }

    @Test
    fun `the block knows which source characters are the code`() {
        // This is what the Copy button puts on the clipboard, so it has to be the code as written —
        // source offsets, not the rendered ones everything else in the plan uses.
        val source = "```kotlin\nval x = 1\nval y = 2\n```"
        val block = decorations<MdCodeBlock>(source).single()
        assertEquals("val x = 1\nval y = 2", source.substring(block.sourceStart, block.sourceEnd))
    }

    @Test
    fun `markdown inside a fence is not interpreted`() {
        // The whole point of a code block is that its contents are quoted, not parsed.
        val source = "```\n# not a heading **not bold**\n```"
        assertEquals("\n # not a heading **not bold**\n", render(source))
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
        assertEquals(" klingon\n nuqneH\n", render(source))
        assertEquals(emptyList<String>(), styled(source, MdStyle.CODE_KEYWORD))
    }

    // ---- Tables --------------------------------------------------------------------------------

    @Test
    fun `a table's rows all come out the same width`() {
        // Which is the whole visible promise of a table: the columns line up because the *text*
        // lines up, and the rules are then drawn between them.
        val source = "| a | long header |\n| --- | --- |\n| 1 | 2 |"
        val lines = renderRaw(source).lines()
        assertEquals(2, lines.size)
        assertEquals(lines[0].length, lines[1].length)
    }

    @Test
    fun `the delimiter row is gone from what the reader sees`() {
        // It carries no information a reader wants and every character of it was a fake border.
        val rendered = renderRaw("| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertTrue("the dashes survived: '$rendered'", !rendered.contains("-"))
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
    fun `every column rule is aimed at a space`() {
        // Each pipe becomes exactly one space and the rule is drawn down the middle of it. Aimed at
        // anything else, the rule would be struck through a character the user typed.
        val source = "| a | long header |\n| --- | --- |\n| 1 | 2 |"
        val table = decorations<MdTable>(source).single()
        val rendered = renderRaw(source)
        assertTrue("no columns found", table.columnStops.isNotEmpty())
        for (stop in table.columnStops) {
            assertEquals("column $stop is not on a space", ' ', rendered[stop])
        }
    }

    @Test
    fun `a table without outer pipes still finds its columns`() {
        val source = "a | b\n--- | ---\n1 | 2"
        val table = decorations<MdTable>(source).single()
        val rendered = renderRaw(source)
        assertEquals(1, table.columnStops.size)
        assertEquals(' ', rendered[table.columnStops.single()])
    }

    @Test
    fun `a cell padded only on the left still reaches the column width`() {
        // Trimming the tail alone cannot get `|    a|` down to size, and one row a character out is
        // a column of drawn rules that no longer lines up with its text.
        val source = "|    a|  b  |\n| --- | --- |\n| 1 | 2 |"
        val lines = renderRaw(source).lines()
        assertEquals(lines[0].length, lines[1].length)
    }

    @Test
    fun `a cell begins where its column does, whatever the author spaced it with`() {
        // The rules are drawn a fixed column out from the text on either side, so the text has to
        // start and stop at fixed places. Blanks the author typed at either end of a cell are
        // struck out and the column is squared off with blanks of its own instead; `| a |`, `|a|`
        // and `|  a  |` then read exactly alike, and nothing on screen sits past the text for a
        // caret to be dropped into.
        val rendered = renderRaw("|a|  spaced  |\n| --- | --- |\n|   b | c |").lines()
        assertEquals(" a  spaced  ", rendered[0])
        assertEquals(" b  c       ", rendered[1])
    }

    @Test
    fun `a cell of nothing but blanks keeps one for a caret to sit in`() {
        // Struck out whole, it would have no offset inside it at all, and the cursor of an empty
        // cell would be drawn against the far border with nowhere else to go.
        val plan = MarkdownRenderer.plan("| a |   |\n| --- | --- |")
        val struck = plan.edits.filter { it.replacement.isEmpty() && it.end > it.start }
        assertTrue("the empty cell was struck out whole", struck.none { it.end - it.start == 3 })
    }

    @Test
    fun `a half-typed table is measured to its last visible row`() {
        // A header and a delimiter and nothing else. The delimiter is hidden, so measuring the box
        // to the last *line* would draw it a row taller than the table it contains.
        val source = "| a | b |\n| --- | --- |"
        val table = decorations<MdTable>(source).single()
        assertEquals(table.rows.single().last, table.end)
        assertEquals(renderRaw(source).lines().first().length, table.end)
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
        // One blank line where the author typed one. It used to be three — a blank line was a
        // paragraph like any other, so it took space above it and gave the paragraph below it space
        // in turn, and the line the author actually typed was left standing at its full height in
        // the middle. The space between two things is now the same whether they were written with a
        // blank line between them or without one; only its size says what they are.
        assertEquals("Just a sentence.\n\nAnd another one.", render("Just a sentence.\n\nAnd another one."))
    }

    // ---- Spacing -------------------------------------------------------------------------------

    @Test
    fun `a block standing between two paragraphs is held off both`() {
        // The margin has to be a line: one line box begins exactly where the last one ended, so
        // there is no space to draw a box into. The inserted lines are kept short by their style.
        assertEquals(
            listOf("before", "", " ", " x", "", "after"),
            renderRaw("before\n```\nx\n```\nafter").lines(),
        )
    }

    @Test
    fun `a block with nothing beside it gets no margin`() {
        // A blank line at the top of a document is not a margin, it is a blank line.
        assertEquals(listOf(" ", " x", ""), renderRaw("```\nx\n```").lines())
    }

    @Test
    fun `a table is held off its neighbours too`() {
        val rendered = renderRaw("before\n| a |\n| --- |\n| 1 |\nafter").lines()
        assertEquals("", rendered[1])
        assertEquals("", rendered[rendered.lastIndex - 1])
    }

    @Test
    fun `two tables stand one line apart, not three`() {
        // A table has to be closed by a blank line or the next one runs into it, so this is the one
        // pair of blocks a user cannot help writing with a blank line between them — and it was
        // where the doubled spacing showed worst: two boxes a finger's width apart.
        val source = "| a |\n| --- |\n| 1 |\n\n| b |\n| --- |\n| 2 |"
        val rendered = renderRaw(source).lines()
        assertEquals(listOf(" a  ", " 1  ", "", " b  ", " 2  "), rendered)
        // And that one line is a block's margin rather than a line of text.
        val plan = MarkdownRenderer.plan(source)
        assertEquals(1, plan.styles.count { it.style == MdStyle.BLOCK_GAP })
    }

    @Test
    fun `a blank line the author typed is the space, not another line to space`() {
        // One line either way — it used to be three when the author had typed one, the blank line
        // being just another paragraph to hold off its neighbours.
        assertEquals(listOf("one", "", "two"), renderRaw("one\n\ntwo").lines())
        assertEquals(listOf("one", "", "two"), renderRaw("one\ntwo").lines())
        // The two are still not the same, though: a line somebody typed is a line, and a line this
        // put in to hold two paragraphs apart is a short one. Pressing Enter twice has to do
        // something visible or it reads as a keystroke that was swallowed.
        val typed = MarkdownRenderer.plan("one\n\ntwo").styles
        assertEquals(0, typed.count { it.style == MdStyle.PARAGRAPH_GAP })
        val made = MarkdownRenderer.plan("one\ntwo").styles
        assertEquals(1, made.count { it.style == MdStyle.PARAGRAPH_GAP })
    }

    @Test
    fun `several blank lines are somebody holding text down the page`() {
        // A run of them is a decision rather than syntax, so it is left at its full height. Only a
        // single blank line is the one Markdown requires between two things.
        assertEquals(listOf("one", "", "", "", "two"), renderRaw("one\n\n\n\ntwo").lines())
    }

    @Test
    fun `every paragraph but the first opens with a blank line of its own`() {
        // The space between two paragraphs is a line, not leading hung on the terminator above it.
        // Leading lands on the terminator's own visual line, which on a paragraph that wrapped is
        // the last of several — so it opened a gap in the middle of a long list item rather than
        // after it. A line always falls between the two, however either of them wraps.
        assertEquals(listOf("one", "", "two", "", "three"), renderRaw("one\ntwo\nthree").lines())
    }

    @Test
    fun `a list is spaced tighter inside itself than against what surrounds it`() {
        // Items of one list are separate things and get a line between them; what stands either
        // side of the list is further off still, and its line is a taller one.
        assertEquals(
            listOf("text", "", "•  one", "", "•  two", "", "after"),
            renderRaw("text\n- one\n- two\nafter").lines(),
        )
        val gaps = MarkdownRenderer.plan("text\n- one\n- two\nafter").styles
        assertEquals(1, gaps.count { it.style == MdStyle.LIST_GAP })
        assertEquals(2, gaps.count { it.style == MdStyle.PARAGRAPH_GAP })
    }

    @Test
    fun `switching list marker starts a second list`() {
        // Which is what CommonMark says it is, and two lists want a space between them.
        assertEquals(
            listOf("•  one", "", "1. two"),
            renderRaw("- one\n1. two").lines(),
        )
    }

    @Test
    fun `a task item belongs to the bullet list above it`() {
        val plan = MarkdownRenderer.plan("- one\n- [ ] two")
        // One list, so the space between them is a list's own and not a paragraph's.
        assertEquals(1, plan.styles.count { it.style == MdStyle.LIST_GAP })
        assertEquals(0, plan.styles.count { it.style == MdStyle.PARAGRAPH_GAP })
    }

    @Test
    fun `code keeps the tighter leading`() {
        // A code block is meant to read densely; body spacing inside one would undo that.
        val plan = MarkdownRenderer.plan("```kotlin\nval x = 1\n```")
        assertEquals(0, plan.styles.count { it.style == MdStyle.PARAGRAPH_GAP })
        assertEquals(2, plan.styles.count { it.style == MdStyle.LEADING_TIGHT })
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
    fun `decorations land inside the rendered text`() {
        val rendered = renderRaw(KITCHEN_SINK)
        for (decoration in MarkdownRenderer.plan(KITCHEN_SINK).decorations) {
            for (offset in offsetsOf(decoration)) {
                assertTrue("$decoration points past the end", offset in 0..rendered.length)
            }
        }
    }

    @Test
    fun `the start of a line maps to the start of the line`() {
        // The blank line above a paragraph is spelled by rewriting the break above it rather than
        // by inserting a newline at the paragraph's own first offset. An insertion there would
        // leave that offset standing on both sides of what went in, and a text field picks the near
        // side: the caret for the start of the paragraph came out on the blank line above it, at
        // that blank line's own few pixels of height. Every offset has one place to be.
        val source = "one\ntwo"
        val rendered = renderRaw(source)
        assertEquals("one\n\ntwo", rendered)
        val edits = MarkdownRenderer.plan(source).edits
        assertEquals(rendered.indexOf("two"), MarkdownRenderer.transformedOffset(edits, source.indexOf("two")))
        // And the end of the line above is still the end of the line above.
        assertEquals(3, MarkdownRenderer.transformedOffset(edits, 3))
    }

    @Test
    fun `the line below a block starts where it looks like it starts`() {
        // The same, where the break above has already gone: a closing fence takes its own newline
        // with it, so the blank line is made out of the fence's own edit rather than inserted after
        // it. Nothing may be inserted at a line's first offset, wherever that line stands.
        val source = "```\nx\n```\nafter"
        val rendered = renderRaw(source)
        val edits = MarkdownRenderer.plan(source).edits
        assertEquals(rendered.indexOf("after"), MarkdownRenderer.transformedOffset(edits, source.indexOf("after")))
        assertTrue("no gap was left below the block", rendered.endsWith("\n\nafter"))
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
            > And a second line of it.

            - milk
            - [ ] eggs
            - [x] bread
            1. first
            2. second

            ---

            ```kotlin
            fun main() = println("# not a heading")
            ```

            ```
            no language here
            ```

            | a | b |
            | --- | --- |
            | 1 | 2 |

            An ![image](pic.png) and an unclosed **marker.
        """.trimIndent()

        fun offsetsOf(decoration: MdDecoration): List<Int> = when (decoration) {
            is MdCodeBlock ->
                listOf(decoration.start, decoration.end, decoration.headerStart, decoration.headerEnd)

            is MdTable ->
                listOf(decoration.start, decoration.end, decoration.headerEnd) +
                    decoration.columnStops +
                    decoration.rows.flatMap { listOf(it.first, it.last) }

            is MdRule -> listOf(decoration.offset)
            is MdQuote -> listOf(decoration.start, decoration.end)
            // `sourceMark` is left out on purpose: it is an offset into the source, and holding it
            // to the rendered length is exactly the mistake this test would be there to catch.
            is MdTask -> listOf(decoration.offset)
        }
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
     * Table cells are padded so the columns line up, which is tested on its own; everywhere else the
     * padding would only make the expected strings unreadable.
     */
    private fun render(markdown: String): String =
        renderRaw(markdown).lines().joinToString("\n") { it.trimEnd() }

    /**
     * The text each [style] covers, trimmed.
     *
     * A style range takes in whitespace inserted at either of its ends — a block's indent, a cell's
     * padding — because the mapping cannot tell inserted spaces at an offset from the character that
     * was already there. None of it carries ink, so the trim keeps the expected strings readable
     * rather than papering over anything.
     */
    private fun styled(markdown: String, style: MdStyle): List<String> {
        val plan = MarkdownRenderer.plan(markdown)
        val rendered = renderRaw(markdown)
        return plan.styles
            .filter { it.style == style && it.end > it.start }
            .map { rendered.substring(it.start, it.end).trim() }
    }

    private inline fun <reified T : MdDecoration> decorations(markdown: String): List<T> =
        MarkdownRenderer.plan(markdown).decorations.filterIsInstance<T>()
}
