package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Deletes an escaped character together with its hidden backslash, never one without the other.
 *
 * The decision is [MarkdownStructure.keepEscapesWhole]'; this only carries it out. It runs after
 * the guards that re-aim a deletion, so it judges the deletion they settled on, whichever keyboard
 * asked for it and however that keyboard's range mapped back through the hidden backslash.
 */
@OptIn(ExperimentalFoundationApi::class)
object KeepEscapesWhole : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        if (changes.getRange(0).length != 0) return
        val deleted = changes.getOriginalRange(0)
        if (deleted.length == 0) return

        val (from, to) = MarkdownStructure.keepEscapesWhole(originalText.toString(), deleted.start, deleted.end) ?: return
        revertAllChanges()
        replace(from, to, "")
        selection = TextRange(from)
    }
}
