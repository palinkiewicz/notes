package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * Inline Markdown as the CommonMark and GFM specs define it, case by case.
 *
 * A `.md` note is opened in other editors too, and a line that reads one way here and another way
 * there is a note that means two things. These cases are taken from the specs' own examples (cited
 * by section) and from the ways the old scanner got them wrong in real notes; each checks what the
 * reader sees and which words wear which style, never the plan's internals.
 */
class MarkdownCommonMarkTest {

    private fun assertShows(source: String, visible: String) = assertEquals(source, visible, rendered(source))

    private fun assertStyled(source: String, style: MdStyle, vararg texts: String) =
        assertEquals("$style in $source", texts.toList(), styledText(source, style))

    private fun assertUnstyled(source: String, style: MdStyle) =
        assertTrue("$style in $source", styledText(source, style).isEmpty())

    // ---- §2.4 Backslash escapes ------------------------------------------------------------------

    @Test
    fun `an escaped asterisk is an asterisk and opens nothing`() {
        assertShows("\\*not emphasized*", "*not emphasized*")
        assertUnstyled("\\*not emphasized*", MdStyle.ITALIC)
    }

    @Test
    fun `escapes work for every marker this app reads`() {
        assertShows("\\_x\\_", "_x_")
        assertShows("\\`not code`", "`not code`")
        assertUnstyled("\\`not code`", MdStyle.CODE)
        assertShows("\\[not a link](u)", "[not a link](u)")
        assertShows("\\~~not struck~~", "~~not struck~~")
    }

    @Test
    fun `a backslash before a letter is just a backslash`() {
        assertShows("foo\\bar", "foo\\bar")
    }

    @Test
    fun `an escaped backslash leaves the marker after it free to open`() {
        assertShows("\\\\*emph*", "\\emph")
        assertStyled("\\\\*emph*", MdStyle.ITALIC, "emph")
    }

    @Test
    fun `escapes are not read inside code`() {
        assertStyled("`\\*`", MdStyle.CODE, "\\*")
    }

    // ---- §6.1 Code spans -------------------------------------------------------------------------

    @Test
    fun `a code span hides its backticks`() {
        assertShows("`foo`", "foo")
        assertStyled("`foo`", MdStyle.CODE, "foo")
    }

    @Test
    fun `a double backtick span can hold a single backtick`() {
        assertStyled("`` foo ` bar ``", MdStyle.CODE, "foo ` bar")
    }

    @Test
    fun `backticks with no closer of their own length are literal`() {
        // A bare pair used to become an empty code span that hid both of them.
        assertShows("``", "``")
        assertShows("`foo``", "`foo``")
        assertUnstyled("`foo``", MdStyle.CODE)
    }

    @Test
    fun `nothing inside a code span is markdown`() {
        assertStyled("`*not emph*`", MdStyle.CODE, "*not emph*")
        assertUnstyled("`*not emph*`", MdStyle.ITALIC)
    }

    @Test
    fun `a code span binds tighter than emphasis around it`() {
        assertShows("*a `*` b*", "a * b")
        assertStyled("*a `*` b*", MdStyle.ITALIC, "a * b")
    }

    // ---- §6.2 Emphasis and strong emphasis ------------------------------------------------------

    @Test
    fun `asterisks round a phrase make it italic`() {
        assertStyled("*foo bar*", MdStyle.ITALIC, "foo bar")
    }

    @Test
    fun `an opener followed by a space opens nothing`() {
        assertShows("a * foo bar*", "a * foo bar*")
        assertShows("2 * 3 * 4", "2 * 3 * 4")
    }

    @Test
    fun `asterisks work inside a word and underscores do not`() {
        assertStyled("foo*bar*", MdStyle.ITALIC, "bar")
        assertShows("snake_case_name", "snake_case_name")
        // The closer is followed by a letter, so it closes nothing — this used to come out italic.
        assertShows("_foo_bar", "_foo_bar")
        assertUnstyled("_foo_bar", MdStyle.ITALIC)
    }

    @Test
    fun `double markers are strong`() {
        assertStyled("__foo__", MdStyle.BOLD, "foo")
        assertStyled("**foo**", MdStyle.BOLD, "foo")
    }

    @Test
    fun `strong nested in emphasis gets both`() {
        assertStyled("*foo**bar**baz*", MdStyle.ITALIC, "foobarbaz")
        assertStyled("*foo**bar**baz*", MdStyle.BOLD, "bar")
        assertStyled("*a **b** c*", MdStyle.BOLD, "b")
    }

    @Test
    fun `a triple marker is one bold italic span`() {
        assertStyled("***strong emph***", MdStyle.BOLD, "strong emph")
        assertStyled("***strong emph***", MdStyle.ITALIC, "strong emph")
        // One span rather than emphasis round strong: the bar and the guards treat it as one run.
        assertEquals(1, MarkdownRenderer.plan("***x***").inline.size)
    }

    @Test
    fun `unequal runs leave the surplus on screen`() {
        assertShows("**foo*", "*foo")
        assertStyled("**foo*", MdStyle.ITALIC, "foo")
        assertShows("*foo**", "foo*")
    }

    @Test
    fun `emphasis nests inside emphasis`() {
        assertShows("*(*foo*)*", "(foo)")
        assertStyled("*(*foo*)*", MdStyle.ITALIC, "(foo)", "foo")
    }

    // ---- GFM strikethrough -----------------------------------------------------------------------

    @Test
    fun `two tildes strike and one does not`() {
        assertStyled("~~strike~~", MdStyle.STRIKE, "strike")
        assertShows("~single~", "~single~")
    }

    // ---- §6.3 Links ------------------------------------------------------------------------------

    @Test
    fun `a link destination may hold balanced parentheses`() {
        val source = "[x](https://en.wikipedia.org/wiki/A_(b))"
        assertShows(source, "x")
        assertStyled(source, MdStyle.LINK, "x")
    }

    @Test
    fun `link text may hold balanced brackets`() {
        assertShows("[a [b] c](u)", "a [b] c")
    }

    @Test
    fun `destinations in angle brackets and titles are hidden with the rest`() {
        assertShows("[link](</my uri>)", "link")
        assertShows("[link](u \"title\")", "link")
    }

    @Test
    fun `a space between text and destination makes no link`() {
        assertShows("[link] (u)", "[link] (u)")
    }

    @Test
    fun `emphasis cannot open outside a link and close inside it`() {
        val source = "*foo [bar*](/url)"
        assertShows(source, "*foo bar*")
        assertUnstyled(source, MdStyle.ITALIC)
        assertStyled(source, MdStyle.LINK, "bar*")
    }

    @Test
    fun `emphasis inside link text is emphasis`() {
        assertStyled("[**bold** link](u)", MdStyle.BOLD, "bold")
        assertStyled("*foo [bar](/url)*", MdStyle.ITALIC, "foo bar")
    }

    @Test
    fun `a link cannot contain another link`() {
        assertShows("[a [b](u) c](v)", "[a b c](v)")
    }

    @Test
    fun `references resolve in all three forms`() {
        // The definition's own line is hidden; only the line that uses it is in question.
        fun firstLine(source: String) = rendered(source).lines().first()
        assertEquals("foo", firstLine("[foo][bar]\n\n[bar]: /url"))
        assertEquals("bar", firstLine("[bar][]\n\n[bar]: /url"))
        assertEquals("Bar", firstLine("[Bar]\n\n[bar]: /url"))
        assertShows("[undefined]", "[undefined]")
    }

    @Test
    fun `an autolink shows its address`() {
        assertShows("<https://example.com>", "https://example.com")
        assertStyled("<https://example.com>", MdStyle.LINK, "https://example.com")
        assertShows("<not a link>", "<not a link>")
    }

    // ---- Pandoc maths ----------------------------------------------------------------------------

    @Test
    fun `prices are not maths`() {
        // The case that turned "5 and " into a formula in every note that mentioned money.
        assertShows("I paid $5 and $10", "I paid $5 and $10")
        assertUnstyled("I paid $5 and $10", MdStyle.MATH)
    }

    @Test
    fun `dollars round a formula are maths`() {
        assertStyled("\$x^2$", MdStyle.MATH, "x^2")
        assertStyled("$\$E=mc^2$$", MdStyle.MATH, "E=mc^2")
    }

    @Test
    fun `maths obeys pandoc's flanking rules`() {
        assertShows("$ x $", "$ x $")
        assertShows("\$x$5", "\$x$5")
        assertShows("\\\$x$", "\$x$")
    }

    // ---- Raw HTML emphasis -----------------------------------------------------------------------

    @Test
    fun `html emphasis tags style their contents and what is inside them`() {
        assertShows("<em>a *b*</em>", "a b")
        assertStyled("<em>a *b*</em>", MdStyle.ITALIC, "a b", "b")
    }

    // ---- Tables ----------------------------------------------------------------------------------

    @Test
    fun `an escaped pipe in a cell is shown without its backslash`() {
        val first = rendered("| a \\| b | c |\n|---|---|").lines().first()
        assertTrue(first, first.contains("a | b"))
    }
}
