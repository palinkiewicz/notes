package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Keeps the space that ends a word from landing against the marker that ends a run.
 *
 * Emphasis does not close on a marker preceded by whitespace — `**test **` is not bold text with a
 * space, it is four asterisks and a word — so a space typed at the end of a bold run used to take
 * the styling off everything the user had just written, and put the markers they had never typed on
 * screen. The space goes past the closing marker instead, which is the one place it can be that
 * changes neither what is written nor what it means.
 *
 * That leaves the caret outside a run the user has not finished writing, so the style is armed for
 * whatever comes next — and [MarkdownActions.applyPending] carries it on by taking the closer off
 * and putting it back after the new word, rather than starting a second run. Typing "test more" in
 * bold gives `**test more**`, one run, at every point along the way.
 *
 * Only against a *closing* marker. A space anywhere else inside a run is an ordinary space in
 * ordinary text, and moving it would be rewriting what somebody wrote.
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepSpaceOutside(private val pending: PendingStyles) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val written = changes.getRange(0)
        if (written.length == 0 || changes.getOriginalRange(0).length != 0) return

        // Spaces and tabs, and nothing else. A newline is [KeepInlineIntact]'s, and it ends the run
        // rather than stepping out of it.
        val spaces = toString().substring(written.start, written.end)
        if (spaces.isEmpty() || spaces.any { !it.isWhitespace() || it == '\n' }) return

        val before = originalText.toString()
        val at = changes.getOriginalRange(0).start
        val fix = MarkdownStructure.spaceOutsideRun(before, at, spaces) ?: return

        revertAllChanges()
        replace(fix.start, fix.end, fix.text)
        selection = TextRange(fix.caret)
        // Armed only where nothing else has a claim: a style the user pressed a button for at this
        // very caret is a statement about what comes next, and it outranks the one being carried.
        if (pending.isEmpty) pending.carry(fix.caret, MarkdownStructure.stylesOpenAt(before, at))
        else pending.clear()
    }
}
