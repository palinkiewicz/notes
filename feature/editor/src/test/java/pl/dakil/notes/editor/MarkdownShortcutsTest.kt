package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.dakil.notes.editor.markdown.FormatCommand
import pl.dakil.notes.editor.markdown.MarkdownActions.BlockStyle
import pl.dakil.notes.editor.markdown.formatCommandFor
import pl.dakil.notes.editor.markdown.nestEdit
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutContext

/**
 * What the formatting keys do. The edits themselves are the format bar's, and covered there; what
 * is pinned here is which edit a key reaches, and when a key is left to do what it did before.
 */
class MarkdownShortcutsTest {

    @Test
    fun `the formatting keys reach the same markers as the format bar's buttons`() {
        assertEquals(FormatCommand.Wrap("**"), formatCommandFor(ShortcutAction.BOLD))
        assertEquals(FormatCommand.Wrap("*"), formatCommandFor(ShortcutAction.ITALIC))
        assertEquals(FormatCommand.Wrap("~~"), formatCommandFor(ShortcutAction.STRIKETHROUGH))
        assertEquals(FormatCommand.Wrap("`"), formatCommandFor(ShortcutAction.INLINE_CODE))
        assertEquals(FormatCommand.Block(BlockStyle.H2), formatCommandFor(ShortcutAction.HEADING_2))
        assertEquals(FormatCommand.Block(BlockStyle.ORDERED), formatCommandFor(ShortcutAction.NUMBERED_LIST))
    }

    @Test
    fun `every shortcut that only fires while typing is a formatting command`() {
        // A TEXT-only action with no command here would be a key the settings screen offers and
        // that then does nothing at all.
        for (action in ShortcutAction.entries) {
            if (action.contexts == setOf(ShortcutContext.TEXT)) {
                assertNotNull("$action does nothing", formatCommandFor(action))
            }
        }
    }

    @Test
    fun `tab in a list item nests it one level deeper`() {
        val text = "- one\n- two"
        val edit = nestEdit(text, 9, 9, deeper = true)
        assertEquals("- one\n  - two", edit?.text)
    }

    @Test
    fun `shift tab in a nested item brings it back out`() {
        val edit = nestEdit("- one\n  - two", 11, 11, deeper = false)
        assertEquals("- one\n- two", edit?.text)
    }

    @Test
    fun `shift tab on an item already at the margin is still taken, as a no-op`() {
        // Let through, it would move focus out of the note from the middle of a list.
        val edit = nestEdit("- one", 3, 3, deeper = false)
        assertEquals("- one", edit?.text)
    }

    @Test
    fun `tab outside a list is left alone`() {
        assertNull(nestEdit("plain words", 3, 3, deeper = true))
        assertNull(nestEdit("# Heading", 4, 4, deeper = false))
    }
}
