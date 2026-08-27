package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Keeps formatting whole across a line break.
 *
 * Inline markup cannot cross a newline, so Enter pressed in the middle of a bold run turned the
 * run into two plain lines wearing four visible asterisks — a document taken apart by a keystroke
 * that added nothing to it, and on markers the formatted view had never shown. The run is ended at
 * the break and started again after it instead, which is what every word processor does and what
 * the user meant by pressing Enter in the middle of a word they had made bold.
 *
 * The decision is [MarkdownStructure.lineBreak]'s; this only carries it out. It runs after
 * [ContinueList] so that the break it rewrites is the one the field settled on, list marker and
 * all — and, like [KeepBlocksIntact], puts the field's own edit back before writing its own,
 * because replacing an edit wholesale is the only way to change one that has already been applied.
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepInlineIntact(private val pending: PendingStyles) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val inserted = changes.getRange(0)
        if (inserted.length == 0) return

        // A line break, and nothing but: what the field writes for Enter is a newline and at most
        // the next item's marker. Text that merely *contains* a newline is a paste — somebody
        // else's structure, and not this one's to rewrite.
        val separator = toString().substring(inserted.start, inserted.end)
        if (!MarkdownStructure.breaksLineOnly(separator)) return

        // Where the break went, in the document as it will stand once it is there. Enter pressed
        // over a selection deletes it first, and it is the text after that deletion the markers
        // have to be counted in — the selected words are already gone.
        val deleted = changes.getOriginalRange(0)
        val original = originalText.toString()
        val source = original.removeRange(deleted.start, deleted.end)
        val fix = MarkdownStructure.lineBreak(source, deleted.start, separator) ?: return

        revertAllChanges()
        if (deleted.length != 0) replace(deleted.start, deleted.end, "")
        replace(fix.edit.start, fix.edit.end, fix.edit.text)
        selection = TextRange(fix.edit.caret)
        // Styles that had no text on the new line to go round are armed for the next keystroke, so
        // that Enter at the end of a bold word leaves the user still typing in bold.
        pending.carry(fix.edit.caret, fix.carry)
    }
}
