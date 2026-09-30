package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Makes backspace mean what it looks like it means at the edge of a block.
 *
 * In the formatted view the characters that make a code block a code block are not on screen, so
 * the caret can be standing next to several of them without the user having any way to know. A
 * backspace there used to delete one of them and nothing visible: the fence stopped parsing, its
 * remains reflowed into whatever came next, and the table below lost a few characters to the same
 * keystroke. What the user meant by that press is *remove the block*, so that is what it does.
 *
 * The decision is [MarkdownStructure.backspaceAt]'s; this only carries it out. `revertAllChanges`
 * is what makes that possible — the field has already applied its own idea of the deletion by the
 * time an [InputTransformation] runs, and putting it back is the only way to replace it wholesale.
 */
@OptIn(ExperimentalFoundationApi::class)
object KeepBlocksIntact : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        // Something was typed rather than removed, or removed as part of typing over it.
        if (changes.getRange(0).length != 0) return

        val deleted = changes.getOriginalRange(0)
        if (deleted.length == 0) return
        // A selection the user made by hand is theirs to delete; only a bare caret is guessing —
        // and "bare" has to be judged on screen rather than in the source. A caret resting against
        // hidden syntax is reported back as a *range* covering the whole hidden run, which is not a
        // selection at all, and reading it as one is what let a backspace beside a code block eat
        // the closing fence and leave the rest of the note inside the block.
        //
        // Theirs to delete is not the same as theirs as written, though: the same widening puts
        // syntax on the *ends* of a real selection too, and that is [takeAsAsked]'s to trim. A
        // deletion that clears several characters off the screen at once goes the same way, whatever
        // the selection says, because it is a request rather than a keystroke.
        val before = originalText.toString()
        val asked = (
            originalSelection.length != 0 &&
                MarkdownRenderer.coversVisibleText(before, originalSelection.start, originalSelection.end)
            ) ||
            MarkdownRenderer.takesMoreThanOneVisibleCharacter(before, deleted.start, deleted.end)
        if (asked) {
            takeAsAsked(before, deleted)
            return
        }

        // Judged at the end of what went, not at the caret. A caret standing on hidden syntax maps
        // back to the start of it, so the deletion a backspace produces there runs *forwards* over
        // the characters the reader cannot see — and it is where it stopped that says which block
        // was underneath.
        when (val fix = MarkdownStructure.backspaceAt(before, deleted.end)) {
            // Nothing block-level is at stake, but the deletion may still have swept up inline
            // syntax on its way past: the caret sits between a letter and a hidden marker, and the
            // field cannot tell which of the two the user was pointing at. It was the letter.
            MdBackspace.Allow -> trimToVisible(before, deleted)
            MdBackspace.Refuse -> revertAllChanges()
            is MdBackspace.Remove -> {
                revertAllChanges()
                replace(fix.start, fix.end, "")
                selection = TextRange(fix.start)
            }
        }
    }

    /**
     * Carries out a deletion the user asked for as the range they asked for.
     *
     * A backspace takes one thing off the screen. A range that takes several is a *request* — the
     * user selected that much, or asked for a word — and re-aiming it at a single character, which
     * is what [trimToVisible] does for a keystroke, is how "delete half the note" came back as one
     * letter gone and the selection still standing. That is one of the bugs this exists for: an
     * AOSP-derived keyboard, FUTO's among them, answers backspace-with-a-selection by *collapsing*
     * the selection to its end and then asking for as many characters back as it had covered, so by
     * the time the deletion arrives there is no selection left to recognise it by and the range is
     * all there is.
     *
     * Granted, then — with the two corrections that hold whoever asked:
     *
     * 1. **Never past the caret.** Everything that deletes backwards deletes behind the caret; a
     *    range reaching in front of one was widened there by the offset mapping and by nothing else.
     *    Compose's own delete-previous-word at the end of the bold run in `Apple **is red** now.`
     *    asks for `red** n` — the word, the closing markers, and the first letter of the next word,
     *    because the caret sits against a hidden run and the run maps back whole. Cut at the caret it
     *    is `red`, which is the word that was asked for.
     * 2. **Nothing hanging off the ends.** What is left may still open or close on syntax nobody can
     *    see — see [MarkdownRenderer.visibleSpan]. Selecting `string` in `**This is my string**` and
     *    pressing delete used to leave `**This is my `, the closing pair gone with the word because
     *    the selection's end had mapped through it; selecting the lot left `**` standing over
     *    nothing. Syntax *between* the first and last visible character stays: the request covered it.
     */
    private fun TextFieldBuffer.takeAsAsked(before: String, deleted: TextRange) {
        val end = deleted.end.coerceIn(deleted.start, originalSelection.max)
        val (from, to) = MarkdownRenderer.visibleSpan(before, deleted.start, end) ?: return
        if (from == deleted.start && to == deleted.end) return
        revertAllChanges()
        replace(from, to, "")
        selection = TextRange(from)
    }

    /**
     * Narrows [deleted] to the one thing the reader could see and meant to remove.
     *
     * Only when hidden syntax was caught up in it — an ordinary deletion is left exactly alone, and
     * so is a marker that is redrawn rather than hidden, because that one is on screen and the list
     * keys downstream are the ones that know what to do with it.
     *
     * What the field asks for wherever hidden syntax meets the caret is not a near miss but an
     * arbitrary range: a keyboard counting in what it can see, told the caret's offset in a document
     * it cannot, asked for one press of backspace beside a heading and got `\n### Head`. So the
     * range is not trimmed towards what was asked for — it is worked out again from the caret, by
     * [MarkdownStructure.backspaceTarget].
     */
    private fun TextFieldBuffer.trimToVisible(before: String, deleted: TextRange) {
        if (!MarkdownRenderer.hidesAnythingIn(before, deleted.start, deleted.end)) return
        // A deletion that starts at the caret is a forward one, and this knows only what lies behind
        // a caret. Leaving it be is what it has always done with those.
        if (deleted.start >= originalSelection.max) return

        // Nothing behind the caret but another block's machinery: no better answer than the field's.
        val aim = MarkdownStructure.backspaceTarget(before, originalSelection.min) ?: return
        if (deleted.start == aim.start && deleted.end == aim.end) return

        revertAllChanges()
        replace(aim.start, aim.end, aim.text)
        selection = TextRange(aim.caret)
    }
}
