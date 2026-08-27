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
 * Deliberately narrow. One plain insertion at the offset the styles were armed at — anything else
 * is not the sentence the user was about to write, and the armed style is dropped rather than
 * guessed at. Two insertions neither spend the style nor drop it, and both for the same reason,
 * that the words it was armed for have not arrived yet: whitespace on its own, and a bare line
 * break. Inline markup cannot cross a break, but an armed style is not markup — it is a plan, and
 * it travels to wherever the caret has gone. See below for both.
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
        val inserted = before.substring(typed.start, typed.end)
        if ('\n' in inserted) {
            // A break moves where the next words will be typed; it does not say the user has
            // changed their mind about how they are to look. Nothing in the document is at stake
            // either — the markers have not been written yet — so the armed style simply travels
            // to the new caret, which is what [KeepInlineIntact] does for a run that *is* written.
            // Dropping it here meant a second Enter forgot what the first one had just carried:
            // bold survived one line break and not two, for no reason the user could see.
            if (MarkdownStructure.breaksLineOnly(inserted)) pending.carry(selection.start, styles)
            else pending.clear()
            return
        }
        // A keyboard that writes the space before the next word has not started the word yet. The
        // style stays armed for whatever follows the space rather than being spent on it: markers
        // round a blank are not markup, and `Apple** is**` is what spending it here used to give.
        // Re-armed at the caret the field has just moved to, so the `keepAt` watching the selection
        // finds it where it expects to and keeps it.
        if (inserted.isBlank()) {
            pending.carry(selection.start, styles)
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
        selection = TextRange(caretFor(before, typed.end, inserted, result))
    }

    /**
     * Where the caret goes: inside the markers, unless the keyboard wrote a space after the word.
     *
     * Inside them is the ordinary answer, and the one people notice — it is what lets the rest of a
     * bold word be typed after the first letter of it turned bold. But a keyboard that commits
     * "is " in one go has put a character past the closing marker, and leaving the caret in front
     * of that space means the next keystroke jumps behind it. So the caret follows the insertion
     * instead, exactly where the field itself would have left it.
     *
     * Only when the text past the insertion really did come through untouched — [MarkdownActions]
     * is free to have rewritten a good deal more than the word, and where it did, its own answer is
     * the only one counted in the right coordinates.
     *
     * The style is not armed again either way: it has been spent, on the word it was armed for. A
     * space that arrives *on its own* never gets this far, and keeps it armed instead.
     */
    private fun caretFor(
        before: String,
        insertedEnd: Int,
        inserted: String,
        result: MarkdownActions.Result,
    ): Int {
        if (inserted.trimEnd() == inserted) return result.selectionEnd
        val tail = before.length - insertedEnd
        val past = result.text.length - tail
        val kept = past >= 0 && result.text.regionMatches(past, before, insertedEnd, tail)
        return if (kept) past else result.selectionEnd
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
