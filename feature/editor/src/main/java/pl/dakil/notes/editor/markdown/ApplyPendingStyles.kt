package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Turns a style armed at a bare caret into real markers, round the first thing typed there.
 *
 * The other half of [PendingStyles]: the bar arms, this spends. It runs last in the field's input
 * chain, after the transformations that continue a list or guard a block, so that what it wraps is
 * the keystroke as those left it rather than as the keyboard sent it.
 *
 * Deliberately narrow. One plain insertion at the offset the styles were armed at, with no line
 * break in it — anything else is not the sentence the user was about to write, and the armed style
 * is dropped rather than guessed at. Inline markup cannot cross a line break, so an Enter ends the
 * chance to use it.
 */
@OptIn(ExperimentalFoundationApi::class)
class ApplyPendingStyles(private val pending: PendingStyles) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (pending.isEmpty) return
        if (changes.changeCount != 1) return

        val typed = changes.getRange(0)
        // Markers go round text, so only an insertion can call for them. A deletion that stranded a
        // pair is [DropStrandedMarkers]' business, and text typed over a selection is not this one's
        // either: the styles are only ever armed at a caret with nothing selected.
        if (typed.length == 0 || changes.getOriginalRange(0).length != 0) return

        val styles = pending.stylesAt(typed.start)
        if (styles.isEmpty()) return

        val before = toString()
        if ('\n' in before.substring(typed.start, typed.end)) {
            pending.clear()
            return
        }

        val result = MarkdownActions.applyPending(before, typed.start, typed.end, styles)
        pending.clear()
        if (result.text == before) return

        // Only the part that actually differs is replaced, rather than the whole document. What the
        // user is typing into is very often a word the keyboard still holds as composing text, and
        // rewriting characters it is not looking at is how that gets torn up.
        val head = commonPrefix(before, result.text)
        val tail = commonSuffix(before, result.text, head)
        replace(head, before.length - tail, result.text.substring(head, result.text.length - tail))
        // Inside the markers, where the next keystroke belongs — not after the closing one.
        selection = TextRange(result.selectionEnd)
    }

    private fun commonPrefix(before: String, after: String): Int {
        val limit = minOf(before.length, after.length)
        var i = 0
        while (i < limit && before[i] == after[i]) i++
        return i
    }

    /** Counted back from the end, never past [head] — the two halves must not overlap. */
    private fun commonSuffix(before: String, after: String, head: Int): Int {
        var i = 0
        while (i < before.length - head && i < after.length - head &&
            before[before.length - 1 - i] == after[after.length - 1 - i]
        ) {
            i++
        }
        return i
    }
}
