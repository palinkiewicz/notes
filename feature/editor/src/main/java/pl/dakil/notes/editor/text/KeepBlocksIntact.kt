package pl.dakil.notes.editor.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdBackspace

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
        // A selection the user made by hand is theirs to delete; only a bare caret is guessing.
        if (originalSelection.length != 0) return

        // Judged at the end of what went, not at the caret. A caret standing on hidden syntax maps
        // back to the start of it, so the deletion a backspace produces there runs *forwards* over
        // the characters the reader cannot see — and it is where it stopped that says which block
        // was underneath.
        when (val fix = MarkdownStructure.backspaceAt(originalText.toString(), deleted.end)) {
            MdBackspace.Allow -> Unit
            MdBackspace.Refuse -> revertAllChanges()
            is MdBackspace.Remove -> {
                revertAllChanges()
                replace(fix.start, fix.end, "")
                selection = TextRange(fix.start)
            }
        }
    }
}
