package pl.dakil.notes.editor.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Keeps typing on a code block's header line from breaking the fence.
 *
 * A fence's backticks are hidden, and a caret sitting on the hidden run maps back to the *start* of
 * it — which side a text field picks is its own business and not something the transformation can
 * ask for. So a language typed into an empty header lands in front of the backticks: `sql``` `,
 * which is no longer a fence, and the block the user was looking at disappears as they type into it.
 *
 * This puts it back on the other side. It fires only on the formatted view — in source mode the
 * backticks are visible and typing in front of them is a thing someone may well mean.
 */
@OptIn(ExperimentalFoundationApi::class)
object KeepFenceIntact : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        // Insertions only. A replacement had a selection behind it, and the user could see where.
        if (changes.getOriginalRange(0).length != 0) return

        val inserted = changes.getRange(0)
        if (inserted.length == 0) return

        val text = asCharSequence()
        if (!opensFenceAt(text, inserted.start, inserted.end)) return

        val typed = text.subSequence(inserted.start, inserted.end).toString()
        replace(inserted.start, inserted.end, "")
        val after = inserted.start + FENCE.length
        replace(after, after, typed)
        selection = TextRange(after + typed.length)
    }

    /**
     * Whether `[from, to)` was typed immediately in front of a fence marker standing at the start of
     * its own line — the one place an insertion is always wrong.
     */
    internal fun opensFenceAt(text: CharSequence, from: Int, to: Int): Boolean =
        from in 0 until to &&
            to + FENCE.length <= text.length &&
            (from == 0 || text[from - 1] == '\n') &&
            text.startsWith(FENCE, to)

    private const val FENCE = "```"
}
