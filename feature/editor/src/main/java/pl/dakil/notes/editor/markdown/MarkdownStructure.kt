package pl.dakil.notes.editor.markdown

/**
 * The blocks whose shape is spelled in syntax the reader never sees.
 *
 * Everything else in a Markdown document survives ordinary editing: delete a character of a
 * heading and you get a shorter heading. Delete one backtick of a fence and the block stops being
 * a block — and in the formatted view the character that did it was invisible, so what the user
 * sees is a code block that fell apart under a keystroke they cannot account for.
 *
 * This is the model that keeps that from happening. It is deliberately plain Kotlin: every decision
 * about what a keystroke means is a pure function of the document text and one offset, so all of it
 * is answerable in a JUnit test rather than by tapping at a phone.
 */
enum class MdBlockKind { FENCE, MATH, TABLE, RULE }

/**
 * A block and the source it is spelled with.
 *
 * [end] takes in the newline that ends the last line, so removing `[start, end)` removes the block
 * *and* the line it stood on rather than leaving a blank one behind. [closed] says whether that
 * last line is a closing marker rather than content — a fence someone is still typing has none.
 */
data class MdBlockSpan(
    val kind: MdBlockKind,
    val start: Int,
    val end: Int,
    val firstLine: Int,
    val lastLine: Int,
    val closed: Boolean,
)

/** What a backspace should really do. */
sealed interface MdBackspace {
    /** Nothing special: let the field delete what it was going to. */
    data object Allow : MdBackspace

    /** Put it back. It would have eaten syntax that is not on screen to be eaten. */
    data object Refuse : MdBackspace

    /**
     * Delete `[start, end)` instead — the whole block the caret was standing under.
     *
     * A block is one object to the reader, so backspacing at the edge of one removes the object.
     * The alternative is what used to happen: one invisible character goes, the block silently
     * stops parsing, and its remains reflow into whatever came next.
     */
    data class Remove(val start: Int, val end: Int) : MdBackspace
}

/** Replace `[start, end)` with [text] and leave the caret at [caret]. */
data class MdEditAt(val start: Int, val end: Int, val text: String, val caret: Int)

/**
 * Where a caret is in a table: which table, which visible row, which column.
 *
 * Rows count only the ones on screen, so row 0 is the header and the hidden delimiter is not a row
 * at all. That is the numbering every action here takes, and the numbering the borders are drawn in.
 */
data class MdCell(val table: MdBlockSpan, val row: Int, val column: Int)

object MarkdownStructure {

    /** Every block in [source], in document order. */
    fun blocks(source: String): List<MdBlockSpan> = blocks(MarkdownRenderer.Lines(source))

    /** The table starting at [sourceStart], as a decoration reported it. */
    fun tableAt(source: String, sourceStart: Int): MdBlockSpan? =
        blocks(source).firstOrNull { it.kind == MdBlockKind.TABLE && it.start == sourceStart }

    /**
     * What should happen to a deletion that ends at [caret].
     *
     * The end rather than the caret it was pressed at, because the two are not always the same
     * offset. A caret resting on a run the formatted view hides maps back to the *start* of that
     * run, so backspacing there deletes forwards through the hidden characters — reaching, in the
     * one case that matters, exactly to the end of the closing fence. Judging by where the deletion
     * finished answers both that case and the ordinary one with the same question.
     *
     * Three of them, in order: is the character about to go a newline holding a block together, is
     * it the last thing between here and a block above, or is it markup inside one.
     */
    fun backspaceAt(source: String, caret: Int): MdBackspace {
        if (caret <= 0 || caret > source.length) return MdBackspace.Allow
        val lines = MarkdownRenderer.Lines(source)
        val blocks = blocks(lines)
        val at = caret - 1

        if (source[at] == '\n') {
            val above = lines.lineOf(at)
            val block = blocks.firstOrNull { above in it.firstLine..it.lastLine }
            if (block != null) {
                // Standing on the line below a block and pressing backspace takes the block. It is
                // the only gesture that can: everything that spells the block out is hidden.
                if (above == block.lastLine) return MdBackspace.Remove(block.start, caret)
                // A block with nothing in it has no content to protect, and refusing every key
                // pressed inside one left the user with a box they could not get rid of: the fence
                // is invisible, so there is nothing to select and nothing to delete by hand.
                if (isEmpty(lines, block)) return MdBackspace.Remove(block.start, block.end)
                // A newline *inside* a block is load-bearing — except between two lines of code,
                // where joining them is an ordinary edit to ordinary text.
                val body = block.kind == MdBlockKind.FENCE &&
                    above > block.firstLine &&
                    above + 1 <= if (block.closed) block.lastLine - 1 else block.lastLine
                return if (body) MdBackspace.Allow else MdBackspace.Refuse
            }
            // Pulling a block's opening line up into the paragraph above it dismantles the block
            // just as thoroughly, from the other end.
            if (blocks.any { it.firstLine == above + 1 }) return MdBackspace.Refuse
            return MdBackspace.Allow
        }

        val k = lines.lineOf(at)
        val block = blocks.firstOrNull { k in it.firstLine..it.lastLine } ?: return MdBackspace.Allow
        return when (block.kind) {
            // The rule has no characters on screen at all, so there is nothing here to trim: the
            // only edit that means anything is removing it.
            MdBlockKind.RULE -> MdBackspace.Remove(block.start, block.end)

            // The delimiter row is hidden, and a pipe reads as a drawn border rather than a
            // character. Cell text is the user's own and is left alone.
            MdBlockKind.TABLE ->
                if (k == block.firstLine + 1 || source[at] == '|') MdBackspace.Refuse else MdBackspace.Allow

            // Backspacing through a fence's language word has to stop when the word runs out,
            // rather than carrying on into the backticks behind it.
            MdBlockKind.FENCE ->
                if (k == block.firstLine && at < lines.start(k) + FENCE_MARKER) MdBackspace.Refuse
                else if (k > block.firstLine && MarkdownParser.FENCE.matchEntire(lines.text(k)) != null) MdBackspace.Refuse
                else MdBackspace.Allow

            // Both `$$` lines are hidden whole; the formula between them is text.
            MdBlockKind.MATH ->
                if (lines.text(k).trim() == "$$") MdBackspace.Refuse else MdBackspace.Allow
        }
    }

    /**
     * The row Enter should open when the caret is in a table, or null when it is not in one.
     *
     * A blank copy of the row it was pressed on — same pipes in the same places — so the table
     * stays a table however its author spaced it out, and the caret lands in the column it left.
     */
    fun rowBelow(source: String, caret: Int): MdEditAt? {
        if (caret !in 0..source.length) return null
        val lines = MarkdownRenderer.Lines(source)
        val k = lines.lineOf(caret)
        val table = blocks(lines).firstOrNull {
            it.kind == MdBlockKind.TABLE && k in it.firstLine..it.lastLine
        } ?: return null

        // Nobody stands in the delimiter row — it is not on screen — but a caret mapped onto it
        // belongs to the header above it.
        val row = if (k == table.firstLine + 1) table.firstLine else k
        val after = if (row == table.firstLine) table.firstLine + 1 else row
        val text = lines.text(row)
        val blank = blankRow(text)
        val at = lines.end(after)
        // Measured against the blank copy, which has the same pipes in the same places: what is
        // wanted is where the new cell's text starts, not where the old cell's text happened to be.
        val column = cellRange(blank, caret - lines.start(row)).first
        return MdEditAt(at, at, "\n$blank", caret = at + 1 + minOf(column, blank.length))
    }

    /**
     * Inserts a row into [table], [at] borders down from its top.
     *
     * Border 0 is the table's top edge, so inserting there puts the new row above the header and
     * makes it the header — which is what a `+` drawn on that edge looks like it should do.
     */
    fun addRow(source: String, table: MdBlockSpan, at: Int): MdEditAt {
        val lines = MarkdownRenderer.Lines(source)
        val rows = rowLines(table)
        val blank = blankRow(lines.text(table.firstLine))
        val cell = cellRange(blank, 0).first

        if (at <= 0) {
            val start = lines.start(table.firstLine)
            return MdEditAt(start, start, "$blank\n", caret = start + cell)
        }
        // Under the header means under the *dashes*: a row slipped between the two would be read as
        // the delimiter, and the table would stop being one.
        val above = rows[(at - 1).coerceAtMost(rows.lastIndex)]
        val end = lines.end(if (above == table.firstLine) table.firstLine + 1 else above)
        return MdEditAt(end, end, "\n$blank", caret = end + 1 + cell)
    }

    /**
     * Inserts a column into [table], [at] borders in from its left edge.
     *
     * Every row is rebuilt, because a column that is only in some of them is not a column. The
     * rebuild normalises the spacing between pipes as a side effect; the rendered table is padded
     * to its own widths either way, so what changes is only how the source reads.
     */
    fun addColumn(source: String, table: MdBlockSpan, at: Int): MdEditAt {
        val lines = MarkdownRenderer.Lines(source)
        val out = StringBuilder()
        var caret = 0

        for (k in table.firstLine..table.lastLine) {
            val cells = cellsOf(lines.text(k)).toMutableList()
            val index = at.coerceIn(0, cells.size)
            // The delimiter row carries dashes rather than content, and it has to gain a cell too
            // or the table is one column wider than it declares and stops parsing.
            cells.add(index, if (k == table.firstLine + 1) "---" else "")

            val line = StringBuilder("|")
            for ((column, cell) in cells.withIndex()) {
                if (column == index && k == table.firstLine) caret = out.length + line.length + 1
                line.append(' ').append(cell).append(if (cell.isEmpty()) "|" else " |")
            }
            out.append(line)
            if (k < table.lastLine) out.append('\n')
        }
        return MdEditAt(
            start = lines.start(table.firstLine),
            end = lines.end(table.lastLine),
            text = out.toString(),
            caret = lines.start(table.firstLine) + caret,
        )
    }

    /** The cell the caret at [at] is in, or null when it is not in a table. */
    fun cellAt(source: String, at: Int): MdCell? {
        if (at !in 0..source.length) return null
        val lines = MarkdownRenderer.Lines(source)
        val k = lines.lineOf(at)
        val table = blocks(lines).firstOrNull {
            it.kind == MdBlockKind.TABLE && k in it.firstLine..it.lastLine
        } ?: return null
        val row = rowLines(table).indexOf(k)
        if (row < 0) return null
        val text = lines.text(k)
        val column = text.take((at - lines.start(k)).coerceIn(0, text.length)).count { it == '|' }
        return MdCell(table, row, (column - 1).coerceAtLeast(0))
    }

    /**
     * Where a caret dropped at [at] should really sit, or null if it is already somewhere sensible.
     *
     * A cell is padded out with blanks so its column lines up, and a caret can be dropped into that
     * padding — which reads as a text field whose cursor is floating in the middle of nothing, and
     * types a character that then jumps to the left as the cell is measured again. Clamping the
     * caret to the cell's own text is what makes a cell behave like the input field it looks like.
     */
    fun cellCaret(source: String, at: Int): Int? {
        if (at !in 0..source.length) return null
        val lines = MarkdownRenderer.Lines(source)
        val k = lines.lineOf(at)
        val table = blocks(lines).firstOrNull {
            it.kind == MdBlockKind.TABLE && k in it.firstLine..it.lastLine
        } ?: return null
        // The delimiter row is not on screen, so a caret there is not one the user placed.
        if (k == table.firstLine + 1) return null

        val start = lines.start(k)
        val row = lines.text(k)
        val bounds = cellBounds(row, at - start)
        var from = bounds.first
        while (from < bounds.last && row[from] == ' ') from++
        var to = bounds.last
        while (to > from && row[to - 1] == ' ') to--
        // An empty cell has no text to sit in, so the caret goes where its text would start: one
        // space in from the border, exactly as an empty field's cursor sits.
        val target = if (from >= to) cellRange(row, at - start).first else (at - start).coerceIn(from, to)
        return (start + target).takeIf { it != at }
    }

    /**
     * Removes one visible row, or the whole table when it was the only one.
     *
     * The table is rebuilt from its remaining rows rather than having a line cut out of it, because
     * the first row is the header and the delimiter has to stay under whichever row that is. Cutting
     * the header line alone leaves the dashes on top, and a table whose first line is its delimiter
     * is not a table any more.
     */
    fun removeRow(source: String, table: MdBlockSpan, row: Int): MdEditAt {
        val lines = MarkdownRenderer.Lines(source)
        val rows = rowLines(table).toMutableList()
        if (row !in rows.indices || rows.size <= 1) return removeWhole(lines, table)
        rows.removeAt(row)

        val out = rows.mapIndexed { index, line ->
            if (index == 0) lines.text(line) + "\n" + lines.text(table.firstLine + 1) else lines.text(line)
        }
        return MdEditAt(
            start = lines.start(table.firstLine),
            end = lines.end(table.lastLine),
            text = out.joinToString("\n"),
            caret = lines.start(table.firstLine) + cellRange(out.first(), 0).first,
        )
    }

    /** Removes one column, or the whole table when it was the only one. */
    fun removeColumn(source: String, table: MdBlockSpan, column: Int): MdEditAt {
        val lines = MarkdownRenderer.Lines(source)
        if (cellsOf(lines.text(table.firstLine)).size <= 1) return removeWhole(lines, table)

        val out = StringBuilder()
        for (k in table.firstLine..table.lastLine) {
            val cells = cellsOf(lines.text(k)).toMutableList()
            if (column in cells.indices) cells.removeAt(column)
            out.append(rebuild(cells, dashes = k == table.firstLine + 1))
            if (k < table.lastLine) out.append('\n')
        }
        val start = lines.start(table.firstLine)
        return MdEditAt(start, lines.end(table.lastLine), out.toString(), start + 2)
    }

    /** Removes the table, and the line it stood on with it. */
    fun removeTable(source: String, table: MdBlockSpan): MdEditAt =
        removeWhole(MarkdownRenderer.Lines(source), table)

    // ---- Internals ---------------------------------------------------------------------------

    private fun removeWhole(lines: MarkdownRenderer.Lines, table: MdBlockSpan): MdEditAt =
        MdEditAt(table.start, table.end, "", table.start)

    /** A row written out from its cells: `| a | b |`, or the dashes that declare the columns. */
    private fun rebuild(cells: List<String>, dashes: Boolean): String =
        cells.joinToString(" | ", prefix = "| ", postfix = " |") { if (dashes) "---" else it }


    /** How many characters a fence marker is: the run of backticks the regex insists on. */
    private const val FENCE_MARKER = 3

    /**
     * Whether [block] holds nothing at all — no code, no formula, not even a language.
     *
     * Only fences and formulas can be empty in a way the reader would notice: a table always has its
     * header text and a rule is a rule. Asked so that a backspace inside an empty block can mean
     * "take this away" rather than being refused for the sake of syntax nobody can see.
     */
    private fun isEmpty(lines: MarkdownRenderer.Lines, block: MdBlockSpan): Boolean {
        if (block.kind != MdBlockKind.FENCE && block.kind != MdBlockKind.MATH) return false
        if (block.kind == MdBlockKind.FENCE &&
            MarkdownParser.FENCE.matchEntire(lines.text(block.firstLine))?.groupValues?.get(1)?.isNotEmpty() == true
        ) {
            return false
        }
        val lastBody = if (block.closed) block.lastLine - 1 else block.lastLine
        return (block.firstLine + 1..lastBody).all { lines.text(it).isBlank() }
    }

    private fun blocks(lines: MarkdownRenderer.Lines): List<MdBlockSpan> {
        val out = ArrayList<MdBlockSpan>()
        var k = 0
        while (k < lines.count) {
            val line = lines.text(k)
            when {
                MarkdownParser.FENCE.matchEntire(line) != null -> {
                    var close = k + 1
                    while (close < lines.count && MarkdownParser.FENCE.matchEntire(lines.text(close)) == null) close++
                    k = span(out, lines, MdBlockKind.FENCE, k, close)
                }

                line.trim() == "$$" -> {
                    var close = k + 1
                    while (close < lines.count && lines.text(close).trim() != "$$") close++
                    k = span(out, lines, MdBlockKind.MATH, k, close)
                }

                lines.startsTable(k) -> {
                    var last = k + 1
                    while (last + 1 < lines.count && lines.text(last + 1).contains('|')) last++
                    k = span(out, lines, MdBlockKind.TABLE, k, last)
                }

                MarkdownParser.RULE.matches(line) -> k = span(out, lines, MdBlockKind.RULE, k, k)

                else -> k++
            }
        }
        return out
    }

    /** Records a block running from [first] to [last], clamped, and returns the line after it. */
    private fun span(
        out: MutableList<MdBlockSpan>,
        lines: MarkdownRenderer.Lines,
        kind: MdBlockKind,
        first: Int,
        last: Int,
    ): Int {
        val end = minOf(last, lines.count - 1)
        out += MdBlockSpan(
            kind = kind,
            start = lines.start(first),
            end = lines.endInclusive(end),
            firstLine = first,
            lastLine = end,
            closed = last < lines.count,
        )
        return end + 1
    }

    /** The visible rows of a table, by line: the header, then everything past the delimiter. */
    private fun rowLines(table: MdBlockSpan): List<Int> =
        listOf(table.firstLine) + (table.firstLine + 2..table.lastLine)

    /** [row] with every character but its pipes turned to a space. */
    private fun blankRow(row: String): String =
        row.map { if (it == '|') '|' else ' ' }.joinToString("")

    /** The cells of a row, trimmed, without the outer pipes. */
    private fun cellsOf(row: String): List<String> {
        val trimmed = row.trim()
        val inner = trimmed.removePrefix("|").removeSuffix("|")
        return inner.split('|').map { it.trim() }
    }

    /**
     * Where the cell containing [offset] can be typed in.
     *
     * The start is past the opening pipe and the space that follows it — where a padded cell's text
     * begins — unless the cell is too narrow for that, in which case it is the cell's own end.
     */
    private fun cellRange(row: String, offset: Int): IntRange {
        val (open, close) = pipesAround(row, offset)
        return minOf(open + 2, close)..close
    }

    /** Everything between the pipes: the cell's text and whatever blanks pad it out. */
    private fun cellBounds(row: String, offset: Int): IntRange {
        val (open, close) = pipesAround(row, offset)
        return (open + 1).coerceAtMost(close)..close
    }

    private fun pipesAround(row: String, offset: Int): Pair<Int, Int> {
        // Offset zero is *outside* a row that opens with a pipe — to the left of the table's own
        // edge — and the cell meant by it is the first one.
        val at = if (offset <= 0 && row.startsWith("|")) 1 else offset.coerceIn(0, row.length)
        val open = row.lastIndexOf('|', (at - 1).coerceAtLeast(0)).let { if (at == 0) -1 else it }
        val close = row.indexOf('|', at).let { if (it < 0) row.length else it }
        return open to close
    }
}
