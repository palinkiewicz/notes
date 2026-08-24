package pl.dakil.notes.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
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
        // Each one is applied to the run the last handed back, so they nest in the order pressed.
        val result = MarkdownActions.applyPending("d", 0, 1, pending.stylesAt(0))
        assertEquals("**[d]{size=24}**", result.text)
        // Inside both, or the next keystroke would be neither bold nor 24.
        assertEquals(4, result.selectionEnd)
    }
}
