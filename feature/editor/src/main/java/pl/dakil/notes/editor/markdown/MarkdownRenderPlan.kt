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

    /** The body of a fenced block: monospace, on the block's own drawn background. */
    FENCE,

    /** The language name standing at the top of a fenced block. */
    FENCE_HEADER,

    CODE_KEYWORD, CODE_STRING, CODE_NUMBER, CODE_COMMENT, CODE_FUNCTION,

    /** A table cell: monospace, so the columns can be made to line up. */
    TABLE,

    /** A table's header row. */
    TABLE_HEADER,

    /** A substituted glyph — a bullet — rather than the user's own text. */
    MARKER,

    /** The blank a checkbox stands in: monospace, so its width is known before the box is placed. */
    TASK_BOX,

    /**
     * Extra leading, worn by the newline that ends a line.
     *
     * A line is as tall as the tallest thing on it, and a newline is a thing on a line. So the way
     * to give a document more air — without a `ParagraphStyle`, which cannot be used here — is to
     * make every line terminator taller than the text it follows. It is purely additive: a heading
     * already taller than this keeps its own height, and nothing is ever squeezed.
     */
    LEADING,

    /** The same, tighter, inside a block that is meant to read densely. */
    LEADING_TIGHT,

    /** A blank line standing between a block and its neighbour. */
    BLOCK_GAP,
}

/** Replace `[start, end)` of the source with [replacement]. An empty replacement hides it. */
data class MdEdit(val start: Int, val end: Int, val replacement: String)

data class MdStyleRange(val start: Int, val end: Int, val style: MdStyle)

/**
 * Something to be *drawn* rather than spelled out in characters.
 *
 * A box round a code block, the grid of a table, the bar beside a quote: shapes a renderer draws and
 * a text field cannot type. Each carries the offsets it covers, and the composable turns those into
 * rectangles through the field's own `TextLayoutResult`.
 */
sealed interface MdDecoration

/**
 * A fenced code block, header line included.
 *
 * [headerStart] and [headerEnd] bracket the language word and are *equal* when the fence carries no
 * language — the header line is then empty and the caret can sit in it, which is what lets the user
 * type a language into the placeholder the drawing layer paints there.
 *
 * [sourceStart] and [sourceEnd] are in **source** coordinates, not transformed ones: they are what
 * the Copy button puts on the clipboard, and that has to be the code as written.
 */
data class MdCodeBlock(
    val start: Int,
    val end: Int,
    val headerStart: Int,
    val headerEnd: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
) : MdDecoration

/**
 * A pipe table.
 *
 * [rows] are the rows that survive into the rendered text — the delimiter row is hidden, so it is
 * not among them. [columnStops] are the offsets of the header row's pipes, each of which has been
 * replaced by a single space for a real rule to be drawn down the middle of.
 */
data class MdTable(
    val start: Int,
    val end: Int,
    val headerEnd: Int,
    val rows: List<IntRange>,
    val columnStops: List<Int>,
) : MdDecoration

/** A `---` line, emptied of characters so a full-width rule can be drawn across it. */
data class MdRule(val offset: Int) : MdDecoration

/** One or more consecutive `>` lines, sharing a single bar. */
data class MdQuote(val start: Int, val end: Int) : MdDecoration

/**
 * A task item's checkbox.
 *
 * [offset] is the blank the box stands in, in transformed coordinates like every other decoration.
 * [sourceMark] is not: it is the **source** offset of the single character between the brackets,
 * which is what a tap rewrites. Flipping one character rather than the line leaves the caret, the
 * undo history and the rest of the user's markup exactly where they were.
 */
data class MdTask(val offset: Int, val checked: Boolean, val sourceMark: Int) : MdDecoration

/**
 * A recipe for turning Markdown source into what the reader sees.
 *
 * [edits] are in **source** coordinates, ascending and disjoint. Everything else is already in
 * **transformed** coordinates — that is, the offsets that remain once every edit has been applied.
 * Doing that conversion here rather than in the composable is what keeps the fiddly part testable:
 * an off-by-one in the mapping is a wrongly-drawn border, not a crash, and would otherwise only be
 * findable by eye.
 */
data class MarkdownRenderPlan(
    val edits: List<MdEdit>,
    val styles: List<MdStyleRange>,
    val decorations: List<MdDecoration>,
)

/**
 * Plans the WYSIWYG rendering of a Markdown document.
 *
 * Inline syntax is expressible as "replace these characters" plus "style these characters", so that
 * is how it is done: `**` is deleted outright and what it wrapped is made bold, `- ` becomes a
 * bullet glyph, a checkbox becomes `☐`. That is the trick that lets one ordinary text field behave
 * like a word processor.
 *
 * Block *shape* is not expressible that way, and trying anyway is how a code block ends up being a
 * background colour behind lines padded with spaces. So blocks emit a third thing — a [MdDecoration]
 * — naming the offsets a box, a grid or a rule should be drawn around. The characters stay exactly
 * where the user typed them and the geometry is drawn over the top, which is what keeps the block
 * both good-looking and editable.
 */
object MarkdownRenderer {

    private const val BULLET_GLYPH = "•  "
    private const val IMAGE_GLYPH = "🖼 "

    /**
     * What stands in the text where a checkbox goes.
     *
     * Nothing is spelled out there — the box is a real control floated over this blank — so all the
     * run has to do is be wide enough to hold one. Three monospace spaces at 14 sp is a shade over
     * 25 dp, which clears a 24 dp checkbox with a little to spare, and grows rather than shrinks
     * when the user scales their fonts up. Proportional spaces are a third as wide and the box
     * would sit on the first word.
     */
    private const val TASK_BLANK = "   "

    /**
     * What clears the left edge of a drawn block.
     *
     * Spaces rather than a `ParagraphStyle` with a `textIndent`, which is what this obviously wants
     * to be. Compose lays each paragraph out as its own block of text, and a paragraph whose range
     * ends on a newline — which every range over whole lines does — comes back one line taller than
     * its text, opening a gap under every block. Two spaces of monospace are exact, predictable and
     * carry no ink.
     */
    private const val INDENT = "  "

    /** The same, in a proportional face, where a space is narrower. */
    private const val QUOTE_INDENT = "   "

    /** Beyond this a table cell is a paragraph, and padding it out would waste more than it buys. */
    private const val MAX_CELL_WIDTH = 200

    // The transformation and the drawing layer both ask for the plan of the same string on the same
    // frame. Both run on the main thread, so one slot is enough to make the second call free.
    private var lastSource: String? = null
    private var lastPlan: MarkdownRenderPlan? = null

    fun plan(markdown: String): MarkdownRenderPlan {
        lastPlan?.let { if (lastSource == markdown) return it }

        val edits = ArrayList<MdEdit>()
        val styles = ArrayList<MdStyleRange>()
        val decorations = ArrayList<MdDecoration>()
        val lines = Lines(markdown)

        var k = 0
        while (k < lines.count) {
            k = when {
                MarkdownParser.FENCE.matchEntire(lines.text(k)) != null ->
                    planFence(markdown, lines, k, edits, styles, decorations)

                lines.text(k).trim() == "$$" -> planMathBlock(lines, k, edits, styles)

                lines.startsTable(k) -> planTable(markdown, lines, k, edits, styles, decorations)

                else -> {
                    scanLine(lines, k, edits, styles, decorations)
                    k + 1
                }
            }
        }

        val result = MarkdownRenderPlan(
            edits = edits,
            styles = styles.map { it.mapped(edits) },
            decorations = decorations.map { it.mapped(edits) },
        )
        lastSource = markdown
        lastPlan = result
        return result
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
     * Hangs [style] on the newline that ends line [k], if it has one.
     *
     * Nothing is emitted for the document's last line: there is no terminator to hang it on, and no
     * line below for the space to separate it from.
     */
    private fun leading(lines: Lines, k: Int, styles: MutableList<MdStyleRange>, style: MdStyle) {
        if (lines.end(k) >= lines.source.length) return
        styles += MdStyleRange(lines.end(k), lines.end(k) + 1, style)
    }

    /**
     * Puts a blank line at [anchor] to hold a block off whatever is next to it.
     *
     * A real inserted newline, kept short by the style on it. The alternative — drawing the box
     * smaller than the lines it covers — has nothing to give: one line box begins exactly where the
     * last one ended, so any margin has to be a line.
     */
    private fun gap(anchor: Int, edits: MutableList<MdEdit>, styles: MutableList<MdStyleRange>) {
        edits += MdEdit(anchor, anchor, "\n")
        // Zero characters of source, which maps to exactly the newline just inserted: the start of
        // a style range falls before an insertion at its offset and the end falls after it.
        styles += MdStyleRange(anchor, anchor, MdStyle.BLOCK_GAP)
    }

    /** The transformed offset text inserted here should fall *after* — an opening edge. */
    private fun opens(edits: List<MdEdit>, offset: Int) =
        transformedOffset(edits, offset, includeInsertionAt = false)

    /** The transformed offset text inserted here should fall *before* — a closing edge. */
    private fun closes(edits: List<MdEdit>, offset: Int) =
        transformedOffset(edits, offset, includeInsertionAt = true)

    /**
     * A style range starts *before* any padding inserted at its first offset and ends *after* any
     * inserted at its last, so a cell's padding is styled along with the cell.
     */
    private fun MdStyleRange.mapped(edits: List<MdEdit>) =
        MdStyleRange(opens(edits, start), closes(edits, end), style)

    private fun MdDecoration.mapped(edits: List<MdEdit>): MdDecoration = when (this) {
        is MdCodeBlock -> copy(
            // Starts close *after* whatever was inserted at them: a block's own first line is what
            // its box is measured from, not the blank line inserted to hold it off the line above.
            start = closes(edits, start),
            end = closes(edits, end),
            headerStart = opens(edits, headerStart),
            headerEnd = closes(edits, headerEnd),
        )

        is MdTable -> copy(
            start = closes(edits, start),
            end = closes(edits, end),
            headerEnd = closes(edits, headerEnd),
            rows = rows.map { closes(edits, it.first)..closes(edits, it.last) },
            // A cell's padding is inserted at the pipe that follows it, so a stop that opened before
            // insertions would name the padding rather than the space the rule is drawn through.
            columnStops = columnStops.map { closes(edits, it) },
        )

        is MdRule -> MdRule(closes(edits, offset))

        is MdQuote -> copy(start = closes(edits, start), end = closes(edits, end))

        // `sourceMark` is deliberately left alone: it names a character in the document, not on the
        // screen, and mapping it would point the toggle at whatever the renderer put there instead.
        is MdTask -> copy(offset = closes(edits, offset))
    }

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
     * Only the backticks are hidden. Whatever language word follows them is kept and shown as the
     * block's header, so the label the reader sees *is* the language in the source and editing one
     * edits the other. A fence with no language leaves an empty header line rather than no line at
     * all: the caret can rest there, and typing a word into it is how the language gets set.
     *
     * Nothing is padded. The block's rectangle is drawn from [MdCodeBlock], so it does not have to
     * be spelled out in trailing spaces — which is what used to force every line in the block to the
     * same font size and the same upright face.
     */
    private fun planFence(
        source: String,
        lines: Lines,
        open: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        decorations: MutableList<MdDecoration>,
    ): Int {
        val language = MarkdownParser.FENCE.matchEntire(lines.text(open))!!.groupValues[1]

        var close = open + 1
        while (close < lines.count && MarkdownParser.FENCE.matchEntire(lines.text(close)) == null) close++
        val lastBody = minOf(close, lines.count) - 1
        val hasBody = lastBody >= open + 1

        if (open > 0) gap(lines.start(open), edits, styles)

        val languageStart = if (language.isEmpty()) {
            lines.end(open)
        } else {
            lines.end(open) - lines.text(open).substringAfter("```").trimStart().length
        }

        // The backticks are replaced by the indent rather than deleted, so the header sits over the
        // code rather than against the box's left edge — and so that a fence with no language still
        // has somewhere for the caret to be.
        edits += MdEdit(lines.start(open), languageStart, INDENT)
        if (language.isNotEmpty()) {
            val languageEnd = languageStart + language.length
            // Trailing whitespace after the word goes too, or the header chip is drawn round a
            // word plus however many spaces happened to follow it.
            if (languageEnd < lines.end(open)) edits += MdEdit(languageEnd, lines.end(open), "")
            styles += MdStyleRange(languageStart, languageEnd, MdStyle.FENCE_HEADER)
        }

        leading(lines, open, styles, MdStyle.LEADING_TIGHT)
        for (k in open + 1..lastBody) {
            edits += MdEdit(lines.start(k), lines.start(k), INDENT)
            styles += MdStyleRange(lines.start(k), lines.end(k), MdStyle.FENCE)
            leading(lines, k, styles, MdStyle.LEADING_TIGHT)
        }

        // Tokens after the block styles, so their colours win where the two overlap.
        if (hasBody) highlight(source, language, lines.start(open + 1), lines.end(lastBody), styles)

        if (close < lines.count) {
            edits += MdEdit(lines.start(close), lines.endInclusive(close), "")
            if (lines.endInclusive(close) < lines.source.length) {
                gap(lines.endInclusive(close), edits, styles)
            }
        }

        decorations += MdCodeBlock(
            start = lines.start(open),
            end = if (hasBody) lines.end(lastBody) else lines.end(open),
            headerStart = languageStart,
            headerEnd = languageStart + language.length,
            sourceStart = if (hasBody) lines.start(open + 1) else lines.end(open),
            sourceEnd = if (hasBody) lines.end(lastBody) else lines.end(open),
        )
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
            leading(lines, k, styles, MdStyle.LEADING)
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
     * Cells are still padded to a common width — nothing but monospace padding can make columns line
     * up, because it is the *text* that has to line up. What is gone is every character pretending
     * to be a border: each `|` becomes a single space, one-for-one so that every offset inside the
     * table survives, and the delimiter row is hidden outright. Real lines are drawn down the middle
     * of those spaces and under the header, from [MdTable].
     *
     * The cost is that alignment colons are unreachable in the formatted view. They are pure
     * formatting, they are still in the source, and source mode still shows them.
     */
    private fun planTable(
        source: String,
        lines: Lines,
        first: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        decorations: MutableList<MdDecoration>,
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
        for (column in widths.indices) widths[column] = minOf(widths[column] + 2, MAX_CELL_WIDTH)

        if (first > 0) gap(lines.start(first), edits, styles)

        val rowRanges = ArrayList<IntRange>(rows.size - 1)
        val stops = ArrayList<Int>()

        for ((index, row) in rows.withIndex()) {
            val k = first + index
            if (index == 1) {
                edits += MdEdit(lines.start(k), lines.endInclusive(k), "")
                continue
            }

            styles += MdStyleRange(
                lines.start(k), lines.end(k),
                if (index == 0) MdStyle.TABLE_HEADER else MdStyle.TABLE,
            )
            rowRanges += lines.start(k)..lines.end(k)

            var pipe = lines.start(k)
            var column = 0
            while (pipe < lines.end(k)) {
                if (source[pipe] == '|') {
                    edits += MdEdit(pipe, pipe + 1, " ")
                    // The header's pipes are the column boundaries for the whole table: every row
                    // is padded to the same widths, so they all fall at the same place.
                    if (index == 0) stops += pipe
                }
                if (column < row.size && pipe == row[column].first) {
                    val cell = row[column]
                    padCell(source, cell, widths.getOrElse(column) { trimmedLength(source, cell) + 2 }, edits)
                    pipe = cell.last + 1
                    column++
                    continue
                }
                pipe++
            }
        }

        if (lines.endInclusive(last) < lines.source.length) {
            gap(lines.endInclusive(last), edits, styles)
        }

        decorations += MdTable(
            start = lines.start(first),
            // The last *visible* row, which is not always the last line: a table someone has only
            // half typed is a header and a delimiter, and the delimiter is hidden. Measured to the
            // line, the box would be drawn a row taller than the table it contains.
            end = rowRanges.last().last,
            headerEnd = lines.end(first),
            rows = rowRanges,
            columnStops = stops,
        )
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
     * Rewrites one cell's whitespace so it occupies exactly [target] characters: one leading space,
     * the content, then whatever it takes to reach the width.
     *
     * Both edges, not just the trailing one. Trimming only the tail cannot always reach the target —
     * a cell written `|    a|` has no trailing spaces to give back — and one row that misses the
     * width by a character is a column of drawn borders that no longer lines up with its text.
     */
    private fun padCell(source: String, cell: IntRange, target: Int, edits: MutableList<MdEdit>) {
        val from = cell.first
        val to = cell.last + 1

        var contentStart = from
        while (contentStart < to && source[contentStart] == ' ') contentStart++
        if (contentStart == to) {
            // A blank cell: one run of spaces, replaced wholesale.
            if (to - from != target) edits += MdEdit(from, to, pad(target))
            return
        }
        var contentEnd = to
        while (contentEnd > contentStart && source[contentEnd - 1] == ' ') contentEnd--

        if (contentStart - from != 1) edits += MdEdit(from, contentStart, " ")
        val trailing = (target - 1 - (contentEnd - contentStart)).coerceAtLeast(0)
        if (to - contentEnd != trailing) edits += MdEdit(contentEnd, to, pad(trailing))
    }

    private fun trimmedLength(source: String, cell: IntRange): Int =
        source.substring(cell.first, cell.last + 1).trim().length

    private fun pad(n: Int): String = if (n <= 0) "" else " ".repeat(n)

    // ---- Block level -------------------------------------------------------------------------

    private fun scanLine(
        lines: Lines,
        k: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        decorations: MutableList<MdDecoration>,
    ) {
        val text = lines.source
        val start = lines.start(k)
        val end = lines.end(k)
        val line = lines.text(k)
        leading(lines, k, styles, MdStyle.LEADING)
        if (line.isBlank()) return

        // The line is emptied rather than filled with dashes: a rule is drawn across the whole page
        // from [MdRule], and no run of characters reaches both margins.
        if (MarkdownParser.RULE.matches(line)) {
            edits += MdEdit(start, end, "")
            decorations += MdRule(start)
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
            // The whole marker becomes blank space, and a real checkbox is put over it. A glyph
            // would be the easy answer and cannot be ticked: the user asked for a control.
            edits += MdEdit(markerStart, textStart, TASK_BLANK)
            styles += MdStyleRange(markerStart, textStart, MdStyle.TASK_BOX)
            decorations += MdTask(markerStart, done, start + match.groups[2]!!.range.first)
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
            // The marker becomes the indent that clears the bar drawn beside it. A `ParagraphStyle`
            // would be the tidier way to say this and cannot be used: Compose lays a paragraph out
            // as its own block of text, and one whose range ends on a newline gets an extra empty
            // line at the bottom of it. See [INDENT].
            edits += MdEdit(start, textStart, QUOTE_INDENT)
            styles += MdStyleRange(textStart, end, MdStyle.QUOTE)
            openQuote(lines, k, decorations)
            scanInline(text, textStart, end, edits, styles)
            return
        }

        scanInline(text, start, end, edits, styles)
    }

    /**
     * Extends the quote above this line, or starts a new one.
     *
     * A quotation is a block even though it is parsed a line at a time, and three `>` lines want one
     * continuous bar beside them, not three stacked ones with seams between.
     */
    private fun openQuote(lines: Lines, k: Int, decorations: MutableList<MdDecoration>) {
        val bar = decorations.lastOrNull()
        if (bar is MdQuote && bar.end + 1 == lines.start(k)) {
            decorations[decorations.lastIndex] = bar.copy(end = lines.end(k))
            return
        }
        decorations += MdQuote(lines.start(k), lines.end(k))
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
