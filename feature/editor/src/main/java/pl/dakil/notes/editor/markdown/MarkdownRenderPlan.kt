package pl.dakil.notes.editor.markdown

import pl.dakil.notes.editor.markdown.code.CodeHighlighter
import pl.dakil.notes.editor.markdown.code.CodeLanguages
import pl.dakil.notes.editor.markdown.code.CodeToken

/**
 * What a run of characters should look like once the syntax around it is gone.
 *
 * Deliberately not a Compose type: everything in this file is plain Kotlin so the whole "what does
 * this Markdown look like" question is answerable in a JUnit test, without a device and without a
 * composition. The mapping from these to `SpanStyle` lives in `MarkdownOutputTransformation`.
 */
enum class MdStyle {
    H1, H2, H3, H4, H5, H6,
    BOLD, ITALIC, STRIKE,
    CODE, MATH,
    LINK, IMAGE,
    QUOTE,

    /** The body of a fenced block: monospace, on the block's own background. */
    FENCE,

    /** The language name standing at the top of a fenced block. */
    FENCE_HEADER,

    CODE_KEYWORD, CODE_STRING, CODE_NUMBER, CODE_COMMENT, CODE_FUNCTION,

    /** A table cell: monospace, so the columns can be made to line up. */
    TABLE,

    /** A table's header row. */
    TABLE_HEADER,

    /** The box-drawing characters a table is ruled with. */
    TABLE_RULE,

    /** A substituted glyph — a bullet, a checkbox, a rule — rather than the user's own text. */
    MARKER,
}

/** Replace `[start, end)` of the source with [replacement]. An empty replacement hides it. */
data class MdEdit(val start: Int, val end: Int, val replacement: String)

data class MdStyleRange(val start: Int, val end: Int, val style: MdStyle)

/**
 * A recipe for turning Markdown source into what the reader sees.
 *
 * [edits] are in **source** coordinates, ascending and disjoint. [styles] are already in
 * **transformed** coordinates — that is, the offsets that remain once every edit has been applied.
 * Doing that conversion here rather than in the composable is what keeps the fiddly part testable:
 * an off-by-one in the mapping is a wrongly-coloured word, not a crash, and would otherwise only be
 * findable by eye.
 */
data class MarkdownRenderPlan(
    val edits: List<MdEdit>,
    val styles: List<MdStyleRange>,
)

/**
 * Plans the WYSIWYG rendering of a Markdown document.
 *
 * The output feeds a single text field, so everything has to be expressible as "replace these
 * characters" plus "style these characters". Block structure that a renderer would draw — a quote
 * bar, a checkbox, a horizontal rule — is therefore *substituted as a glyph* rather than drawn:
 * `> ` becomes `▏ `, `- [ ] ` becomes `☐  `, and a `---` line becomes a run of box-drawing dashes.
 * That is the whole trick that lets one ordinary text field behave like a word processor.
 *
 * The same trick carries the two block types that need real shape. A code block pads every line out
 * to the width of its widest, so its background paints a solid rectangle instead of hugging the
 * glyphs; a table pads its cells and swaps its pipes for box-drawing rules, so the columns line up.
 * Both keep every character the user typed exactly where it was, which is what lets them stay
 * editable in the formatted view.
 */
object MarkdownRenderer {

    private const val QUOTE_BAR = "▏ "
    private const val BULLET_GLYPH = "•  "
    private const val TASK_OPEN = "☐  "
    private const val TASK_DONE = "☑  "
    private const val RULE_GLYPH = "──────────"
    private const val IMAGE_GLYPH = "🖼 "

    /** Beyond this a "block" is one long line, and padding it out would waste more than it buys. */
    private const val MAX_BLOCK_WIDTH = 200

    fun plan(markdown: String): MarkdownRenderPlan {
        val edits = ArrayList<MdEdit>()
        val styles = ArrayList<MdStyleRange>()
        val lines = Lines(markdown)

        var k = 0
        while (k < lines.count) {
            k = when {
                MarkdownParser.FENCE.matchEntire(lines.text(k)) != null ->
                    planFence(markdown, lines, k, edits, styles)

                lines.text(k).trim() == "$$" -> planMathBlock(lines, k, edits, styles)

                lines.startsTable(k) -> planTable(markdown, lines, k, edits, styles)

                else -> {
                    scanLine(markdown, lines.start(k), lines.end(k), edits, styles)
                    k + 1
                }
            }
        }

        return MarkdownRenderPlan(edits, styles.map { it.mapped(edits) })
    }

    /**
     * Where [offset] in the source ends up once [edits] are applied.
     *
     * An offset that falls *inside* an edited range lands at the end of its replacement, which is
     * the only choice that keeps the function monotonic — and monotonic is what the text field
     * requires of any mapping it is handed.
     */
    fun transformedOffset(edits: List<MdEdit>, offset: Int, includeInsertionAt: Boolean = true): Int {
        var delta = 0
        for (edit in edits) {
            when {
                // Text inserted at exactly this offset can fall on either side of it, and which
                // side decides whether a style covers it. See [mapped].
                edit.start == edit.end && edit.start == offset ->
                    if (includeInsertionAt) delta += edit.replacement.length else return offset + delta

                edit.end <= offset -> delta += edit.replacement.length - (edit.end - edit.start)
                edit.start < offset -> delta += edit.replacement.length - (offset - edit.start)
                else -> return offset + delta
            }
        }
        return offset + delta
    }

    /**
     * A style range starts *before* any padding inserted at its first offset and ends *after* any
     * inserted at its last.
     *
     * Without that asymmetry an empty line inside a code block has nothing to style — its source
     * range is zero characters long, both ends land on the same side of the padding, and the block's
     * background breaks wherever the code has a blank line in it.
     */
    private fun MdStyleRange.mapped(edits: List<MdEdit>) = MdStyleRange(
        start = transformedOffset(edits, start, includeInsertionAt = false),
        end = transformedOffset(edits, end, includeInsertionAt = true),
        style = style,
    )

    // ---- Lines -------------------------------------------------------------------------------

    /** The document split into lines once, so no pass has to go looking for newlines again. */
    private class Lines(val source: String) {
        private val starts: IntArray
        private val ends: IntArray
        val count: Int

        init {
            val s = ArrayList<Int>()
            val e = ArrayList<Int>()
            var i = 0
            while (true) {
                val newline = source.indexOf('\n', i)
                s += i
                e += if (newline < 0) source.length else newline
                if (newline < 0) break
                i = newline + 1
            }
            starts = s.toIntArray()
            ends = e.toIntArray()
            count = starts.size
        }

        fun start(k: Int) = starts[k]
        fun end(k: Int) = ends[k]
        fun width(k: Int) = ends[k] - starts[k]
        fun text(k: Int): String = source.substring(starts[k], ends[k])

        /** The end of the line including its newline, so a whole line can be hidden without trace. */
        fun endInclusive(k: Int) = if (ends[k] < source.length) ends[k] + 1 else ends[k]

        /** A table is a row of pipes with a delimiter row under it — the header is what marks it. */
        fun startsTable(k: Int): Boolean =
            k + 1 < count &&
                text(k).contains('|') &&
                MarkdownParser.TABLE_DELIMITER.matches(text(k + 1)) &&
                text(k + 1).contains('|')
    }

    // ---- Fenced code -------------------------------------------------------------------------

    /**
     * Renders a fenced block and returns the line after it.
     *
     * The opening fence is not hidden outright: its language word is kept and shown as the block's
     * header, so the label the reader sees *is* the language in the source and editing one edits the
     * other. Only the backticks go.
     */
    private fun planFence(
        source: String,
        lines: Lines,
        open: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ): Int {
        val language = MarkdownParser.FENCE.matchEntire(lines.text(open))!!.groupValues[1]

        var close = open + 1
        while (close < lines.count && MarkdownParser.FENCE.matchEntire(lines.text(close)) == null) close++
        val lastBody = minOf(close, lines.count) - 1

        var width = language.length
        for (k in open + 1..lastBody) width = maxOf(width, lines.width(k))
        width = minOf(width + 2, MAX_BLOCK_WIDTH)

        if (language.isEmpty()) {
            edits += MdEdit(lines.start(open), lines.endInclusive(open), "")
        } else {
            val languageStart = lines.end(open) - lines.text(open).substringAfter("```").trimStart().length
            edits += MdEdit(lines.start(open), languageStart, "")
            edits += MdEdit(languageStart + language.length, lines.end(open), pad(width - language.length))
            styles += MdStyleRange(languageStart, lines.end(open), MdStyle.FENCE_HEADER)
        }

        for (k in open + 1..lastBody) {
            // Padding to a common width is what turns a background that hugs the glyphs into a
            // block: without it a two-character line paints a two-character box.
            edits += MdEdit(lines.end(k), lines.end(k), pad(width - lines.width(k)))
            styles += MdStyleRange(lines.start(k), lines.end(k), MdStyle.FENCE)
        }

        // Tokens after the block styles, so their colours win where the two overlap.
        if (lastBody >= open + 1) {
            highlight(source, language, lines.start(open + 1), lines.end(lastBody), styles)
        }

        if (close < lines.count) {
            edits += MdEdit(lines.start(close), lines.endInclusive(close), "")
        }
        return close + 1
    }

    /**
     * A `$$` block: the delimiters go, the formula is preserved verbatim and marked.
     *
     * Preserved rather than typeset — a real formula renderer does not fit the size budget, and
     * mangling the source would be worse than showing it plainly.
     */
    private fun planMathBlock(
        lines: Lines,
        open: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ): Int {
        var close = open + 1
        while (close < lines.count && lines.text(close).trim() != "$$") close++

        edits += MdEdit(lines.start(open), lines.endInclusive(open), "")
        for (k in open + 1 until minOf(close, lines.count)) {
            styles += MdStyleRange(lines.start(k), lines.end(k), MdStyle.MATH)
        }
        if (close < lines.count) edits += MdEdit(lines.start(close), lines.endInclusive(close), "")
        return close + 1
    }

    private fun highlight(
        source: String,
        language: String,
        from: Int,
        to: Int,
        styles: MutableList<MdStyleRange>,
    ) {
        val spec = CodeLanguages.of(language) ?: return
        CodeHighlighter.tokenize(source, spec, from, to) { start, end, token ->
            styles += MdStyleRange(
                start, end,
                when (token) {
                    CodeToken.KEYWORD -> MdStyle.CODE_KEYWORD
                    CodeToken.STRING -> MdStyle.CODE_STRING
                    CodeToken.NUMBER -> MdStyle.CODE_NUMBER
                    CodeToken.COMMENT -> MdStyle.CODE_COMMENT
                    CodeToken.FUNCTION -> MdStyle.CODE_FUNCTION
                },
            )
        }
    }

    // ---- Tables ------------------------------------------------------------------------------

    /**
     * Rules a table and returns the line after it.
     *
     * Cells are padded rather than rewritten, and every pipe becomes exactly one box-drawing
     * character. That one-for-one swap is the point: replacing whole rows with a pretty grid would
     * collapse every offset inside them, and the table would render beautifully and be impossible to
     * type in.
     *
     * Every row is given the *same structure* — same pipes in the same cells — because that is what
     * makes the columns line up no matter what the font does. `│` is not in the monospace face and
     * arrives from a fallback at some other width; identical structure means every row is wrong by
     * the same amount, which is to say right. The delimiter row is filled with ASCII hyphens for the
     * same reason: the box-drawing dash it used to use was wider than a space, and that one row
     * ended up longer than the table it belonged to.
     */
    private fun planTable(
        source: String,
        lines: Lines,
        first: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ): Int {
        var last = first
        while (last + 1 < lines.count && lines.text(last + 1).contains('|')) last++

        val rows = ArrayList<List<IntRange>>(last - first + 1)
        for (k in first..last) rows += cellsOf(lines.source, lines.start(k), lines.end(k))

        val columns = rows.maxOf { it.size }
        val widths = IntArray(columns)
        for ((index, row) in rows.withIndex()) {
            if (index == 1) continue // the delimiter row sizes itself to whatever the others need
            row.forEachIndexed { column, cell ->
                widths[column] = maxOf(widths[column], trimmedLength(source, cell))
            }
        }
        for (column in widths.indices) widths[column] = minOf(widths[column] + 2, MAX_BLOCK_WIDTH)

        for ((index, row) in rows.withIndex()) {
            val k = first + index
            val delimiter = index == 1
            styles += MdStyleRange(
                lines.start(k), lines.end(k),
                if (index == 0) MdStyle.TABLE_HEADER else MdStyle.TABLE,
            )

            var pipe = lines.start(k)
            var column = 0
            while (pipe < lines.end(k)) {
                if (source[pipe] == '|') {
                    edits += MdEdit(pipe, pipe + 1, "│")
                    styles += MdStyleRange(pipe, pipe + 1, MdStyle.TABLE_RULE)
                }
                if (column < row.size && pipe == row[column].first) {
                    val cell = row[column]
                    val target = widths.getOrElse(column) { trimmedLength(source, cell) + 2 }
                    if (delimiter) {
                        edits += MdEdit(cell.first, cell.last + 1, "-".repeat(target))
                        styles += MdStyleRange(cell.first, cell.last + 1, MdStyle.TABLE_RULE)
                    } else {
                        padCell(source, cell, target, edits)
                    }
                    pipe = cell.last + 1
                    column++
                    continue
                }
                pipe++
            }
        }
        return last + 1
    }

    /** The source ranges of each cell on a row, between its pipes. */
    private fun cellsOf(source: String, start: Int, end: Int): List<IntRange> {
        val pipes = ArrayList<Int>()
        for (i in start until end) if (source[i] == '|') pipes += i
        if (pipes.isEmpty()) return emptyList()

        val cells = ArrayList<IntRange>(pipes.size)
        // Content before the first pipe is a cell only when there is something in it; a leading `|`
        // is punctuation, not an empty first column.
        if (pipes.first() > start && source.substring(start, pipes.first()).isNotBlank()) {
            cells += start until pipes.first()
        }
        for (i in 0 until pipes.size - 1) cells += (pipes[i] + 1) until pipes[i + 1]
        if (pipes.last() + 1 < end && source.substring(pipes.last() + 1, end).isNotBlank()) {
            cells += (pipes.last() + 1) until end
        }
        return cells
    }

    /**
     * Widens or narrows one cell to [target] characters.
     *
     * Narrowing only ever hides spaces the user cannot see anyway, so a table someone has already
     * lined up by hand does not end up doubly padded.
     */
    private fun padCell(source: String, cell: IntRange, target: Int, edits: MutableList<MdEdit>) {
        val length = cell.last + 1 - cell.first
        if (length == target) return
        if (length < target) {
            edits += MdEdit(cell.last + 1, cell.last + 1, pad(target - length))
            return
        }
        var trailing = cell.last + 1
        while (trailing > cell.first && source[trailing - 1] == ' ') trailing--
        val removable = minOf(length - target, cell.last + 1 - trailing)
        if (removable > 0) edits += MdEdit(cell.last + 1 - removable, cell.last + 1, "")
    }

    private fun trimmedLength(source: String, cell: IntRange): Int =
        source.substring(cell.first, cell.last + 1).trim().length

    private fun pad(n: Int): String = if (n <= 0) "" else " ".repeat(n)

    // ---- Block level -------------------------------------------------------------------------

    private fun scanLine(
        text: String,
        start: Int,
        end: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ) {
        val line = text.substring(start, end)
        if (line.isBlank()) return

        if (MarkdownParser.RULE.matches(line)) {
            edits += MdEdit(start, end, RULE_GLYPH)
            styles += MdStyleRange(start, end, MdStyle.MARKER)
            return
        }

        MarkdownParser.HEADING.matchEntire(line)?.let { match ->
            val textStart = end - match.groupValues[2].length
            edits += MdEdit(start, textStart, "")
            styles += MdStyleRange(textStart, end, headingStyle(match.groupValues[1].length))
            scanInline(text, textStart, end, edits, styles)
            return
        }

        // Tasks before bullets: a task item is a bullet whose text happens to start with `[ ]`.
        MarkdownParser.TASK.matchEntire(line)?.let { match ->
            val markerStart = start + match.groupValues[1].length
            val textStart = end - match.groupValues[3].length
            val done = match.groupValues[2].equals("x", ignoreCase = true)
            edits += MdEdit(markerStart, textStart, if (done) TASK_DONE else TASK_OPEN)
            styles += MdStyleRange(markerStart, textStart, MdStyle.MARKER)
            if (done) styles += MdStyleRange(textStart, end, MdStyle.STRIKE)
            scanInline(text, textStart, end, edits, styles)
            return
        }

        MarkdownParser.BULLET.matchEntire(line)?.let { match ->
            val markerStart = start + match.groupValues[1].length
            val textStart = end - match.groupValues[2].length
            edits += MdEdit(markerStart, textStart, BULLET_GLYPH)
            styles += MdStyleRange(markerStart, textStart, MdStyle.MARKER)
            scanInline(text, textStart, end, edits, styles)
            return
        }

        // The number is not syntax to be hidden — it is what the reader is meant to see — so it is
        // only tinted, never removed.
        MarkdownParser.ORDERED.matchEntire(line)?.let { match ->
            val markerStart = start + match.groupValues[1].length
            val textStart = end - match.groupValues[3].length
            styles += MdStyleRange(markerStart, textStart, MdStyle.MARKER)
            scanInline(text, textStart, end, edits, styles)
            return
        }

        MarkdownParser.QUOTE.matchEntire(line)?.let { match ->
            val textStart = end - match.groupValues[1].length
            edits += MdEdit(start, textStart, QUOTE_BAR)
            styles += MdStyleRange(start, end, MdStyle.QUOTE)
            scanInline(text, textStart, end, edits, styles)
            return
        }

        scanInline(text, start, end, edits, styles)
    }

    private fun headingStyle(level: Int): MdStyle = when (level) {
        1 -> MdStyle.H1
        2 -> MdStyle.H2
        3 -> MdStyle.H3
        4 -> MdStyle.H4
        5 -> MdStyle.H5
        else -> MdStyle.H6
    }

    // ---- Inline level ------------------------------------------------------------------------

    /**
     * Scans `[from, to)` for inline syntax, hiding the markers and styling what they wrapped.
     *
     * Recurses into the body of every span so `**bold with *emphasis* inside**` gets both styles.
     * Edits are appended in ascending order — open marker, then the body's own edits, then close —
     * because the caller relies on that to apply them back-to-front without sorting.
     */
    private fun scanInline(
        text: String,
        from: Int,
        to: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ) {
        var i = from
        while (i < to) {
            // Code first: nothing inside a code span is Markdown.
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close in (i + 1) until to) {
                    edits += MdEdit(i, i + 1, "")
                    styles += MdStyleRange(i + 1, close, MdStyle.CODE)
                    edits += MdEdit(close, close + 1, "")
                    i = close + 1
                    continue
                }
            }

            if (text[i] == '$') {
                val close = text.indexOf('$', i + 1)
                if (close in (i + 1) until to) {
                    edits += MdEdit(i, i + 1, "")
                    styles += MdStyleRange(i + 1, close, MdStyle.MATH)
                    edits += MdEdit(close, close + 1, "")
                    i = close + 1
                    continue
                }
            }

            if (text.startsWith("![", i)) {
                val consumed = scanReference(text, i, to, edits, styles, image = true)
                if (consumed > 0) {
                    i = consumed
                    continue
                }
            }

            if (text[i] == '[') {
                val consumed = scanReference(text, i, to, edits, styles, image = false)
                if (consumed > 0) {
                    i = consumed
                    continue
                }
            }

            val emphasis = scanEmphasis(text, i, to, edits, styles)
            if (emphasis > 0) {
                i = emphasis
                continue
            }

            i++
        }
    }

    /** A run of emphasis markers and what wearing them means. */
    private class Emphasis(val marker: String, val styles: List<MdStyle>)

    /**
     * Longest marker first.
     *
     * The order is load-bearing: matching `**` inside `***both***` consumes two of the three
     * asterisks and leaves the third stranded in the middle of the bold text, in full view.
     */
    private val EMPHASIS = listOf(
        Emphasis("***", listOf(MdStyle.BOLD, MdStyle.ITALIC)),
        Emphasis("___", listOf(MdStyle.BOLD, MdStyle.ITALIC)),
        Emphasis("**", listOf(MdStyle.BOLD)),
        Emphasis("__", listOf(MdStyle.BOLD)),
        Emphasis("~~", listOf(MdStyle.STRIKE)),
        Emphasis("*", listOf(MdStyle.ITALIC)),
        Emphasis("_", listOf(MdStyle.ITALIC)),
    )

    /** Returns the offset just past the emphasis span starting at [i], or 0 if there is none. */
    private fun scanEmphasis(
        text: String,
        i: Int,
        to: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ): Int {
        for (rule in EMPHASIS) {
            val length = rule.marker.length
            if (!text.startsWith(rule.marker, i)) continue
            // Underscores inside a word are `snake_case`, not emphasis. Asterisks are not used that
            // way, so a doubled asterisk needs no such guard.
            if ((length == 1 || rule.marker[0] == '_') && isWordChar(text.getOrNull(i - 1))) continue

            val close = text.indexOf(rule.marker, i + length)
            // An empty span is left alone so a shorter marker gets its turn at the same characters.
            if (close <= i || close == i + length || close + length > to) continue

            edits += MdEdit(i, i + length, "")
            for (style in rule.styles) styles += MdStyleRange(i + length, close, style)
            scanInline(text, i + length, close, edits, styles)
            edits += MdEdit(close, close + length, "")
            return close + length
        }
        return 0
    }

    /**
     * Handles `[label](url)` and `![alt](url)`, returning the offset just past it, or 0 if this is
     * not one after all.
     */
    private fun scanReference(
        text: String,
        i: Int,
        to: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        image: Boolean,
    ): Int {
        val openLength = if (image) 2 else 1
        val close = text.indexOf(']', i + openLength)
        if (close < 0 || close >= to) return 0
        if (close + 1 >= to || text[close + 1] != '(') return 0
        val end = text.indexOf(')', close + 1)
        if (end < 0 || end >= to) return 0

        // The URL is hidden with the brackets: it is the destination, not the prose. An image keeps
        // a glyph in its place so an empty alt text does not vanish without trace.
        edits += MdEdit(i, i + openLength, if (image) IMAGE_GLYPH else "")
        styles += MdStyleRange(i, close, if (image) MdStyle.IMAGE else MdStyle.LINK)
        if (!image) scanInline(text, i + 1, close, edits, styles)
        edits += MdEdit(close, end + 1, "")
        return end + 1
    }

    private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')
}
