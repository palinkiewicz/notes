package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Keeps a keyboard's own correction from eating the markers round the word it corrected.
 *
 * An IME works on the text it can see, and the formatted view does not show a `**`. So when it
 * autocorrects "Aple" to "Apple" inside `**Aple**` it names the four characters it knows about, and
 * those offsets have to be mapped back through the runs the renderer struck out — where an offset
 * landing *on* a struck-out run maps to the whole of it. The range that comes back covers the
 * markers, and the correction takes the styling apart on a keystroke that was only about spelling.
 *
 * What puts the keyboard on that path is worth knowing, because it is why this shows up on styled
 * words and nowhere else: `TextFieldBuffer.replace` ends by committing the composition, so the
 * moment [ApplyPendingStyles] puts markers round the word being composed, the composing region is
 * gone and the keyboard re-establishes it by *region* — which is the one route that goes through
 * the widening above. Applying a style is what arms the bug.
 *
 * The rule enforced is simply that **an edit may not consume hidden characters at its own edges**;
 * the decision is [MarkdownStructure.keepMarkers]' and this only carries it out, so the fiddly part
 * stays testable without a text field.
 *
 * Replacements only. That is what keeps this orthogonal to the seven transformations either side of
 * it without a single shared condition: a pure deletion is [KeepBlocksIntact]'s and
 * [DropStrandedMarkers]', a pure insertion is [KeepFenceIntact]'s, [KeepInlineIntact]'s and
 * [ApplyPendingStyles]', and neither is ever seen here.
 */
@OptIn(ExperimentalFoundationApi::class)
object KeepMarkersIntact : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val replaced = changes.getOriginalRange(0)
        val written = changes.getRange(0)
        if (replaced.length == 0 || written.length == 0) return

        // A selection the user made by hand is theirs to type over, markers and all — and "by hand"
        // has to be judged on screen, the same way [KeepBlocksIntact] judges it: a caret resting
        // against hidden syntax is reported back as a range covering the whole hidden run, which
        // covers nothing the reader can see and is not a selection at all.
        val before = originalText.toString()
        if (originalSelection.length != 0 &&
            MarkdownRenderer.coversVisibleText(before, originalSelection.start, originalSelection.end)
        ) {
            return
        }

        val text = toString().substring(written.start, written.end)
        val fix = MarkdownStructure.keepMarkers(before, replaced.min, replaced.max, text) ?: return

        revertAllChanges()
        replace(fix.start, fix.end, fix.text)
        // Between the corrected word and the closing marker, which is where the next keystroke
        // belongs — past it would leave the run on the very next character typed.
        selection = TextRange(fix.caret)
    }
}
