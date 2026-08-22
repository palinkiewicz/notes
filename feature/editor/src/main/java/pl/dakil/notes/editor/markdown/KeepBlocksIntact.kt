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
        val before = originalText.toString()
        if (originalSelection.length != 0 &&
            MarkdownRenderer.coversVisibleText(before, originalSelection.start, originalSelection.end)
        ) {
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
