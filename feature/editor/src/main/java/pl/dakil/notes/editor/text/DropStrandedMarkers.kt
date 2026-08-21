package pl.dakil.notes.editor.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import pl.dakil.notes.editor.markdown.MarkdownStructure

/**
 * Takes the styling away with the last of the text that wore it.
 *
 * A bold run is two hidden markers with a word between them. Delete the word and the markers have
 * nothing left to style — and, having nothing to style, they stop being markers: `****` is four
 * asterisks to the parser and to the reader alike. So a user who deleted their own word watched
 * four characters they had never typed appear where it had been.
 *
 * Which is the *only* time styling is removed by a deletion. Markup is not the user's to lose while
 * any of the text it covers is still there, so this fires on nothing but a pair that has been left
 * empty, and never on a run that is merely shorter than it was.
 */
@OptIn(ExperimentalFoundationApi::class)
object DropStrandedMarkers : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        // Insertions cannot strand anything: markers are only ever left behind by what goes.
        if (changes.getRange(0).length != 0) return
        if (changes.getOriginalRange(0).length == 0) return

        // Where the text was taken from, which is the one place a pair can have closed up. Offsets
        // in front of it are the same in both documents, so this reads the same in either.
        val at = changes.getRange(0).start
        val edit = MarkdownStructure.strandedMarkers(originalText.toString(), toString(), at) ?: return

        replace(edit.start, edit.end, edit.text)
        selection = TextRange(edit.caret)
    }
}
