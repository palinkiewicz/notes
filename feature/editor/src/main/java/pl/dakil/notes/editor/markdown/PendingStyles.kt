package pl.dakil.notes.editor.markdown

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/** A style asked for before the text that is to wear it has been typed. */
sealed interface MdPending {

    /** Bold, italic, strikethrough or code — one of [MarkdownActions.toggleWrap]'s markers. */
    data class Wrap(val marker: String) : MdPending

    /** A font size, or null for the document's own. See [MarkdownActions.setSize]. */
    data class Size(val sp: Int?) : MdPending
}

/**
 * What the formatting bar has been asked for at a caret with nothing selected.
 *
 * Pressing bold with a selection is a text edit and needs nothing kept: the markers go round the
 * words. Pressing it with a bare caret is a statement about words that do not exist yet, and the
 * markers for those used to go into the document straight away — where an empty pair is not markup
 * at all. `****` styles nothing, so nothing hides it, and the user watched four asterisks they had
 * never typed appear in the formatted view. So the request is held here instead, and turns into
 * markers at the moment there is something between them to style.
 *
 * Held for one caret and no longer: [keepAt] drops it as soon as the caret moves, because a style
 * armed where the user is no longer typing is one they have visibly changed their mind about. That
 * is also what makes this safe to keep in a view model beside the field — there is no way for it to
 * go stale and surprise someone two edits later.
 *
 * The bar and the field have to share one of these. The bar arms it and lights its buttons from it;
 * [ApplyPendingStyles], in the field's own input chain, is what spends it.
 */
@Stable
class PendingStyles {

    /** Where the styles were armed, or -1 for none. */
    var caret by mutableStateOf(-1)
        private set

    /** In the order they were pressed, so `**` then `*` nests the way the presses read. */
    var styles by mutableStateOf<List<MdPending>>(emptyList())
        private set

    val isEmpty: Boolean get() = styles.isEmpty()

    /** What is armed for a caret at [offset] — nothing, once the caret has moved on. */
    fun stylesAt(offset: Int): List<MdPending> = if (offset == caret) styles else emptyList()

    /**
     * Turns [marker] on if it is off and off if it is on, at [offset].
     *
     * A press of a lit button is a press to turn something off, and that has to be armable too: the
     * caret standing inside a bold run is exactly where somebody types the words that end it.
     */
    fun toggleWrap(offset: Int, marker: String) {
        val armed = stylesAt(offset)
        caret = offset
        // Not by identity: a style carried onto this caret off the parse is written as the run it
        // came from, so bold armed as part of `***` has to answer to a press of the bold button.
        // See [MarkdownActions.toggleArmedMarker].
        styles = MarkdownActions.toggleArmedMarker(armed, marker)
    }

    /** Arms [sp] at [offset], replacing whatever size was armed there — sizes do not stack. */
    fun setSize(offset: Int, sp: Int?) {
        val armed = stylesAt(offset)
        caret = offset
        styles = armed.filterNot { it is MdPending.Size } + MdPending.Size(sp)
    }

    /**
     * Arms [styles] at [offset] outright, replacing whatever was armed before.
     *
     * For a caret that has just been *put* somewhere carrying styling with it — a line break taken
     * through the middle of a bold run leaves the new line bare, and what the user goes on typing
     * there is still meant to be bold. See [MarkdownStructure.lineBreak].
     */
    fun carry(offset: Int, styles: List<MdPending>) {
        if (styles.isEmpty()) {
            clear()
            return
        }
        caret = offset
        this.styles = styles
    }

    /** Forgets everything unless [selection] is still the bare caret the styles were armed at. */
    fun keepAt(selection: TextRange) {
        if (selection.collapsed && selection.start == caret) return
        clear()
    }

    fun clear() {
        if (caret == -1 && styles.isEmpty()) return
        caret = -1
        styles = emptyList()
    }
}
