package pl.dakil.notes.editor.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import pl.dakil.notes.editor.markdown.MarkdownActions

/**
 * Space and backspace, pressed with the caret standing between a list marker and its label.
 *
 * That one position is the only place in a list where those two keys have an obvious second meaning,
 * and every outliner in the world uses it: space nests the item, backspace pulls it back out and
 * then — once there is no nesting left to undo — takes the marker off altogether. It is the fastest
 * way to build a nested list, and on a phone it is the *only* one that does not involve aiming at a
 * toolbar between every item.
 *
 * An [InputTransformation] rather than a key handler because the caret's position is the whole
 * trigger, and because the formatted view hides the marker: a backspace there is reported as a
 * deletion of the entire hidden run, and what matters is only that the run ended where the marker
 * does. That is the same test in both views, which is why one object serves both.
 */
@OptIn(ExperimentalFoundationApi::class)
object ListIndent : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val before = originalText.toString()
        val removed = changes.getOriginalRange(0)
        val added = changes.getRange(0)

        // A space typed at the marker's end. Judged on the original text, because the marker is the
        // one the user was looking at when they pressed the key.
        if (removed.length == 0 && added.length == 1 && asCharSequence()[added.start] == ' ') {
            if (removed.start != MarkdownActions.listMarkerEnd(before, removed.start)) return
            apply(MarkdownActions.indentList(before, removed.start, removed.start), before)
            return
        }

        // A backspace that stopped exactly where the marker does. Anything shorter is a keystroke
        // inside the label, and anything that ran past is a selection the user made themselves.
        if (added.length == 0 && removed.length > 0 && originalSelection.length == 0) {
            if (removed.end != MarkdownActions.listMarkerEnd(before, removed.end)) return
            apply(MarkdownActions.unindentOrUnlist(before, removed.end), before)
        }
    }

    /**
     * Puts [result] in place of whatever the field did, leaving the caret on the label.
     *
     * The whole edit is reverted first rather than added to: what the field applied was a space in
     * the wrong place or a marker half eaten, and neither is a starting point for the right answer.
     */
    private fun TextFieldBuffer.apply(result: MarkdownActions.Result, before: String) {
        if (result.text == before) return
        revertAllChanges()
        replace(0, length, result.text)
        selection = TextRange(result.selectionStart.coerceIn(0, length))
    }
}
