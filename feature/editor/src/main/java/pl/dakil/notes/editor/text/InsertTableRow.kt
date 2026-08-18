package pl.dakil.notes.editor.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import pl.dakil.notes.editor.markdown.MarkdownStructure

/**
 * Enter inside a table opens a new row instead of a new line.
 *
 * The same idea as [ContinueList] one step further along: a bare newline in the middle of a table
 * is a line with no pipes on it, which ends the table and leaves half of it as prose. What the user
 * meant was another row, so they get one — blank, with its pipes standing in the same columns, and
 * the caret in the cell below the one they were in.
 */
@OptIn(ExperimentalFoundationApi::class)
object InsertTableRow : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        // Typing over a selection is a replacement, and a paste of several lines is the user's own
        // structure; neither is the single Enter this is for.
        if (changes.getOriginalRange(0).length != 0) return
        val typed = changes.getRange(0)
        if (typed.length != 1 || typed.start >= length) return
        if (asCharSequence()[typed.start] != '\n') return

        val row = MarkdownStructure.rowBelow(originalText.toString(), originalSelection.start) ?: return
        revertAllChanges()
        replace(row.start, row.end, row.text)
        selection = TextRange(row.caret.coerceIn(0, length))
    }
}
