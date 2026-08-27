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
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepSpaceOutsideOnDelete(private val pending: PendingStyles) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        if (changes.getRange(0).length != 0) return
        val deleted = changes.getOriginalRange(0)
        if (deleted.length == 0) return

        val before = originalText.toString()
        val fix = MarkdownStructure.spaceOutsideRunAfterDeletion(before, deleted.start, deleted.end) ?: return

        revertAllChanges()
        replace(fix.start, fix.end, fix.text)
        selection = TextRange(fix.caret)
        // Armed only where nothing else has a claim, the same rule [KeepSpaceOutside] follows.
        if (pending.isEmpty) pending.carry(fix.caret, MarkdownStructure.stylesOpenAt(before, deleted.end))
        else pending.clear()
    }
}
