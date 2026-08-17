package pl.dakil.notes.editor.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * Carries a list marker onto the next line when the user presses Enter.
 *
 * This is the difference between "a text box that happens to hold Markdown" and something that
 * feels like a word processor: typing `- milk` ⏎ should give a second bullet, not a bare line the
 * user has to mark up again. Pressing Enter on an item that is still empty ends the list instead,
 * which is the only way out that does not involve deleting characters by hand.
 *
 * An [InputTransformation] rather than a keyboard action because it must fire for the IME's own
 * newline, hardware Enter, and a pasted line break alike.
 */
object ContinueList : InputTransformation {

    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformInput() {
        // Only a single typed newline. A paste of several lines is the user's own structure and
        // must not be rewritten.
        if (changes.changeCount != 1) return
        val change = changes.getRange(0)
        val content = asCharSequence()
        if (change.length != 1 || change.start >= content.length) return
        if (content[change.start] != '\n') return

        val caret = change.start + 1
        val lineStart = content.lastLineStart(change.start)
        val line = content.subSequence(lineStart, change.start).toString()
        val marker = continuationOf(line) ?: return

        if (line.length == markerLength(line)) {
            // The item was never filled in: drop the marker and leave an empty line to type on.
            replace(lineStart, caret, "")
            selection = TextRange(lineStart)
        } else {
            replace(caret, caret, marker)
            selection = TextRange(caret + marker.length)
        }
    }

    private fun CharSequence.lastLineStart(before: Int): Int {
        var i = before - 1
        while (i >= 0) {
            if (this[i] == '\n') return i + 1
            i--
        }
        return 0
    }

    /** The marker to start the next item with, or null if [line] is not a list item at all. */
    internal fun continuationOf(line: String): String? {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val body = line.substring(indent.length)

        TASK.find(body)?.let { return indent + it.groupValues[1] + " [ ] " }
        ORDERED.find(body)?.let { match ->
            val next = match.groupValues[1].toIntOrNull()?.plus(1) ?: return null
            return "$indent$next${match.groupValues[2]} "
        }
        BULLET.find(body)?.let { return indent + it.groupValues[1] + " " }
        return null
    }

    /** How much of [line] is marker rather than content. */
    private fun markerLength(line: String): Int {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val body = line.substring(indent.length)
        val match = TASK.find(body) ?: ORDERED.find(body) ?: BULLET.find(body) ?: return -1
        return indent.length + match.value.length
    }

    private val TASK = Regex("^([-*+])\\s+\\[[ xX]]\\s+")
    private val ORDERED = Regex("^(\\d+)([.)])\\s+")
    private val BULLET = Regex("^([-*+])\\s+")
}
