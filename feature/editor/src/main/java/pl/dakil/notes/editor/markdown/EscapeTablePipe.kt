package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Writes a pipe typed into a table cell as `\|`, so it is a character and not a new column.
 *
 * The decision is [MarkdownStructure.escapeTypedPipes]'; this only carries it out. Formatted view
 * only: in source mode the pipes are on screen and typing one is something a person can mean.
 */
@OptIn(ExperimentalFoundationApi::class)
object EscapeTablePipe : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val written = changes.getRange(0)
        if (written.length == 0) return
        val replaced = changes.getOriginalRange(0)

        val before = originalText.toString()
        val typed = toString().substring(written.start, written.end)
        // Judged in the document as it stands once whatever was typed over has gone.
        val source = before.removeRange(replaced.start, replaced.end)
        val escaped = MarkdownStructure.escapeTypedPipes(source, replaced.start, typed) ?: return

        replace(written.start, written.end, escaped)
        selection = TextRange(written.start + escaped.length)
    }
}
