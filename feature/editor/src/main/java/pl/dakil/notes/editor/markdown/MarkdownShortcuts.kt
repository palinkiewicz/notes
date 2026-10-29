package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import pl.dakil.notes.editor.markdown.MarkdownActions.BlockStyle
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutContext
import pl.dakil.notes.ui.keys.ShortcutHandler

/**
 * What a formatting shortcut does — the same thing the matching button on the format bar does, by
 * the same function, so the two can never disagree about what "bold" means at a bare caret.
 */
internal sealed interface FormatCommand {
    data class Wrap(val marker: String) : FormatCommand
    data class Block(val style: BlockStyle) : FormatCommand
    data class Nest(val deeper: Boolean) : FormatCommand
    data object Link : FormatCommand
}

internal fun formatCommandFor(action: ShortcutAction): FormatCommand? = when (action) {
    ShortcutAction.BOLD -> FormatCommand.Wrap("**")
    ShortcutAction.ITALIC -> FormatCommand.Wrap("*")
    ShortcutAction.STRIKETHROUGH -> FormatCommand.Wrap("~~")
    ShortcutAction.INLINE_CODE -> FormatCommand.Wrap("`")
    ShortcutAction.HEADING_1 -> FormatCommand.Block(BlockStyle.H1)
    ShortcutAction.HEADING_2 -> FormatCommand.Block(BlockStyle.H2)
    ShortcutAction.HEADING_3 -> FormatCommand.Block(BlockStyle.H3)
    ShortcutAction.PARAGRAPH -> FormatCommand.Block(BlockStyle.PARAGRAPH)
    ShortcutAction.BULLET_LIST -> FormatCommand.Block(BlockStyle.BULLET)
    ShortcutAction.NUMBERED_LIST -> FormatCommand.Block(BlockStyle.ORDERED)
    ShortcutAction.TASK_LIST -> FormatCommand.Block(BlockStyle.TASK)
    ShortcutAction.INDENT -> FormatCommand.Nest(deeper = true)
    ShortcutAction.OUTDENT -> FormatCommand.Nest(deeper = false)
    ShortcutAction.LINK -> FormatCommand.Link
    else -> null
}

/**
 * The edit Tab or Shift+Tab makes, or null when it is not this editor's key to take.
 *
 * Only on a list item, which is the one place a Tab has a meaning Markdown agrees on. Shift+Tab on
 * an item already at the margin is still taken, as a no-op: letting it through would move focus out
 * of the note from the middle of a list, which is never what someone pressing it there wanted.
 */
internal fun nestEdit(text: String, start: Int, end: Int, deeper: Boolean): MarkdownActions.Result? {
    if (!MarkdownActions.canIndent(text, start, end)) return null
    return if (deeper) MarkdownActions.indentList(text, start, end) else MarkdownActions.outdentList(text, start, end)
}

/**
 * The formatting shortcuts, for as long as [enabled] — the caller passes whether its field holds the
 * caret, since a key pressed while focus is elsewhere is not about this text.
 *
 * [onRequestLink] opens the caller's link dialog; the dialog belongs to the screen, as it does for
 * the format bar's button, so without one Ctrl+K is left alone.
 */
@Composable
internal fun MarkdownShortcuts(
    state: TextFieldState,
    pending: PendingStyles,
    enabled: Boolean,
    onRequestLink: (() -> Unit)?,
) {
    ShortcutHandler(ShortcutContext.TEXT, enabled = enabled) { action ->
        when (val command = formatCommandFor(action)) {
            null -> false
            is FormatCommand.Wrap -> {
                state.applyWrap(pending, command.marker)
                true
            }
            is FormatCommand.Block -> {
                state.applyBlockToggle(pending, command.style)
                true
            }
            is FormatCommand.Nest -> {
                val text = state.text.toString()
                val selection = state.selection
                val edit = nestEdit(text, selection.start, selection.end, command.deeper)
                if (edit != null) state.applyAction(pending) { _, _, _ -> edit }
                edit != null
            }
            FormatCommand.Link -> {
                if (onRequestLink != null) {
                    pending.clear()
                    onRequestLink()
                }
                onRequestLink != null
            }
        }
    }
}
