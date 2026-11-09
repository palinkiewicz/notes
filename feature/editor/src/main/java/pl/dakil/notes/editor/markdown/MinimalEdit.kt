package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.text.input.TextFieldBuffer

/**
 * The smallest single replacement that turns [old] into [new], or null when they are equal.
 *
 * Everything the two share at the front and at the back is left out, so an action that rewrites a
 * line hands the field one line's worth of change rather than the whole document. That matters
 * more than it looks: a replacement covering the whole text ends the keyboard's composition and
 * makes the undo step a copy of the entire note, and both of those are felt as glitches — the
 * suggestion strip blanks, the caret jumps, undo takes back far more than the button did.
 *
 * The prefix is taken first and the suffix may not reach back into it, so a run of repeated
 * characters (`aa` to `aaa`) comes out as one insertion rather than two overlapping ranges.
 */
internal fun minimalEdit(old: CharSequence, new: CharSequence): MdEditAt? {
    val shorter = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < shorter && old[prefix] == new[prefix]) prefix++
    if (prefix == old.length && prefix == new.length) return null
    var suffix = 0
    while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    val text = new.subSequence(prefix, new.length - suffix).toString()
    return MdEditAt(prefix, old.length - suffix, text, prefix + text.length)
}

/** Makes the buffer read [text], by way of the smallest replacement that does it. See [minimalEdit]. */
internal fun TextFieldBuffer.replaceAllWith(text: String) {
    val edit = minimalEdit(asCharSequence(), text) ?: return
    replace(edit.start, edit.end, edit.text)
}
