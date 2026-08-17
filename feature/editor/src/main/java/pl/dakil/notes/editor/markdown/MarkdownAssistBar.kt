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
     * Works on the **length of the marker run** around the selection rather than on whether the
     * exact marker string is there, and that is what lets styles stack. In Markdown one asterisk is
     * italic and two are bold, so `***word***` is both — three asterisks is not "a bold marker with
     * a stray asterisk", it is one run carrying two styles. Matching the literal string instead made
     * the italic button read the `**` of an already-bold word as its own marker and *remove* it, so
     * bolding then italicising gave a word that was only italic. Each button now toggles its own
     * width in and out of the run and leaves the rest of it alone.
     */
    fun toggleWrap(text: String, start: Int, end: Int, marker: String): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val width = marker.length
        val symbol = marker[0]

        // Markers wrapped inside the selection rather than outside it — what a user who selected
        // `**word**` by hand has — are normalised to the outside case first.
        val inside = minOf(
            runForward(text, from, symbol, to),
            runBackward(text, to, symbol, from),
        )
        if (to - from >= width * 2 && inside >= width) {
            return toggleWrap(text, from + inside, to - inside, marker)
                .let { Result(it.text, it.selectionStart - inside, it.selectionEnd + inside) }
        }

        val run = minOf(
            runBackward(text, from, symbol, 0),
            runForward(text, to, symbol, text.length),
        )
        // A run of 3 is bold *and* italic, so italic is on when the run is odd and bold when it is
        // at least two. Anything wider than one style's own width belongs to another button.
        val alreadyOn = if (width == 1) run % 2 == 1 else run >= width

        if (alreadyOn) {
            val out = text.removeRange(to, to + width).removeRange(from - width, from)
            return Result(out, from - width, to - width)
        }

        val selected = text.substring(from, to)
        val out = text.replaceRange(from, to, "$marker$selected$marker")
        // With nothing selected the caret lands between the markers, ready to type.
        return Result(out, from + width, from + width + selected.length)
    }

    /** How many [symbol] characters run backwards from [at], stopping at [limit]. */
    private fun runBackward(text: String, at: Int, symbol: Char, limit: Int): Int {
        var i = at
        while (i > limit && text[i - 1] == symbol) i--
        return at - i
    }

    /** How many [symbol] characters run forwards from [at], stopping at [limit]. */
    private fun runForward(text: String, at: Int, symbol: Char, limit: Int): Int {
        var i = at
        while (i < limit && text[i] == symbol) i++
        return i - at
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

    // ---- Block style ------------------------------------------------------------------------

    /**
     * What a whole line is, as far as the formatting bar is concerned.
     *
     * Only one of these can be true of a line at a time, which is why they are a menu rather than a
     * row of toggles — and why [setBlockStyle] *sets* rather than toggling: picking "Heading 2" on a
     * quote has to produce a heading, not a quoted heading.
     */
    enum class BlockStyle(val prefix: String) {
        PARAGRAPH(""),
        H1("# "),
        H2("## "),
        H3("### "),
        H4("#### "),
        H5("##### "),
        H6("###### "),
        QUOTE("> "),
        BULLET("- "),
        TASK("- [ ] "),
        ORDERED("1. "),
    }

    /** Replaces whatever block prefix each selected line has with [style]'s. */
    fun setBlockStyle(text: String, start: Int, end: Int, style: BlockStyle): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val lineStart = lineStartAt(text, from)
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }

        val region = text.substring(lineStart, lineEnd)
        val updated = region.split('\n').mapIndexed { i, line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            // A numbered list across several lines has to actually count, or every line reads "1."
            // once the source is shown.
            val prefix = if (style == BlockStyle.ORDERED) "${i + 1}. " else style.prefix
            indent + prefix + line.drop(indent.length).removeBlockPrefix()
        }.joinToString("\n")

        val out = text.replaceRange(lineStart, lineEnd, updated)
        // Both ends shift by the first line's own delta, so a caret keeps its place in the words
        // rather than jumping to the start of the line.
        val firstDelta = updated.substringBefore('\n').length - region.substringBefore('\n').length
        return Result(
            text = out,
            selectionStart = (from + firstDelta).coerceIn(0, out.length),
            selectionEnd = (to + updated.length - region.length).coerceIn(0, out.length),
        )
    }

    /** The style [text]'s line at [offset] currently has, for the menu's checked item. */
    fun blockStyleAt(text: String, offset: Int): BlockStyle {
        val lineStart = lineStartAt(text, offset.coerceIn(0, text.length))
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val body = text.substring(lineStart, lineEnd).trimStart()
        return when {
            TASK_PREFIX.containsMatchIn(body) -> BlockStyle.TASK
            body.startsWith("###### ") -> BlockStyle.H6
            body.startsWith("##### ") -> BlockStyle.H5
            body.startsWith("#### ") -> BlockStyle.H4
            body.startsWith("### ") -> BlockStyle.H3
            body.startsWith("## ") -> BlockStyle.H2
            body.startsWith("# ") -> BlockStyle.H1
            body.startsWith("> ") || body == ">" -> BlockStyle.QUOTE
            BULLET_PREFIX.containsMatchIn(body) -> BlockStyle.BULLET
            ORDERED_PREFIX.containsMatchIn(body) -> BlockStyle.ORDERED
            else -> BlockStyle.PARAGRAPH
        }
    }

    /**
     * Toggles [style] on the selected lines: applies it, or clears back to a paragraph when every
     * line already has it.
     */
    fun toggleBlockStyle(text: String, start: Int, end: Int, style: BlockStyle): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val lineStart = lineStartAt(text, from)
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }

        var offset = lineStart
        var allMatch = true
        while (offset <= lineEnd) {
            if (blockStyleAt(text, offset) != style) {
                allMatch = false
                break
            }
            val newline = text.indexOf('\n', offset)
            if (newline < 0 || newline >= lineEnd) break
            offset = newline + 1
        }
        return setBlockStyle(text, start, end, if (allMatch) BlockStyle.PARAGRAPH else style)
    }

    // ---- Insertions -------------------------------------------------------------------------

    /** `[label](url)`, from the link dialog rather than from the selection. */
    fun insertLink(text: String, start: Int, end: Int, label: String, url: String): Result =
        insertReference(text, start, end, label, url, prefix = "[")

    /** `![alt](url)`. */
    fun insertImage(text: String, start: Int, end: Int, alt: String, url: String): Result =
        insertReference(text, start, end, alt, url, prefix = "![")

    private fun insertReference(
        text: String,
        start: Int,
        end: Int,
        label: String,
        url: String,
        prefix: String,
    ): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val snippet = "$prefix$label]($url)"
        val out = text.replaceRange(from, to, snippet)
        val caret = from + snippet.length
        return Result(out, caret, caret)
    }

    fun insertRule(text: String, start: Int): Result = insertBlock(text, start, "---\n")

    fun insertTable(text: String, start: Int): Result =
        insertBlock(text, start, "| Column | Column |\n| --- | --- |\n|  |  |\n")

    /** Puts [snippet] on a line of its own, leaving the caret after it. */
    private fun insertBlock(text: String, start: Int, snippet: String): Result {
        val caret = start.coerceIn(0, text.length)
        val lead = if (caret == 0 || text[caret - 1] == '\n') "" else "\n"
        val out = text.replaceRange(caret, caret, lead + snippet)
        val end = caret + lead.length + snippet.length
        return Result(out, end, end)
    }

    // ---- Queries ----------------------------------------------------------------------------

    /**
     * Which inline markers the selection sits inside, so the bar can show them pressed.
     *
     * Reads the marker run touching the selection — the same arithmetic [toggleWrap] applies — so a
     * `***word***` lights both bold and italic rather than one or neither. When nothing touches the
     * selection the caret is somewhere in the middle of a span, and the parity of the markers before
     * it is the cheap answer; a bar whose lit buttons lag a keystroke behind would be worse than one
     * that is occasionally optimistic about a half-typed span.
     */
    fun activeInlineMarkers(text: String, start: Int, end: Int): Set<String> {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val active = HashSet<String>()

        for (marker in INLINE_MARKERS) {
            val symbol = marker[0]
            val outer = minOf(
                runBackward(text, from, symbol, 0),
                runForward(text, to, symbol, text.length),
            )
            val inner = if (to - from >= marker.length * 2) {
                minOf(runForward(text, from, symbol, to), runBackward(text, to, symbol, from))
            } else {
                0
            }
            val run = maxOf(outer, inner)
            val on = if (run > 0) {
                if (marker.length == 1) run % 2 == 1 else run >= marker.length
            } else {
                countOccurrences(text, 0, from, marker) % 2 == 1 &&
                    countOccurrences(text, to, text.length, marker) > 0
            }
            if (on) active += marker
        }
        return active
    }

    private fun countOccurrences(text: String, from: Int, to: Int, marker: String): Int {
        var count = 0
        var i = from
        while (i <= to - marker.length) {
            if (text.startsWith(marker, i)) {
                count++
                i += marker.length
            } else {
                i++
            }
        }
        return count
    }

    private fun lineStartAt(text: String, offset: Int): Int =
        text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

    /** Strips whatever block marker a line starts with, leaving its prose. */
    private fun String.removeBlockPrefix(): String {
        TASK_PREFIX.find(this)?.let { return substring(it.value.length) }
        HEADING_PREFIX.find(this)?.let { return substring(it.value.length) }
        QUOTE_PREFIX.find(this)?.let { return substring(it.value.length) }
        BULLET_PREFIX.find(this)?.let { return substring(it.value.length) }
        ORDERED_PREFIX.find(this)?.let { return substring(it.value.length) }
        return this
    }

    private val HEADING_PREFIX = Regex("^#{1,6}\\s+")
    private val QUOTE_PREFIX = Regex("^>\\s?")
    private val BULLET_PREFIX = Regex("^[-*+]\\s+")
    private val ORDERED_PREFIX = Regex("^\\d+[.)]\\s+")
    private val TASK_PREFIX = Regex("^[-*+]\\s+\\[[ xX]]\\s*")

    /** Longest first, so `**` is tested before `*`. */
    private val INLINE_MARKERS = listOf("**", "~~", "*", "`", "$")

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
