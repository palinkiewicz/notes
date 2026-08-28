package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * `[text]{size=18 color=#c0392b}`: what a Pandoc bracketed span says here.
 *
 * One span carries every attribute it was given, and that is the whole point of the list — two
 * styles on one run of words have to be *one* span, or every edit steps over four more characters
 * and the user cannot select the inner run without the outer.
 *
 * The syntax is understood everywhere — hidden from the reader in both kinds of note — and whether
 * it is *obeyed* is a document's own affair: a sheet always does, a `.md` note only where the user
 * has asked for it. That split is what lets text be copied from a page of paper into a Markdown
 * file without the file gaining punctuation nobody typed or losing the styling on the way back.
 */
class MarkdownAttributesTest {

    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()
    private val red = 0xFFC0392B.toInt()

    private fun rendered(markdown: String) = MarkdownRenderer.render(markdown)

    private fun styles(markdown: String, style: MdStyle) =
        MarkdownRenderer.plan(markdown).styles.filter { it.style == style }

    // ---- Reading the braces ----------------------------------------------------------------------

    @Test
    fun `a colour is carried on the run it wraps`() {
        val range = styles("a [word]{color=#0000ff} b", MdStyle.COLOR).single()
        assertEquals(blue, range.arg)
        assertEquals("word", rendered("a [word]{color=#0000ff} b").substring(range.start, range.end))
    }

    @Test
    fun `one span says both things at once`() {
        val source = "[word]{size=18 color=#0000ff}"
        assertEquals("word", rendered(source))
        assertEquals(18, styles(source, MdStyle.SIZE).single().arg)
        assertEquals(blue, styles(source, MdStyle.COLOR).single().arg)
    }

    @Test
    fun `the order the two are written in does not matter`() {
        val source = "[word]{color=#0000ff size=18}"
        assertEquals(18, styles(source, MdStyle.SIZE).single().arg)
        assertEquals(blue, styles(source, MdStyle.COLOR).single().arg)
    }

    @Test
    fun `three-digit hex is the colour it is short for`() {
        assertEquals(blue, styles("[a]{color=#00f}", MdStyle.COLOR).single().arg)
    }

    @Test
    fun `a colour keeps the transparency it was written with`() {
        assertEquals(0x800000FF.toInt(), styles("[a]{color=#800000ff}", MdStyle.COLOR).single().arg)
    }

    @Test
    fun `nonsense in the braces is left as the text it is`() {
        // Including braces that are only *half* understood: hiding those is how a note quietly
        // loses the half nobody parsed, and a user who mistyped is owed the sight of it.
        for (source in listOf(
            "[a]{color=}",
            "[a]{color=blue}",
            "[a]{color=ff0000}",
            "[a]{color=#gg0000}",
            "[a]{size=18 weight=bold}",
            "[a]{size=12 size=18}",
        )) {
            assertTrue("$source should stay visible", rendered(source).contains("{"))
        }
    }

    @Test
    fun `other styles still apply inside an attributed span`() {
        val source = "[a **b** c]{size=20 color=#0000ff}"
        assertEquals("a b c", rendered(source))
        assertTrue(MarkdownRenderer.plan(source).styles.any { it.style == MdStyle.BOLD })
        assertEquals(blue, styles(source, MdStyle.COLOR).single().arg)
    }

    // ---- Setting one attribute without disturbing the other --------------------------------------

    @Test
    fun `colouring text that is already sized keeps the size`() {
        // The braces are a list, so the button names the one thing it is about and everything the
        // span already carried comes through underneath.
        val result = MarkdownActions.setColor("[word]{size=24}", 1, 5, red)
        assertEquals("[word]{size=24 color=#c0392b}", result.text)
    }

    @Test
    fun `going back to body size leaves the colour behind`() {
        val result = MarkdownActions.setSize("[word]{size=24 color=#c0392b}", 1, 5, null)
        assertEquals("[word]{color=#c0392b}", result.text)
    }

    @Test
    fun `dropping the last attribute takes the brackets with it`() {
        val result = MarkdownActions.setColor("[word]{color=#c0392b}", 1, 5, null)
        assertEquals("word", result.text)
        assertEquals("word", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `a second colour replaces the first rather than nesting inside it`() {
        val result = MarkdownActions.setColor("x [word]{color=#0000ff} y", 3, 7, yellow)
        assertEquals("x [word]{color=#ffff00} y", result.text)
    }

    @Test
    fun `a size asked for inside a colour splits the span rather than nesting in it`() {
        // Both attributes survive — the middle span says the colour as well as the size — and the
        // three spans stand side by side rather than one inside another, which is what lets the
        // outer words be selected again without dragging the inner ones along.
        val result = MarkdownActions.setSize("[I am blue]{color=#0000ff}", 3, 5, 12)
        assertEquals(
            "[I]{color=#0000ff} [am]{size=12 color=#0000ff} [blue]{color=#0000ff}",
            result.text,
        )
        assertEquals("I am blue", rendered(result.text))
        assertEquals(blue, MarkdownActions.colorAt(result.text, result.text.indexOf("am") + 1))
    }

    @Test
    fun `the caret reports the colour it is standing in`() {
        val source = "plain [red]{color=#c0392b} plain"
        assertNull(MarkdownActions.colorAt(source, 2))
        assertEquals(red, MarkdownActions.colorAt(source, 8))
    }

    @Test
    fun `a span that names only a colour has nothing to say about size`() {
        assertNull(MarkdownActions.sizeAt("[a]{color=#0000ff}", 2))
        assertEquals(red, MarkdownActions.colorAt("[a]{color=#c0392b}", 2))
    }

    // ---- Splitting -------------------------------------------------------------------------------

    @Test
    fun `recolouring the middle of a coloured run splits it into three siblings`() {
        // The case nesting cannot express honestly: "am" in yellow is not "am inside blue", and a
        // user who then selects the blue words has to be able to get at them without the yellow.
        val result = MarkdownActions.setColor("[I am blue]{color=#0000ff}", 3, 5, yellow)
        assertEquals(
            "[I]{color=#0000ff} [am]{color=#ffff00} [blue]{color=#0000ff}",
            result.text,
        )
        assertEquals("am", result.text.substring(result.selectionStart, result.selectionEnd))
        assertEquals("I am blue", rendered(result.text))
    }

    @Test
    fun `resizing the middle of a sized run splits it the same way`() {
        val result = MarkdownActions.setSize("[I am big]{size=24}", 3, 5, 12)
        assertEquals("[I]{size=24} [am]{size=12} [big]{size=24}", result.text)
        assertEquals("I am big", rendered(result.text))
    }

    @Test
    fun `a split at the front of a run leaves no empty tag behind it`() {
        // The piece before the selection is empty, and `[]{size=24}` on screen is four characters
        // of punctuation styling nothing.
        val result = MarkdownActions.setSize("[I am big]{size=24}", 1, 3, 12)
        assertEquals("[I]{size=12} [am big]{size=24}", result.text)
    }

    @Test
    fun `clearing an attribute in the middle leaves the words between plain`() {
        val result = MarkdownActions.setColor("[I am blue]{color=#0000ff}", 3, 5, null)
        assertEquals("[I]{color=#0000ff} am [blue]{color=#0000ff}", result.text)
        assertEquals("I am blue", rendered(result.text))
    }

    @Test
    fun `a split keeps what the braces said about the other attribute`() {
        val source = "[I am big]{size=24 color=#0000ff}"
        val result = MarkdownActions.setColor(source, 3, 5, yellow)
        assertEquals(
            "[I]{size=24 color=#0000ff} [am]{size=24 color=#ffff00} [big]{size=24 color=#0000ff}",
            result.text,
        )
    }

    // ---- The same rule for the markers -----------------------------------------------------------

    @Test
    fun `unbolding the middle of a bold run ends the run rather than nesting a second one`() {
        // The bug this is here for: a lit bold button pressed inside a bold sentence used to put a
        // *second* pair of asterisks inside the first, which is a word that is bold twice over.
        val result = MarkdownActions.toggleWrap("**I am blue**", 4, 6, "**")
        assertEquals("**I** am **blue**", result.text)
        assertEquals("am", result.text.substring(result.selectionStart, result.selectionEnd))
        assertEquals("I am blue", rendered(result.text))
    }

    @Test
    fun `the same for italic, strikethrough and code`() {
        assertEquals("*I* am *blue*", MarkdownActions.toggleWrap("*I am blue*", 3, 5, "*").text)
        assertEquals("~~I~~ am ~~blue~~", MarkdownActions.toggleWrap("~~I am blue~~", 4, 6, "~~").text)
        assertEquals("`I` am `blue`", MarkdownActions.toggleWrap("`I am blue`", 3, 5, "`").text)
    }

    @Test
    fun `unbolding inside a bold-italic run leaves the italic behind`() {
        // Three asterisks is two styles, and only one of them was pressed. The italic still runs
        // the whole way, so it is written once round the lot and the bold twice inside it.
        val result = MarkdownActions.toggleWrap("***a b c***", 5, 6, "**")
        assertEquals("***a** b **c***", result.text)
        assertEquals("a b c", rendered(result.text))
        assertEquals(5, styles(result.text, MdStyle.ITALIC).single().let { it.end - it.start })
        assertEquals(listOf("a", "c"), styles(result.text, MdStyle.BOLD).map { rendered(result.text).substring(it.start, it.end) })
    }

    @Test
    fun `unbolding the end of a bold run needs no markers round the empty half`() {
        val result = MarkdownActions.toggleWrap("**hello world**", 8, 13, "**")
        assertEquals("**hello** world", result.text)
        assertEquals("hello world", rendered(result.text))
    }

    @Test
    fun `a style the run does not carry is added inside it, not split out of it`() {
        // Splitting is what a press to turn something *off* means. Italic pressed inside a bold
        // word is a press to add, and `**a *b* c**` is tidier than three runs.
        assertEquals("**a *b* c**", MarkdownActions.toggleWrap("**a b c**", 4, 5, "*").text)
    }

    @Test
    fun `a cut lands between two things, never through the middle of one`() {
        // The bug: unbolding the word in `**[word]{size=24}**` names offsets *inside* the size tag,
        // and a cut there put the bold markers between the brackets and their braces — where they
        // are four asterisks on screen and no longer markup at all.
        val result = MarkdownActions.toggleWrap("**[word]{size=24}**", 3, 7, "**")
        assertEquals("[word]{size=24}", result.text)
        assertEquals("word", rendered(result.text))
    }

    @Test
    fun `a run crossing a bracket is cut inside it, word by word`() {
        // The run covers text on both sides of the tag, so it is pushed inside the tag first and
        // the cut is then an ordinary one: only "big" loses its bold, and it keeps its size.
        val result = MarkdownActions.toggleWrap("**a [big word]{size=24} b**", 5, 8, "**")
        assertEquals("**a** [big **word**]{size=24} **b**", result.text)
        assertEquals("big", result.text.substring(result.selectionStart, result.selectionEnd))
        assertEquals("a big word b", rendered(result.text))
    }

    @Test
    fun `styles that overlap rather than nest are written with tags`() {
        // Unbolding one letter inside an italic run leaves bold covering `a b` and italic covering
        // `b c` — two ranges that cross rather than sit inside one another. No arrangement of
        // asterisks says that: a marker pair has to open and close inside its parent's, and these
        // two have no parent. So the writer uses the one syntax that can, and the note still reads
        // correctly in any editor, because raw HTML is part of Markdown.
        val result = MarkdownActions.toggleWrap("**a *b c* d**", 7, 8, "**")
        assertEquals("a b c d", rendered(result.text))
        assertTrue(result.text, "<strong>" in result.text)
        // Bold on `a b` and on `d`, with the letter that was pressed left out of it; italic still
        // on `b c`, crossing the first of those.
        val text = rendered(result.text)
        assertEquals(
            listOf("a b", "d"),
            styles(result.text, MdStyle.BOLD).map { text.substring(it.start, it.end) },
        )
        // Two ranges rather than one, because the tags are nested inside the bold ones they cross
        // and each half is written where it belongs. What the reader sees is one italic run.
        assertEquals(
            "b c",
            styles(result.text, MdStyle.ITALIC).joinToString("") { text.substring(it.start, it.end) },
        )
    }

    @Test
    fun `resizing a bolded word restates its braces rather than splitting them`() {
        val result = MarkdownActions.setSize("[**word**]{size=24}", 3, 7, 12)
        assertEquals("[**word**]{size=12}", result.text)
        assertEquals("word", rendered(result.text))
    }

    // ---- The two kinds of styling, stacked -------------------------------------------------------

    @Test
    fun `a size can be put on a bolded word and taken off again`() {
        // The markers land inside the brackets whichever way round the two were applied, so there
        // is one shape for a bold sized word and not two. See [MarkdownActions.canonical].
        val bold = "**word**"
        val sized = MarkdownActions.setSize(bold, 2, 6, 24)
        assertEquals("[**word**]{size=24}", sized.text)
        assertEquals("word", rendered(sized.text))

        val plain = MarkdownActions.setSize(sized.text, sized.selectionStart, sized.selectionEnd, null)
        assertEquals(bold, plain.text)
    }

    @Test
    fun `bold can be put on a sized word and taken off again`() {
        val sized = "[word]{size=24}"
        val bold = MarkdownActions.toggleWrap(sized, 1, 5, "**")
        assertEquals("[**word**]{size=24}", bold.text)
        assertEquals("word", rendered(bold.text))

        val plain = MarkdownActions.toggleWrap(bold.text, bold.selectionStart, bold.selectionEnd, "**")
        assertEquals(sized, plain.text)
    }

    @Test
    fun `every marker stacks with a size and a colour on the same word`() {
        var out = MarkdownActions.Result("word", 0, 4)
        for (marker in listOf("**", "*", "~~", "`")) {
            out = MarkdownActions.toggleWrap(out.text, out.selectionStart, out.selectionEnd, marker)
        }
        out = MarkdownActions.setSize(out.text, out.selectionStart, out.selectionEnd, 24)
        out = MarkdownActions.setColor(out.text, out.selectionStart, out.selectionEnd, red)

        // Whatever order the markers ended up nested in, the reader sees the word and nothing else,
        // and every one of the six styles is on it.
        assertEquals("word", rendered(out.text))
        val plan = MarkdownRenderer.plan(out.text)
        for (style in listOf(
            MdStyle.BOLD, MdStyle.ITALIC, MdStyle.STRIKE, MdStyle.CODE, MdStyle.SIZE, MdStyle.COLOR,
        )) {
            assertTrue("$style missing from ${out.text}", plan.styles.any { it.style == style })
        }
        assertEquals(24, plan.styles.single { it.style == MdStyle.SIZE }.arg)
        assertEquals(red, plan.styles.single { it.style == MdStyle.COLOR }.arg)
    }

    @Test
    fun `styling a code span puts the markup outside it, where markup means something`() {
        // Everything between a pair of backticks is the characters that are there, so a tag written
        // between them is eight characters of code the user did not type.
        assertEquals("**`word`**", MarkdownActions.toggleWrap("`word`", 1, 5, "**").text)

        val sized = MarkdownActions.setSize("`word`", 1, 5, 24)
        assertEquals("[`word`]{size=24}", sized.text)
        assertEquals("word", rendered(sized.text))
        assertEquals(24, styles(sized.text, MdStyle.SIZE).single().arg)
    }

    @Test
    fun `the bar reports a size and a colour set one after the other`() {
        val sized = MarkdownActions.setSize("word", 0, 4, 24)
        val both = MarkdownActions.setColor(sized.text, sized.selectionStart, sized.selectionEnd, red)
        assertEquals(24, MarkdownActions.sizeIn(both.text, both.selectionStart, both.selectionEnd))
        assertEquals(red, MarkdownActions.colorIn(both.text, both.selectionStart, both.selectionEnd))
    }

    @Test
    fun `a marker in a sized span is added inside it`() {
        val result = MarkdownActions.toggleWrap("[I am big]{size=24}", 3, 5, "**")
        assertEquals("[I **am** big]{size=24}", result.text)
        assertEquals("I am big", rendered(result.text))
    }

    // ---- Markers inside the brackets ------------------------------------------------------------

    @Test
    fun `a marker pair round a tag is moved inside it by the first edit made`() {
        // `**[word]{size=24}**` and `[**word**]{size=24}` say the same thing; only the second can
        // be edited a word at a time, because only in the second is there nothing between a marker
        // and the text it styles. Every styling action puts the document into that form first.
        val result = MarkdownActions.toggleWrap("**[testofcolors]{size=24}**", 3, 7, "*")
        assertEquals("testofcolors", rendered(result.text))
        assertTrue(result.text, result.text.startsWith("["))
    }

    @Test
    fun `unbolding one word of a bold sized phrase leaves the rest of it bold`() {
        // Reported: the whole phrase lost its bold, because the cut had nowhere to land — the
        // markers were outside the brackets and the only place to cut was between a `]` and its
        // `{size=24}`.
        val result = MarkdownActions.toggleWrap("**[testofcolors]{size=24}**", 7, 9, "**")
        assertEquals("[**test**of**colors**]{size=24}", result.text)
        assertEquals("testofcolors", rendered(result.text))
        // And the size still covers all twelve characters, in one piece.
        assertEquals(24, styles(result.text, MdStyle.SIZE).single().arg)
        assertEquals(12, styles(result.text, MdStyle.SIZE).single().let { it.end - it.start })
    }

    @Test
    fun `an edge the field widened over hidden markup is pulled back to the text`() {
        // The offsets a text field hands back are not the ones the user pointed at: the syntax is
        // hidden, so a tap at the start of a bold sized word maps back to a source position before
        // the `**[` standing there. Taken at face value it wrote `***[test*ofcolors]{size=24}**`,
        // which is not markup at all — the reader saw the tag.
        val atEdge = MarkdownActions.toggleWrap("**[testofcolors]{size=24}**", 0, 7, "*")
        val atText = MarkdownActions.toggleWrap("**[testofcolors]{size=24}**", 3, 7, "*")
        assertEquals(atText.text, atEdge.text)
        assertEquals("testofcolors", rendered(atEdge.text))
    }

    @Test
    fun `two nested runs at both ends of one are written as siblings instead`() {
        // Two marker runs of the same symbol cannot touch: `***test*of*colors***` has a three at
        // each end, they pair off with each other, and the middle falls apart. Where a bracketed
        // span can stand between them, the run is cut into siblings and the brackets separate them.
        var out = MarkdownActions.toggleWrap("**[testofcolors]{size=24}**", 3, 7, "*")
        val colors = out.text.indexOf("colors")
        out = MarkdownActions.toggleWrap(out.text, colors, colors + 6, "*")

        assertEquals("testofcolors", rendered(out.text))
        val plan = MarkdownRenderer.plan(out.text)
        val text = rendered(out.text)
        val italic = plan.styles.filter { it.style == MdStyle.ITALIC }
            .joinToString("") { text.substring(it.start, it.end) }
        // Italic on exactly the two words asked for, and bold still on the whole phrase.
        assertEquals("testcolors", italic)
        assertEquals(12, plan.styles.filter { it.style == MdStyle.BOLD }.sumOf { it.end - it.start })
    }

    @Test
    fun `a selection reaching in and out of a tag is styled part by part`() {
        // One run of text with no single pair of markers around it. Wrapping it whole interleaved
        // the asterisks with the brackets — `**te*xt[te*stofcolors]{size=24}**` — and the tag came
        // out on screen as the punctuation it had become.
        val result = MarkdownActions.toggleWrap("**text[testofcolors]{size=24}**", 4, 9, "*")
        assertEquals("texttestofcolors", rendered(result.text))
        val text = rendered(result.text)
        val italic = MarkdownRenderer.plan(result.text).styles
            .filter { it.style == MdStyle.ITALIC }
            .joinToString("") { text.substring(it.start, it.end) }
        assertEquals("xtte", italic)
    }

    @Test
    fun `a run crossing a bracket is pushed inside it before anything is cut`() {
        val result = MarkdownActions.toggleWrap("**text[testofcolors]{size=24}**", 11, 13, "**")
        assertEquals("**text**[**test**of**colors**]{size=24}", result.text)
        assertEquals("texttestofcolors", rendered(result.text))
    }

    /** Source offset of the reader's character [n], which is what a tap on screen names. */
    private fun at(src: String, n: Int): Int {
        var seen = 0
        for (i in src.indices) {
            if (MarkdownRenderer.hidesAnythingIn(src, i, i + 1)) continue
            if (seen == n) return i
            seen++
        }
        return src.length
    }

    @Test
    fun `a style added across a bracket and then removed leaves the document as it was`() {
        // Reported: selecting "texttest" across the `[` of a sized run and pressing italic broke
        // the tag apart, and pressing it again finished the job. Both halves of that are the same
        // bug — the markers were being shuffled around syntax instead of the line being rewritten
        // from what it looks like — and the test for it is that the round trip is exact.
        val start = "**text[testofcolors]{size=24}**"
        val italic = MarkdownActions.toggleWrap(start, at(start, 0), at(start, 8), "*")
        assertEquals("texttestofcolors", rendered(italic.text))
        assertEquals("texttest", rendered(italic.text).substring(0, 8))
        assertEquals(
            "texttest",
            styles(italic.text, MdStyle.ITALIC)
                .joinToString("") { rendered(italic.text).substring(it.start, it.end) },
        )

        val back = MarkdownActions.toggleWrap(italic.text, italic.selectionStart, italic.selectionEnd, "*")
        assertEquals("**text**[**testofcolors**]{size=24}", back.text)
        assertTrue(back.text, styles(back.text, MdStyle.ITALIC).isEmpty())
        assertEquals("texttestofcolors", rendered(back.text))
    }

    @Test
    fun `the selection handed back is the words that were selected`() {
        // The step that turned one broken edit into a destroyed document: the range returned after
        // a multi-part edit was measured in source offsets that no longer meant anything, so the
        // *next* press landed on markup rather than words.
        val start = "**text[testofcolors]{size=24}**"
        val result = MarkdownActions.toggleWrap(start, at(start, 0), at(start, 8), "*")
        // Measured in characters the reader can see. The source offsets either side of the range
        // have moved and mean nothing; what has to hold is that the same eight letters are still
        // the ones under the selection.
        assertEquals(0, visibleBefore(result.text, result.selectionStart))
        assertEquals(8, visibleBefore(result.text, result.selectionEnd))
    }

    /** How many characters the reader can see before [at]. */
    private fun visibleBefore(src: String, at: Int): Int =
        (0 until at).count { !MarkdownRenderer.hidesAnythingIn(src, it, it + 1) }

    @Test
    fun `a colour is written the same way on a Turkish phone as anywhere else`() {
        // A device-locale `lowercase()` folds `I` to a dotless `ı`, and `#FFCC00` written that way
        // is a colour no other device can read back — an unreadable file produced by a language
        // setting. The hex here has no `I` in it, but `ABCDEF` is one edit away from having one.
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val result = MarkdownActions.setColor("word", 0, 4, 0xFFABCDEF.toInt())
            assertEquals("[word]{color=#abcdef}", result.text)
            assertEquals(0xFFABCDEF.toInt(), MarkdownActions.colorAt(result.text, 2))
        } finally {
            Locale.setDefault(original)
        }
    }
}
