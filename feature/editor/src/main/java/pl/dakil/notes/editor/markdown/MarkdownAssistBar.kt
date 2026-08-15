package pl.dakil.notes.editor.markdown

/**
 * The text transformations behind the formatting toolbar.
 *
 * Pure string functions taking and returning a selection range, so the behaviour people notice —
 * that bolding an empty selection leaves the caret between the markers, and that bolding twice
 * unbolds — is testable without a UI.
 */
object MarkdownActions {

    data class Result(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /**
     * Wraps the selection in [marker], or unwraps it when it is already wrapped.
     *
     * The toggle matters: a formatting button that only ever adds markers turns into a trap the
     * moment someone presses it twice.
     */
    fun toggleWrap(text: String, start: Int, end: Int, marker: String): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)

        val alreadyInside = from >= marker.length &&
            to + marker.length <= text.length &&
            text.regionMatches(from - marker.length, marker, 0, marker.length) &&
            text.regionMatches(to, marker, 0, marker.length)

        if (alreadyInside) {
            val out = text.removeRange(to, to + marker.length)
                .removeRange(from - marker.length, from)
            return Result(out, from - marker.length, to - marker.length)
        }

        val selected = text.substring(from, to)
        if (selected.startsWith(marker) && selected.endsWith(marker) && selected.length >= marker.length * 2) {
            val inner = selected.substring(marker.length, selected.length - marker.length)
            val out = text.replaceRange(from, to, inner)
            return Result(out, from, from + inner.length)
        }

        val out = text.replaceRange(from, to, "$marker$selected$marker")
        // With nothing selected the caret lands between the markers, ready to type.
        return Result(out, from + marker.length, from + marker.length + selected.length)
    }

    /** Applies or removes a line prefix (`# `, `> `, `- `) across every line the selection spans. */
    fun togglePrefix(text: String, start: Int, end: Int, prefix: String): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)

        val lineStart = text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }

        val region = text.substring(lineStart, lineEnd)
        val lines = region.split('\n')
        val allPrefixed = lines.all { it.trimStart().startsWith(prefix.trimEnd()) }

        val updated = lines.joinToString("\n") { line ->
            if (allPrefixed) {
                // Strip only one level, so nested quotes degrade one step at a time.
                val indent = line.takeWhile { it.isWhitespace() }
                indent + line.trimStart().removePrefix(prefix.trimEnd()).trimStart()
            } else {
                prefix + line
            }
        }

        val out = text.replaceRange(lineStart, lineEnd, updated)
        val delta = updated.length - region.length
        return Result(out, from + if (allPrefixed) -prefix.length else prefix.length, to + delta)
    }

    fun insertTaskItem(text: String, start: Int): Result = insertAtLineStart(text, start, "- [ ] ")

    fun insertBullet(text: String, start: Int): Result = insertAtLineStart(text, start, "- ")

    private fun insertAtLineStart(text: String, start: Int, prefix: String): Result {
        val caret = start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val out = text.replaceRange(lineStart, lineStart, prefix)
        return Result(out, caret + prefix.length, caret + prefix.length)
    }

    /** Inserts a fenced block, leaving the caret on the empty line inside it. */
    fun insertCodeFence(text: String, start: Int): Result {
        val caret = start.coerceIn(0, text.length)
        val prefix = if (caret == 0 || text[caret - 1] == '\n') "" else "\n"
        val snippet = "$prefix```\n\n```\n"
        val out = text.replaceRange(caret, caret, snippet)
        val inner = caret + prefix.length + 4
        return Result(out, inner, inner)
    }

    fun insertMath(text: String, start: Int, end: Int): Result = toggleWrap(text, start, end, "$")

    fun insertLink(text: String, start: Int, end: Int): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val label = text.substring(from, to)
        val out = text.replaceRange(from, to, "[$label]()")
        // Caret goes inside the parentheses: the label is usually already typed, the URL is not.
        val caret = from + label.length + 3
        return Result(out, caret, caret)
    }
}
