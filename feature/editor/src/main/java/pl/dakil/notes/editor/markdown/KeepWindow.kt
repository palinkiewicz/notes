package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Keeps the `.md` screen's live field from acting on the parts of the document it is not showing.
 *
 * That field holds the whole note but draws only the block the caret is in (see
 * [MarkdownBlocks]); everything above and below is trimmed off by its output transformation, and a
 * trimmed range is a single point on screen. The field maps that point back to the *whole* range,
 * so without this a backspace against the top of a block deleted every block above it, and an
 * arrow key onto the edge selected half the note. Runs first, so every guard after it judges an
 * edit the user could actually have meant. The decisions are [MarkdownBlocks.repairChange] and
 * [MarkdownBlocks.repairSelection]; this only carries them out.
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepWindow(private val formatted: Boolean) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        val before = originalText.toString()
        val sel = originalSelection
        val layout = MarkdownBlocks.layout(before, sel.start, sel.end, formatted)
        val window = MarkdownBlocks.bounds(layout.windowStart, layout.windowEnd)

        when (changes.changeCount) {
            0 -> {
                val now = selection
                val fixed = MarkdownBlocks.repairSelection(
                    window, length, sel.start, sel.end, now.start, now.end,
                ) ?: return
                selection = TextRange(fixed.first, fixed.second)
            }

            1 -> {
                val original = changes.getOriginalRange(0)
                val range = changes.getRange(0)
                val fix = MarkdownBlocks.repairChange(
                    window, sel.start, sel.end, original.min, original.max, range.length,
                ) ?: return
                val inserted = asCharSequence().subSequence(range.min, range.max).toString()
                revertAllChanges()
                replace(fix.first, fix.last + 1, inserted)
                selection = TextRange(fix.first + inserted.length)
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is KeepWindow && other.formatted == formatted
    override fun hashCode(): Int = formatted.hashCode()
}
