package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * [KeepSpaceOutside] run in reverse, for a deletion rather than an insertion.
 *
 * Backspacing the last word out of `**Apple is red**` removes it one visible character at a time,
 * and the keystroke that takes the final letter off "red" is no different from any other — until it
 * lands on the space that used to separate it from "is". What is left is `**Apple is **`, a closer
 * preceded by whitespace, which CommonMark will not read as a closer at all: a run the user never
 * touched comes apart on the keystroke that only removed a word they meant to remove.
 *
 * The decision is [MarkdownStructure.spaceOutsideRunAfterDeletion]'s; this only carries it out,
 * landing on `**Apple is** ` — exactly where the run would already be had the user finished "is"
 * with [KeepSpaceOutside]'s help before ever typing "red". Style is armed the same way that leaves,
 * so a word typed next carries on the same run instead of opening a second one.
 *
 * A run has two ends and a deletion can uncover a space at either, so
 * [MarkdownStructure.openerOutsideRunAfterDeletion] answers the same question for the *opening*
 * marker: taking `This is ` out of `**This is my string**` gives ` **my string**` rather than the
 * `** my string**` that is not markup at all. Only one of the two can apply to any one deletion —
 * they are different ends of different markers — so they are tried in turn.
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepSpaceOutsideOnDelete(private val pending: PendingStyles) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        if (changes.getRange(0).length != 0) return
        val deleted = changes.getOriginalRange(0)
        if (deleted.length == 0) return

        val before = originalText.toString()
        val closer = MarkdownStructure.spaceOutsideRunAfterDeletion(before, deleted.start, deleted.end)
        val fix = closer
            ?: MarkdownStructure.openerOutsideRunAfterDeletion(before, deleted.start, deleted.end)
            ?: return

        revertAllChanges()
        replace(fix.start, fix.end, fix.text)
        selection = TextRange(fix.caret)
        // Only the closing end leaves the caret outside a run the user has not finished writing, so
        // only that one has a style to arm: the opener's move puts the caret back *inside* the run,
        // where what is typed is already wearing it. Armed where nothing else has a claim, which is
        // the rule [KeepSpaceOutside] follows.
        if (closer == null) return
        if (pending.isEmpty) pending.carry(fix.caret, MarkdownStructure.stylesOpenAt(before, deleted.end))
        else pending.clear()
    }
}
