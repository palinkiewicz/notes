package pl.dakil.notes.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle
import pl.dakil.notes.editor.markdown.MdPending
import pl.dakil.notes.editor.markdown.PendingStyles

/**
 * Formatting asked for before there is anything to format.
 *
 * Pressing bold with nothing selected used to put `****` in the document, and an empty pair is not
 * markup: nothing hides it, so four asterisks the user never typed appeared in the formatted view.
 * The request waits instead, and these are the two halves of that — what is kept, and what it turns
 * into once there are words for it to wear.
 */
class MarkdownPendingStylesTest {

    @Test
    fun `a style armed at a caret goes round the text typed there`() {
        val styles = listOf(MdPending.Wrap("**"))
        // "d" has just been typed at offset 3 of "abcd".
        val result = MarkdownActions.applyPending("abcd", 3, 4, styles)
        assertEquals("abc**d**", result.text)
    }

    @Test
    fun `the caret lands inside the markers, so the rest of the word is bold too`() {
        val result = MarkdownActions.applyPending("d", 0, 1, listOf(MdPending.Wrap("**")))
        // Not 7: past the closing marker is where the next keystroke would leave the bold run
        // again, and typing "done" would give a bold "d" followed by a plain "one".
        assertEquals(3, result.selectionEnd)
    }

    @Test
    fun `two styles armed together both apply`() {
        val styles = listOf(MdPending.Wrap("**"), MdPending.Wrap("*"))
        assertEquals("***d***", MarkdownActions.applyPending("d", 0, 1, styles).text)
    }

    @Test
    fun `arming a style that is already in force ends the run instead of nesting`() {
        // The caret is inside a bold run and the user pressed bold: they mean the words they are
        // about to type not to be bold. Wrapping again would give them bold inside bold.
        val typed = "**word**".let { it.substring(0, 4) + "x" + it.substring(4) }
        val result = MarkdownActions.applyPending(typed, 4, 5, listOf(MdPending.Wrap("**")))
        assertEquals("**wo**x**rd**", result.text)
    }

    @Test
    fun `an armed size tags the text typed after it`() {
        val result = MarkdownActions.applyPending("d", 0, 1, listOf(MdPending.Size(24)))
        assertEquals("[d]{size=24}", result.text)
    }

    @Test
    fun `nothing armed leaves the text exactly as it was typed`() {
        val result = MarkdownActions.applyPending("abc", 2, 3, emptyList())
        assertEquals("abc", result.text)
        assertEquals(3, result.selectionEnd)
    }

    @Test
    fun `pressing a button twice takes the request back`() {
        val pending = PendingStyles()
        pending.toggleWrap(5, "**")
        pending.toggleWrap(5, "**")
        assertTrue(pending.isEmpty)
    }

    @Test
    fun `a request belongs to the caret it was made at`() {
        val pending = PendingStyles()
        pending.toggleWrap(5, "**")
        assertEquals(listOf(MdPending.Wrap("**")), pending.stylesAt(5))
        // The same document, one character further along: not the caret that asked for bold.
        assertEquals(emptyList<MdPending>(), pending.stylesAt(6))
    }

    @Test
    fun `moving the caret forgets what was armed`() {
        val pending = PendingStyles()
        pending.toggleWrap(5, "**")
        pending.keepAt(TextRange(5))
        assertTrue(!pending.isEmpty)

        pending.keepAt(TextRange(9))
        assertTrue(pending.isEmpty)
    }

    @Test
    fun `selecting text forgets what was armed`() {
        // A selection is something to format now, and the bar formats it outright — so whatever was
        // being held for a caret that no longer exists has to go with it.
        val pending = PendingStyles()
        pending.toggleWrap(5, "**")
        pending.keepAt(TextRange(5, 8))
        assertTrue(pending.isEmpty)
    }

    @Test
    fun `only one size can be armed at a time`() {
        // The size menu is a choice, not a toggle: picking 24 and then 12 means 12, and arming both
        // would put one tag inside the other.
        val pending = PendingStyles()
        pending.setSize(3, 24)
        pending.setSize(3, 12)
        assertEquals(listOf(MdPending.Size(12)), pending.stylesAt(3))
    }

    @Test
    fun `a size and a marker can be armed together`() {
        val pending = PendingStyles()
        pending.toggleWrap(0, "**")
        pending.setSize(0, 24)
        // Each one is applied to the run the last handed back. The markers end up *inside* the
        // brackets whichever order they were pressed in — see [MarkdownActions.canonical].
        val result = MarkdownActions.applyPending("d", 0, 1, pending.stylesAt(0))
        assertEquals("[**d**]{size=24}", result.text)
        // Inside both, or the next keystroke would be neither bold nor 24.
        assertEquals(4, result.selectionEnd)
    }

    // ---- Turning a style off ---------------------------------------------------------------

    @Test
    fun `turning a style off at the end of a run closes it rather than opening another`() {
        // The reported case. Bold on, "Apple", bold off, "s" — and what used to come out was
        // `**Apple**s****`, which looks right until anything else moves and is not Markdown.
        val typed = "**Apple**".let { it.substring(0, 7) + "s" + it.substring(7) }
        val result = MarkdownActions.applyPending(typed, 7, 8, listOf(MdPending.Wrap("**")))
        assertEquals("**Apple**s", result.text)
        assertEquals(10, result.selectionEnd)
    }

    @Test
    fun `turning a style off at the start of a run leaves the run where it was`() {
        val typed = "**Apple**".let { it.substring(0, 2) + "s" + it.substring(2) }
        val result = MarkdownActions.applyPending(typed, 2, 3, listOf(MdPending.Wrap("**")))
        assertEquals("s**Apple**", result.text)
        assertEquals(1, result.selectionEnd)
    }

    @Test
    fun `a style turned off leaves no empty pair behind`() {
        // Said the way the user says it: markers that style nothing are not markers, so nothing
        // hides them and asterisks nobody typed appear on the page.
        val typed = "**Apple**".let { it.substring(0, 7) + "s" + it.substring(7) }
        val result = MarkdownActions.applyPending(typed, 7, 8, listOf(MdPending.Wrap("**")))
        assertFalse('*' in MarkdownRenderer.render(result.text))
    }

    @Test
    fun `turning bold off inside a bold italic run leaves the text italic`() {
        // `***` is one span conferring two styles, so cancelling bold has to leave the italic
        // standing rather than take the whole run off the new text.
        val typed = "***Apple***".let { it.substring(0, 8) + "s" + it.substring(8) }
        val result = MarkdownActions.applyPending(typed, 8, 9, listOf(MdPending.Wrap("**")))
        assertEquals("***Apple****s*", result.text)
        assertItalicNotBold(result.text)
    }

    @Test
    fun `turning italic off inside a bold italic run leaves the text bold`() {
        val typed = "***Apple***".let { it.substring(0, 8) + "s" + it.substring(8) }
        val result = MarkdownActions.applyPending(typed, 8, 9, listOf(MdPending.Wrap("*")))
        assertBoldNotItalic(result.text)
    }

    @Test
    fun `turning bold off in the middle of a bold italic run keeps both halves as they were`() {
        val typed = "***Apple***".let { it.substring(0, 5) + "s" + it.substring(5) }
        val result = MarkdownActions.applyPending(typed, 5, 6, listOf(MdPending.Wrap("**")))
        assertItalicNotBold(result.text)
        // And nothing was stranded doing it.
        assertFalse('*' in MarkdownRenderer.render(result.text))
    }

    @Test
    fun `a second style pressed inside a run is added to it, not swapped for it`() {
        // A press of a button that is *not* lit says "this as well". Read as "the run being stood
        // in is longer than the marker pressed", italic inside a bold word came out as "end the
        // bold", and the rest of the sentence lost the styling it was being added to.
        val typed = "**Apple**".let { it.substring(0, 7) + "s" + it.substring(7) }
        val result = MarkdownActions.applyPending(typed, 7, 8, listOf(MdPending.Wrap("*")))
        assertEquals("**Apple*s***", result.text)
        assertBoldAndItalic(result.text)
    }

    @Test
    fun `bold pressed inside an italic run is added to it as well`() {
        // The mirror of it, and the case that always worked — the run being stood in is the shorter
        // of the two, so no length test ever mistook it for a cancellation.
        val typed = "*Apple*".let { it.substring(0, 6) + "s" + it.substring(6) }
        val result = MarkdownActions.applyPending(typed, 6, 7, listOf(MdPending.Wrap("**")))
        assertBoldAndItalic(result.text)
    }

    @Test
    fun `strikethrough pressed inside a bold run is added to it as well`() {
        val typed = "**Apple**".let { it.substring(0, 7) + "s" + it.substring(7) }
        val result = MarkdownActions.applyPending(typed, 7, 8, listOf(MdPending.Wrap("~~")))
        assertStyles(result.text, MdStyle.STRIKE, MdStyle.ITALIC)
        assertStyles(result.text, MdStyle.BOLD, MdStyle.ITALIC)
    }

    @Test
    fun `turning a style off where nothing has it on still turns it on`() {
        // The bar's lit buttons are a guess at a half-typed span and the parse is not. When they
        // disagree there is no run to close, and wrapping is the honest fallback.
        //
        // Half-typed is the point here: `**abcd` has no closing pair, so those two asterisks are
        // characters on screen rather than markup. Writing `**abc**d**` would have made them
        // markup — the reader would have seen "abc" turn bold, which nobody asked for — so the
        // writer refuses that spelling and reaches for the one form that cannot be misread.
        val result = MarkdownActions.applyPending("**abcd", 5, 6, listOf(MdPending.Wrap("**")))
        assertEquals("**abc<strong>d</strong>", result.text)
        assertEquals("**abcd", MarkdownRenderer.render(result.text))
    }

    @Test
    fun `a size armed inside a sized run only reaches the text typed there`() {
        // It used to rewrite the whole span: one character typed at "body" took the size off the
        // entire word around it.
        val typed = "[Apple]{size=24}".let { it.substring(0, 6) + "s" + it.substring(6) }
        val result = MarkdownActions.applyPending(typed, 6, 7, listOf(MdPending.Size(null)))
        assertEquals("[Apple]{size=24}s", result.text)
    }

    @Test
    fun `a new size inside a sized run leaves the rest of the run at its own size`() {
        val typed = "[Apple]{size=24}".let { it.substring(0, 3) + "s" + it.substring(3) }
        val result = MarkdownActions.applyPending(typed, 3, 4, listOf(MdPending.Size(18)))
        assertEquals("[Ap]{size=24}[s]{size=18}[ple]{size=24}", result.text)
    }

    // ---- Whitespace ------------------------------------------------------------------------

    @Test
    fun `a space typed at an armed caret is left outside the markers`() {
        // Most keyboards write the space before the next word rather than after the last one.
        // Spending the style on it gave `Apple** **`, and then `Apple** is**` — not emphasis to
        // any Markdown reader, because a marker may not touch whitespace.
        val result = MarkdownActions.applyPending("Apple ", 5, 6, listOf(MdPending.Wrap("**")))
        assertEquals("Apple ", result.text)
    }

    @Test
    fun `markers go round the word and not round the space in front of it`() {
        // The same keyboard, committing the space and the word together.
        val result = MarkdownActions.applyPending("Apple is", 5, 8, listOf(MdPending.Wrap("**")))
        assertEquals("Apple **is**", result.text)
    }

    @Test
    fun `turning a style off mid sentence reopens it after the space, not before it`() {
        // The reported case. `**apple**something** is red**` reopens bold on a space, which opens
        // nothing — so the second half stopped being bold and grew four visible asterisks.
        val typed = "**apple is red**".let { it.substring(0, 7) + "something" + it.substring(7) }
        val result = MarkdownActions.applyPending(typed, 7, 16, listOf(MdPending.Wrap("**")))
        assertEquals("**apple**something **is red**", result.text)
        assertEquals(18, result.selectionEnd)
        assertFalse('*' in MarkdownRenderer.render(result.text))
    }

    @Test
    fun `a style armed after the space that ended a run carries that run on`() {
        // Where [KeepSpaceOutside] leaves the caret: past the closing marker, with the style armed.
        // Wrapping the next word on its own would give `**test** **more**` — two runs where the
        // user wrote one phrase.
        val result = MarkdownActions.applyPending("**test** m", 9, 10, listOf(MdPending.Wrap("**")))
        assertEquals("**test m**", result.text)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `a style carried onto the next line starts a run there rather than stretching the last one`() {
        // Enter at the end of a bold word arms bold for the new line. Reaching back for the run
        // above it would put that run's closing marker on this line, which closes nothing and
        // unbolds the word above as well as this one.
        val result = MarkdownActions.applyPending("**Apple**\nP", 10, 11, listOf(MdPending.Wrap("**")))
        assertEquals("**Apple**\n**P**", result.text)
    }

    @Test
    fun `a run is only carried on across whitespace`() {
        // Real text since the run ended means the user moved on, and a second run is the honest
        // answer.
        val result = MarkdownActions.applyPending("**test** and m", 13, 14, listOf(MdPending.Wrap("**")))
        assertEquals("**test** and **m**", result.text)
    }

    @Test
    fun `a run is not reopened round the space a keyboard left behind it`() {
        // Turning bold off with "s " committed in one go. Reopening the run round what is left of
        // the old one gave `**Apple**s** **`, and a marker beside a space is not a marker: the
        // rendering was right and the file was not, which is the whole family of bug this is.
        val typed = "**Apple**".let { it.substring(0, 7) + "s " + it.substring(7) }
        val result = MarkdownActions.applyPending(typed, 7, 9, listOf(MdPending.Wrap("**")))
        assertEquals("**Apple**s ", result.text)
        assertFalse('*' in MarkdownRenderer.render(result.text))
    }

    @Test
    fun `markers never close on whitespace`() {
        val result = MarkdownActions.applyPending("is ", 0, 3, listOf(MdPending.Wrap("**")))
        assertEquals("**is** ", result.text)
    }

    // ---- What an armed marker says to the bar ---------------------------------------------------

    @Test
    fun `a carried triple marker is bold and italic, which is what it renders as`() {
        // The parse of `***word***` is one span three asterisks wide, so that is what a caret
        // carrying its style away holds. Asked which buttons it lights, it has to answer as the
        // document does — both — or a space typed at the end of a bold-italic word puts the bar out.
        assertEquals(setOf("**", "*"), MarkdownActions.inlineMarkersOf("***"))
        assertEquals(setOf("**"), MarkdownActions.inlineMarkersOf("**"))
        assertEquals(setOf("*"), MarkdownActions.inlineMarkersOf("*"))
        // Markers that are not a run of one symbol are already themselves.
        assertEquals(setOf("~~"), MarkdownActions.inlineMarkersOf("~~"))
        assertEquals(setOf("`"), MarkdownActions.inlineMarkersOf("`"))
    }

    @Test
    fun `pressing bold on a carried triple marker leaves italic armed`() {
        // The button was lit, so the press means "off" — and what is left has to be the other half
        // rather than a second `**` armed on top of a run that already had one.
        val armed = listOf<MdPending>(MdPending.Wrap("***"))
        assertEquals(listOf(MdPending.Wrap("*")), MarkdownActions.toggleArmedMarker(armed, "**"))
        assertEquals(listOf(MdPending.Wrap("**")), MarkdownActions.toggleArmedMarker(armed, "*"))
    }

    @Test
    fun `arming two markers keeps them apart, one per button`() {
        // Never combined into `***`: that is how the *document* writes them, and arming one before
        // there is text to style would hand `***` to [toggleWrap] as a marker it does not know.
        val bold = listOf<MdPending>(MdPending.Wrap("**"))
        assertEquals(
            listOf(MdPending.Wrap("**"), MdPending.Wrap("*")),
            MarkdownActions.toggleArmedMarker(bold, "*"),
        )
        assertEquals(
            listOf(MdPending.Wrap("**"), MdPending.Wrap("~~")),
            MarkdownActions.toggleArmedMarker(bold, "~~"),
        )
        // And a press of a lit button still turns it off.
        assertEquals(emptyList<MdPending>(), MarkdownActions.toggleArmedMarker(bold, "**"))
    }

    @Test
    fun `a size armed beside a wrap survives a wrap being toggled`() {
        val armed = listOf(MdPending.Wrap("**"), MdPending.Size(24))
        assertEquals(
            listOf(MdPending.Wrap("**"), MdPending.Wrap("*"), MdPending.Size(24)),
            MarkdownActions.toggleArmedMarker(armed, "*"),
        )
    }

    private fun assertBoldAndItalic(text: String) {
        assertStyles(text, MdStyle.BOLD, MdStyle.CODE)
        assertStyles(text, MdStyle.ITALIC, MdStyle.CODE)
    }

    /** Whether the one character that was typed came out wearing what it was meant to. */
    private fun assertItalicNotBold(text: String) = assertStyles(text, MdStyle.ITALIC, MdStyle.BOLD)

    private fun assertBoldNotItalic(text: String) = assertStyles(text, MdStyle.BOLD, MdStyle.ITALIC)

    private fun assertStyles(text: String, wanted: MdStyle, unwanted: MdStyle) {
        val plan = MarkdownRenderer.plan(text)
        val at = MarkdownRenderer.render(text).indexOf('s')
        assertTrue("no `s` in the rendering of $text", at >= 0)
        val worn = plan.styles.filter { at >= it.start && at < it.end }.map { it.style }
        assertTrue("$text: expected $wanted, got $worn", wanted in worn)
        assertFalse("$text: expected no $unwanted, got $worn", unwanted in worn)
    }
}
