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
     * The selection is first cut into the *prose* it covers — one span per line, each starting after
     * whatever block markers that line carries. Inline syntax cannot cross a line break and cannot
     * contain a bullet: dragging over two list items and pressing strikethrough used to produce
     * `- ~~alpha\n- beta~~`, which is not a strikethrough at all but two literal pairs of tildes on
     * screen, and dragging over a single whole item produced `~~- alpha~~`, which silently stopped
     * being a list item. Wrapping each line's prose on its own is what makes the button mean the
     * same thing inside a list as it does in a paragraph.
     */
    fun toggleWrap(text: String, start: Int, end: Int, marker: String): Result {
        val spans = proseSpans(text, start, end)
        if (spans.size == 1) {
            val span = spans.single()
            return wrapSpan(text, span.from, span.to, marker)
        }

        // Already on everywhere means the button is being pressed to turn it off; anything less is
        // a press to turn it on, and the lines that already have it are left alone.
        val on = spans.all { encloses(text, it.from, it.to, marker) }
        var out = text
        for (span in spans.reversed()) {
            if (encloses(text, span.from, span.to, marker) != on) continue
            out = wrapSpan(out, span.from, span.to, marker).text
        }
        // The selection is put back over the whole prose of every line it covered rather than over
        // the words alone. Anything narrower would leave one end inside a pair of markers and the
        // other outside it, and the next press would read that as "not wrapped" and wrap it again.
        val lines = proseSpans(out, spans.first().from, spans.last().to + (out.length - text.length))
        return Result(
            text = out,
            selectionStart = lines.first().from.coerceIn(0, out.length),
            selectionEnd = lines.last().to.coerceIn(0, out.length),
        )
    }

    /**
     * The one-line case: toggle [marker] around exactly `[from, to)`.
     *
     * Works on the **length of the marker run** around the span rather than on whether the exact
     * marker string is there, and that is what lets styles stack. In Markdown one asterisk is
     * italic and two are bold, so `***word***` is both — three asterisks is not "a bold marker with
     * a stray asterisk", it is one run carrying two styles. Matching the literal string instead made
     * the italic button read the `**` of an already-bold word as its own marker and *remove* it, so
     * bolding then italicising gave a word that was only italic. Each button now toggles its own
     * width in and out of the run and leaves the rest of it alone.
     */
    private fun wrapSpan(text: String, from: Int, to: Int, marker: String): Result {
        val width = marker.length
        val symbol = marker[0]

        // Markers wrapped inside the selection rather than outside it — what a user who selected
        // `**word**` by hand has — are normalised to the outside case first.
        val inside = minOf(
            runForward(text, from, symbol, to),
            runBackward(text, to, symbol, from),
        )
        // Strictly wider than the markers it is peeling off, or there is nothing between them to
        // peel: a selection of exactly `**` used to recurse onto a range that ran backwards.
        if (to - from > inside * 2 && inside >= width) {
            return wrapSpan(text, from + inside, to - inside, marker)
                .let { Result(it.text, it.selectionStart - inside, it.selectionEnd + inside) }
        }

        if (isWrapped(text, from, to, marker)) {
            val out = text.removeRange(to, to + width).removeRange(from - width, from)
            return Result(out, from - width, to - width)
        }

        val selected = text.substring(from, to)
        val out = text.replaceRange(from, to, "$marker$selected$marker")
        // With nothing selected the caret lands between the markers, ready to type.
        return Result(out, from + width, from + width + selected.length)
    }

    /**
     * Whether `[from, to)` carries [marker], with the markers on either side of it or within it.
     *
     * The question the multi-line case asks, because a span there is a whole line of prose and a
     * whole line of prose *contains* its own markers. [isWrapped] answers the narrower question
     * [wrapSpan] needs — markers strictly outside — and cannot be widened to this without making its
     * unwrap step reach for characters that are inside the span rather than beside it.
     */
    private fun encloses(text: String, from: Int, to: Int, marker: String): Boolean {
        if (isWrapped(text, from, to, marker)) return true
        val symbol = marker[0]
        val inside = minOf(
            runForward(text, from, symbol, to),
            runBackward(text, to, symbol, from),
        )
        return if (marker.length == 1) inside % 2 == 1 else inside >= marker.length
    }

    /** Whether `[from, to)` is already sitting inside a run of [marker] wide enough to count. */
    private fun isWrapped(text: String, from: Int, to: Int, marker: String): Boolean {
        val symbol = marker[0]
        val run = minOf(
            runBackward(text, from, symbol, 0),
            runForward(text, to, symbol, text.length),
        )
        // A run of 3 is bold *and* italic, so italic is on when the run is odd and bold when it is
        // at least two. Anything wider than one style's own width belongs to another button.
        return if (marker.length == 1) run % 2 == 1 else run >= marker.length
    }

    /**
     * The prose `[start, end)` covers, one span per line, each clear of that line's block markers.
     *
     * Never empty: a caret with nothing selected still has to produce one span, so that pressing
     * bold on a bare caret leaves it between two fresh markers ready to type into.
     */
    private fun proseSpans(text: String, start: Int, end: Int): List<Span> {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)

        val spans = ArrayList<Span>()
        var lineStart = lineStartAt(text, from)
        while (lineStart <= to) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val body = lineStart + prefixOf(text.substring(lineStart, lineEnd)).length
            val spanFrom = maxOf(from, body)
            val spanTo = minOf(to, lineEnd)
            if (spanFrom < spanTo) spans += Span(spanFrom, spanTo)
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        if (spans.isNotEmpty()) return spans

        // Nothing but markers and line breaks was covered — a bare caret, most often. It belongs on
        // its own line's prose, so the markers land after the bullet rather than in front of it.
        val lineEnd = text.indexOf('\n', from).let { if (it < 0) text.length else it }
        val body = lineStartAt(text, from)
            .let { it + prefixOf(text.substring(it, lineEnd)).length }
        val at = from.coerceIn(minOf(body, lineEnd), lineEnd)
        return listOf(Span(at, at))
    }

    /** Half-open `[from, to)`, because an empty one at a caret is a span this has to be able to name. */
    private data class Span(val from: Int, val to: Int)

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

        val lineStart = lineStartAt(text, from)
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
        val lineStart = lineStartAt(text, caret)
        val out = text.replaceRange(lineStart, lineStart, prefix)
        return Result(out, caret + prefix.length, caret + prefix.length)
    }

    // ---- Block style ------------------------------------------------------------------------

    /**
     * What a whole line is, as far as the formatting bar is concerned.
     *
     * These are **two** menus rather than one, and which one a style belongs to is [axis]. A line's
     * paragraph style and its list marker are independent things — `- # Alpha` is a bulleted
     * heading, which is what CommonMark says it is and what every other Markdown reader draws —
     * so picking Heading 2 on a list item has to keep the bullet, and pressing the bullet button on
     * a heading has to keep the heading. Treating all of these as one menu is what used to make
     * either choice silently throw the other away.
     */
    enum class BlockStyle(val prefix: String, val axis: Axis) {
        PARAGRAPH("", Axis.PARAGRAPH),
        H1("# ", Axis.PARAGRAPH),
        H2("## ", Axis.PARAGRAPH),
        H3("### ", Axis.PARAGRAPH),
        H4("#### ", Axis.PARAGRAPH),
        H5("##### ", Axis.PARAGRAPH),
        H6("###### ", Axis.PARAGRAPH),
        QUOTE("> ", Axis.PARAGRAPH),
        BULLET("- ", Axis.LIST),
        TASK("- [ ] ", Axis.LIST),
        ORDERED("1. ", Axis.LIST);

        /** Which of the two menus this style is an item of. */
        enum class Axis { PARAGRAPH, LIST }

        internal val headingLevel: Int get() = prefix.count { it == '#' }
    }

    /** One indent step of a nested list: two spaces, which is the depth the parser counts in. */
    const val INDENT = "  "

    /**
     * Sets [style] on every selected line, leaving the other menu's choice alone.
     *
     * A heading keeps whatever list marker the line had; a list marker keeps whatever heading it
     * had. Only "Body" clears both, because that is the one item that means "no markup here".
     */
    fun setBlockStyle(text: String, start: Int, end: Int, style: BlockStyle): Result =
        editPrefixes(text, start, end) { prefix, index ->
            when (style) {
                // The one item that empties the line outright, which is what people reach for it to
                // do — the list buttons are how a list alone is taken off.
                BlockStyle.PARAGRAPH -> LinePrefix(prefix.indent, "", "", "")
                BlockStyle.QUOTE -> prefix.copy(quote = "> ", heading = "")
                // A numbered list across several lines has to actually count, or every line reads
                // "1." once the source is shown.
                BlockStyle.ORDERED -> prefix.copy(list = "${index + 1}. ")
                BlockStyle.BULLET, BlockStyle.TASK -> prefix.copy(list = style.prefix)
                else -> prefix.copy(heading = style.prefix, quote = "")
            }
        }

    /** Takes [style] off every selected line, leaving the other menu's choice alone. */
    private fun clearBlockStyle(text: String, start: Int, end: Int, style: BlockStyle): Result =
        editPrefixes(text, start, end) { prefix, _ ->
            when (style.axis) {
                BlockStyle.Axis.LIST -> prefix.copy(list = "")
                BlockStyle.Axis.PARAGRAPH -> prefix.copy(heading = "", quote = "")
            }
        }

    /**
     * The most specific thing [text]'s line at [offset] is, for the bar's label.
     *
     * A bulleted heading answers "heading": the heading is the part that changes how the line
     * *reads*, and the bullet button beside the label is already lit to say the rest.
     */
    fun blockStyleAt(text: String, offset: Int): BlockStyle =
        paragraphStyleAt(text, offset).takeIf { it != BlockStyle.PARAGRAPH }
            ?: listStyleAt(text, offset)
            ?: BlockStyle.PARAGRAPH

    /** What the paragraph menu should show as checked: a heading, a quote, or plain body text. */
    fun paragraphStyleAt(text: String, offset: Int): BlockStyle {
        val prefix = prefixOf(lineAt(text, offset))
        return when {
            prefix.heading.isNotEmpty() ->
                BlockStyle.entries.first { it.axis == BlockStyle.Axis.PARAGRAPH && it.headingLevel == prefix.headingLevel }

            prefix.quote.isNotEmpty() -> BlockStyle.QUOTE
            else -> BlockStyle.PARAGRAPH
        }
    }

    /** Which list button should be lit for [text]'s line at [offset], or null for no list at all. */
    fun listStyleAt(text: String, offset: Int): BlockStyle? = listStyleOf(prefixOf(lineAt(text, offset)).list)

    /**
     * Toggles [style] on the selected lines: applies it, or takes it back off when every line
     * already has it.
     */
    fun toggleBlockStyle(text: String, start: Int, end: Int, style: BlockStyle): Result {
        val allMatch = linesIn(text, start, end).all { prefixOf(it).has(style) }
        return if (allMatch) clearBlockStyle(text, start, end, style) else setBlockStyle(text, start, end, style)
    }

    // ---- Indentation ---------------------------------------------------------------------------

    /** Pushes every selected list item one level deeper. Lines that are not list items are left. */
    fun indentList(text: String, start: Int, end: Int): Result =
        editPrefixes(text, start, end) { prefix, _ ->
            if (prefix.list.isEmpty()) prefix else prefix.copy(indent = prefix.indent + INDENT)
        }

    /** Pulls every selected list item one level back out, and takes no marker off doing it. */
    fun outdentList(text: String, start: Int, end: Int): Result =
        editPrefixes(text, start, end) { prefix, _ ->
            if (prefix.list.isEmpty()) prefix else prefix.copy(indent = prefix.indent.dropIndent())
        }

    /** Whether anything in the selection is a list item at all, so the buttons can be disabled. */
    fun canIndent(text: String, start: Int, end: Int): Boolean =
        linesIn(text, start, end).any { prefixOf(it).list.isNotEmpty() }

    /** Whether anything in the selection is a list item that is currently indented. */
    fun canOutdent(text: String, start: Int, end: Int): Boolean =
        linesIn(text, start, end).any { prefixOf(it).let { p -> p.list.isNotEmpty() && p.indent.isNotEmpty() } }

    /**
     * Where the list marker on the line holding [offset] ends, or null when there is not one.
     *
     * This is the position a bullet's own keys act at: a space typed here indents the item rather
     * than pushing its first word along, and a backspace pulls it back out rather than eating a
     * marker the formatted view is not even showing.
     */
    fun listMarkerEnd(text: String, offset: Int): Int? {
        val at = offset.coerceIn(0, text.length)
        val lineStart = lineStartAt(text, at)
        val prefix = prefixOf(lineAt(text, at))
        return if (prefix.list.isEmpty()) null else lineStart + prefix.length
    }

    /**
     * What a backspace pressed at the end of a list marker should leave behind.
     *
     * One indent level at a time, and only once the item is back at the margin does the marker
     * itself go — which is the order every other editor unwinds a list in, and the only one where
     * a single key both un-nests and un-lists without ever doing both at once.
     */
    fun unindentOrUnlist(text: String, offset: Int): Result =
        editPrefixes(text, offset, offset) { prefix, _ ->
            when {
                prefix.list.isEmpty() -> prefix
                prefix.indent.isNotEmpty() -> prefix.copy(indent = prefix.indent.dropIndent())
                else -> prefix.copy(list = "")
            }
        }

    // ---- Line prefixes -------------------------------------------------------------------------

    /**
     * The block markers a line opens with, kept apart rather than lumped together.
     *
     * Markdown spells them in this order — indent, quote, list marker, heading — and a line may
     * carry any combination of them. Parsing them into separate fields is what lets the bar change
     * one without disturbing the others.
     */
    internal data class LinePrefix(
        val indent: String,
        val quote: String,
        val list: String,
        val heading: String,
    ) {
        val length: Int get() = indent.length + quote.length + list.length + heading.length
        val headingLevel: Int get() = heading.count { it == '#' }

        override fun toString(): String = indent + quote + list + heading

        fun has(style: BlockStyle): Boolean = when (style) {
            BlockStyle.PARAGRAPH -> heading.isEmpty() && quote.isEmpty() && list.isEmpty()
            BlockStyle.QUOTE -> quote.isNotEmpty()
            BlockStyle.BULLET, BlockStyle.TASK, BlockStyle.ORDERED -> listStyleOf(list) == style
            else -> heading.isNotEmpty() && headingLevel == style.headingLevel
        }
    }

    internal fun prefixOf(line: String): LinePrefix {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        var rest = line.substring(indent.length)
        val quote = QUOTE_PREFIX.find(rest)?.value.orEmpty()
        rest = rest.substring(quote.length)
        // Tasks before bullets: a task marker is a bullet with a box after it, so the shorter
        // pattern would match first and leave the box behind as prose.
        val list = (TASK_PREFIX.find(rest) ?: BULLET_PREFIX.find(rest) ?: ORDERED_PREFIX.find(rest))
            ?.value.orEmpty()
        rest = rest.substring(list.length)
        return LinePrefix(indent, quote, list, HEADING_PREFIX.find(rest)?.value.orEmpty())
    }

    private fun listStyleOf(marker: String): BlockStyle? = when {
        marker.isEmpty() -> null
        TASK_PREFIX.matches(marker) -> BlockStyle.TASK
        ORDERED_PREFIX.matches(marker) -> BlockStyle.ORDERED
        else -> BlockStyle.BULLET
    }

    private fun String.dropIndent(): String = when {
        endsWith('\t') -> dropLast(1)
        else -> dropLast(minOf(INDENT.length, length))
    }

    /**
     * Rewrites the prefix of every line the selection touches.
     *
     * The single place any block-level button reaches the document through, so "what happens to the
     * caret" is decided once. Both ends move by their own line's delta, which keeps a caret among
     * the words rather than throwing it to the start of the line.
     */
    private fun editPrefixes(
        text: String,
        start: Int,
        end: Int,
        transform: (LinePrefix, Int) -> LinePrefix,
    ): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val lineStart = lineStartAt(text, from)
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }

        val region = text.substring(lineStart, lineEnd)
        val updated = region.split('\n').mapIndexed { index, line ->
            val prefix = prefixOf(line)
            transform(prefix, index).toString() + line.substring(prefix.length)
        }.joinToString("\n")

        val out = text.replaceRange(lineStart, lineEnd, updated)
        val firstDelta = updated.substringBefore('\n').length - region.substringBefore('\n').length
        return Result(
            text = out,
            selectionStart = (from + firstDelta).coerceIn(lineStart, out.length),
            selectionEnd = (to + updated.length - region.length).coerceIn(lineStart, out.length),
        )
    }

    /** The whole lines `[start, end)` reaches into. */
    private fun linesIn(text: String, start: Int, end: Int): List<String> {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val lineStart = lineStartAt(text, from)
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd).split('\n')
    }

    private fun lineAt(text: String, offset: Int): String {
        val at = offset.coerceIn(0, text.length)
        val lineStart = lineStartAt(text, at)
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd)
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

    /**
     * A table, with a blank line above it where one is needed to keep it a table of its own.
     *
     * Pressing the button under a table used to give a taller version of that table: the rows ran
     * straight on, so the new header became two more rows of the old one — the one place in this
     * bar where a block cannot simply be written down where the caret is. See
     * [MarkdownStructure.underTable].
     */
    fun insertTable(text: String, start: Int): Result = insertBlock(
        text = text,
        start = start,
        snippet = "| Column | Column |\n| --- | --- |\n|  |  |\n",
        clear = MarkdownStructure.underTable(text, start),
    )

    /** Puts [snippet] on a line of its own, leaving the caret after it. */
    private fun insertBlock(
        text: String,
        start: Int,
        snippet: String,
        /** Whether to leave a blank line between the snippet and whatever is above it. */
        clear: Boolean = false,
    ): Result {
        val caret = start.coerceIn(0, text.length)
        val lead = if (caret == 0 || text[caret - 1] == '\n') "" else "\n"
        val blank = if (clear) "\n" else ""
        val out = text.replaceRange(caret, caret, lead + blank + snippet)
        val end = caret + lead.length + blank.length + snippet.length
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
    /**
     * Which inline markers an *armed* [marker] stands for, by the rule [activeInlineMarkers] reads
     * the document by.
     *
     * A style carried onto a bare caret is read off the parse, and the parse of `***word***` is one
     * span three asterisks wide — so what comes back armed is `***`, which is neither of the two
     * markers the bold and italic buttons test for. Both went dark the moment a space was typed at
     * the end of a bold-italic word, and the user was told their formatting had been dropped by a
     * bar that was merely spelling it differently. Counted as a run, as the document is, `***` is
     * bold *and* italic, which is what it renders as and what it says here.
     */
    fun inlineMarkersOf(marker: String): Set<String> {
        val symbol = marker.firstOrNull() ?: return emptySet()
        // Only a repeated symbol is a run to be counted. Anything else is its own marker already.
        if (marker.any { it != symbol }) return setOf(marker)
        return INLINE_MARKERS.filterTo(HashSet()) { confers(marker, it) }
    }

    /**
     * Whether a run written as [conferred] is wearing the style [armed] stands for.
     *
     * Counted as runs, never by length alone: a longer run is not every shorter one. `**` is bold
     * and nothing else, so an odd number of asterisks is what it takes to be italic and two of them
     * are not one twice over. Only `***` is two runs at once — bold and italic together — and
     * either button answers it.
     *
     * The rule the whole family shares. [inlineMarkersOf] reads it to light the bar's buttons and
     * [cancels] reads it to decide whether a press means "and this as well" or "no more of this",
     * and the two would be answering the same question differently if either had its own copy: a
     * bar showing bold and italic both lit while the italic press was quietly ending the bold.
     */
    private fun confers(conferred: String, armed: String): Boolean {
        val symbol = conferred.firstOrNull() ?: return false
        if (armed.isEmpty() || conferred.any { it != symbol } || armed.any { it != symbol }) {
            return conferred == armed
        }
        return if (armed.length == 1) conferred.length % 2 == 1 else conferred.length >= armed.length
    }

    /**
     * [armed] with [marker] turned on if it was off and off if it was on.
     *
     * Read through [inlineMarkersOf] on the way in, so a press lands on what the button was showing:
     * bold pressed while `***` is armed leaves italic, rather than arming a second `**` on top of a
     * run that already had one.
     *
     * What comes back is always one marker per button, never the combined run. A `***` is what the
     * *document* is written as, and only [carried] hands one over; arming one before there is any
     * text to style would send `***` to [toggleWrap] as a marker in its own right, which is not one
     * of the four it knows.
     */
    fun toggleArmedMarker(armed: List<MdPending>, marker: String): List<MdPending> {
        val lit = LinkedHashSet<String>()
        for (style in armed) if (style is MdPending.Wrap) lit += inlineMarkersOf(style.marker)
        if (!lit.remove(marker)) lit += marker
        return lit.map { MdPending.Wrap(it) } + armed.filterIsInstance<MdPending.Size>()
    }

    fun activeInlineMarkers(text: String, start: Int, end: Int): Set<String> {
        val spans = proseSpans(text, start, end)
        // Lit only when every line of the selection has it, matching what pressing the button would
        // then do: a selection where one item is struck through and one is not is not "struck".
        if (spans.size > 1) {
            return INLINE_MARKERS.filterTo(HashSet()) { marker ->
                spans.all { encloses(text, it.from, it.to, marker) }
            }
        }
        val from = spans.single().from
        val to = spans.single().to
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

    /**
     * Puts the styles armed at a bare caret round `[start, end)` — the text just typed there.
     *
     * Three things happen here, in this order, and each of them is a rule about where markers may
     * go rather than a special case:
     *
     * 1. **Markers never touch whitespace.** The range is narrowed to its non-blank core first.
     *    `** is**` is not emphasis to any Markdown reader, and a keyboard that puts a space in
     *    front of the next word — most of them do, after a suggestion — used to spend the armed
     *    style on that space and give `Apple** is**`. Nothing left to wrap means nothing to do,
     *    and [ApplyPendingStyles] keeps the style armed for the word that follows.
     * 2. **A style already in force is being turned off.** Pressing a lit button is a press to end
     *    the run, so what was typed goes *outside* it: the run is closed before the new text and
     *    opened again after it, by [MarkdownStructure.splitOpenRuns]. Wrapping it in place — which
     *    is what this used to do, always — is right in the middle of a run and wrong at either
     *    edge, where the empty pair it leaves behind stops being markup and becomes four asterisks
     *    on screen.
     * 3. **Anything else is wrapped where it stands.** The same calls the bar would have made had
     *    the user typed the words first and then selected them, run one after another over the
     *    range the last one handed back, so that arming bold and italic and then typing gives the
     *    run both. Adding a style *inside* a run belongs here and not in 2: `*a**x**b*` is tidier
     *    than a split, and says the same thing.
     */
    fun applyPending(text: String, start: Int, end: Int, styles: List<MdPending>): Result {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        val typed = text.substring(from, to)
        val body = typed.trim()
        if (body.isEmpty() || styles.isEmpty()) return Result(text, start, end)
        val lead = typed.take(typed.length - typed.trimStart().length)
        val trail = typed.takeLast(typed.length - typed.trimEnd().length)

        // The runs to close are the ones that were open before this keystroke, read off the
        // document as it stood then. Reading them off the document as it stands *now* is wrong the
        // moment the keystroke was a space: `**Apples **` no longer parses as bold at all, so the
        // run this is here to end would look as though it had never been open.
        val was = text.removeRange(from, to)
        val open = MarkdownStructure.stylesOpenAt(was, from)

        val middle = residual(open, styles)
        if (middle != null) {
            val inner = wrapEach(body, 0, body.length, middle)
            val split = MarkdownStructure.splitOpenRuns(
                source = was,
                start = from,
                end = from,
                middle = lead + inner.text + trail,
                middleCaret = lead.length + inner.selectionEnd,
            )
            if (split != null) {
                val out = was.replaceRange(split.start, split.end, split.text)
                return Result(out, split.caret - (inner.selectionEnd - inner.selectionStart), split.caret)
            }
        }
        return wrapOrExtend(text, from + lead.length, to - trail.length, styles)
    }

    /**
     * Rule 3, plus the one thing that is not a wrap: carrying on a run the caret has just left.
     *
     * A space at the end of a bold word is moved outside the run rather than left against its
     * closing marker, so the next word is typed at a caret that is no longer inside anything. The
     * user has not stopped writing in bold, though, so the closer comes off and goes on again after
     * the new word — one run of two words, rather than two runs of one.
     */
    private fun wrapOrExtend(text: String, start: Int, end: Int, styles: List<MdPending>): Result {
        var out = text
        var from = start
        var to = end
        for (style in styles) {
            val step = when {
                style is MdPending.Wrap -> extend(out, from, to, style.marker) ?: toggleWrap(out, from, to, style.marker)
                else -> setSize(out, from, to, (style as MdPending.Size).sp)
            }
            out = step.text
            from = step.selectionStart
            to = step.selectionEnd
        }
        return Result(out, from, to)
    }

    /** Puts `[from, to)` inside the [marker] run that ended just before it, or null if there is none. */
    private fun extend(text: String, from: Int, to: Int, marker: String): Result? {
        val span = MarkdownStructure.extendableRun(text, from, marker) ?: return null
        val width = span.closeEnd - span.closeStart
        val out = text.substring(0, span.closeStart) +
            text.substring(span.closeEnd, to) +
            marker +
            text.substring(to)
        return Result(out, from - width, to - width)
    }

    /** Rule 3: each style in turn, over the range the one before it handed back. */
    private fun wrapEach(text: String, start: Int, end: Int, styles: List<MdPending>): Result {
        var out = text
        var from = start
        var to = end
        for (style in styles) {
            val step = when (style) {
                is MdPending.Wrap -> toggleWrap(out, from, to, style.marker)
                is MdPending.Size -> setSize(out, from, to, style.sp)
            }
            out = step.text
            from = step.selectionStart
            to = step.selectionEnd
        }
        return Result(out, from, to)
    }

    /**
     * What the newly typed text should wear, or null when nothing was being turned off.
     *
     * [open] is what the text there already wears and [armed] what the user asked for; an armed
     * style that answers one of the open ones cancels it, and the rest of that one — if there is
     * any — is what the new text keeps. The rest matters because the renderer reads `***` as *one*
     * span conferring two styles, so turning bold off inside `***Apple***` has to leave the italic
     * behind rather than the whole thing.
     *
     * Null, meaning "nothing was cancelled", is the answer that sends the caller down the ordinary
     * wrapping path — including when the bar's buttons and the parse disagree about what is in
     * force. The failure mode is then a button that lied, never a document taken apart.
     */
    private fun residual(open: List<MdPending>, armed: List<MdPending>): List<MdPending>? {
        val spent = BooleanArray(armed.size)
        val middle = ArrayList<MdPending>()
        // Innermost first: an armed marker answers the nearest run that confers it.
        for (conferred in open.asReversed()) {
            var wears: MdPending? = conferred
            var again = true
            while (again) {
                again = false
                val worn = wears ?: break
                for (i in armed.indices) {
                    if (spent[i] || !cancels(worn, armed[i])) continue
                    spent[i] = true
                    wears = leftOf(worn, armed[i])
                    again = true
                    break
                }
            }
            wears?.let { middle += it }
        }
        if (spent.none { it }) return null
        middle.reverse()
        armed.filterIndexedTo(middle) { i, _ -> !spent[i] }
        return middle
    }

    /**
     * Whether pressing [armed] at a caret inside a run wearing [conferred] means "end this run".
     *
     * Through [confers], because only a run that is actually wearing the style being pressed has
     * anything to end. Asking merely whether the run were the longer of the two read italic pressed
     * inside a bold word as "stop the bold", and the words that followed came out italic and plain
     * — a style *added* to what was already there taking away what it was added to.
     */
    private fun cancels(conferred: MdPending, armed: MdPending): Boolean = when {
        conferred is MdPending.Wrap && armed is MdPending.Wrap -> confers(conferred.marker, armed.marker)

        // A size is a choice rather than a toggle, so any size answers any other — including
        // "body", which is the one that takes the tag off.
        else -> conferred is MdPending.Size && armed is MdPending.Size
    }

    /** What is left of [conferred] once [armed] has cancelled it, or null for nothing at all. */
    private fun leftOf(conferred: MdPending, armed: MdPending): MdPending? = when {
        armed is MdPending.Size -> armed.sp?.let { MdPending.Size(it) }
        conferred is MdPending.Wrap && armed is MdPending.Wrap ->
            conferred.marker.drop(armed.marker.length).takeIf { it.isNotEmpty() }?.let { MdPending.Wrap(it) }

        else -> null
    }

    // ---- Font size ------------------------------------------------------------------------

    /**
     * Sets, replaces or clears the size of `[start, end)`, as `[text]{size=N}`.
     *
     * The one styling control a sheet has and a `.md` note does not — see [MdStyle.SIZE].
     *
     * Line by line, through [proseSpans], for the reason every other block-aware action is: the
     * span is an *inline* construct and the renderer scans inline syntax one line at a time, so a
     * tag opened on one line and closed on the next is not a tag at all — it is four characters of
     * punctuation the reader can see. A selection across three paragraphs comes out as three spans,
     * each clear of its own bullet or heading marker.
     */
    fun setSize(text: String, start: Int, end: Int, sp: Int?): Result {
        val spans = proseSpans(text, start, end)
        if (spans.size == 1) return sizeSpan(text, spans.single().from, spans.single().to, sp)

        var out = text
        for (span in spans.reversed()) out = sizeSpan(out, span.from, span.to, sp).text
        // Reselected over the whole prose of every line covered, the same way [toggleWrap] does it:
        // an end left inside one of the tags would be read as a different span next time round.
        val lines = proseSpans(out, spans.first().from, spans.last().to + (out.length - text.length))
        return Result(
            text = out,
            selectionStart = lines.first().from.coerceIn(0, out.length),
            selectionEnd = lines.last().to.coerceIn(0, out.length),
        )
    }

    /**
     * One line's worth of [setSize].
     *
     * Three cases. A tag the selection is *inside of* is rewritten whole, rather than nested in a
     * second one: two sizes on one run of characters is a document with no answer, and the answer
     * people expect from pressing a size button twice is the second size. A selection that instead
     * *contains* tags — which is what selecting everything gives you — has them taken out of the
     * text before the new one goes round it, for the same reason. And a bare caret gets the empty
     * pair, so that what is typed next is the size asked for: the bargain [toggleWrap] makes for
     * bold.
     *
     * [sp] of null is the same three cases with nothing put back, so "Body" leaves no tag behind to
     * be puzzled over in source mode.
     */
    private fun sizeSpan(text: String, start: Int, end: Int, sp: Int?): Result {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)

        val existing = sizeSpanAt(text, from, to)
        if (existing != null) {
            val body = text.substring(existing.open, existing.close)
            val replacement = if (sp == null) body else "[$body]{size=$sp}"
            val out = text.substring(0, existing.start) + replacement + text.substring(existing.end)
            val shift = existing.start + (if (sp == null) 0 else 1)
            return Result(out, shift, shift + body.length)
        }

        val body = stripSizes(text.substring(from, to))
        val replacement = if (sp == null) body else "[$body]{size=$sp}"
        val out = text.substring(0, from) + replacement + text.substring(to)
        val shift = from + (if (sp == null) 0 else 1)
        return Result(out, shift, shift + body.length)
    }

    /** The size in force at [offset], or null where the text is at the document's own size. */
    fun sizeAt(text: String, offset: Int): Int? = sizeSpanAt(text, offset, offset)?.size

    /**
     * The size the *selection* is at, or null if it is at the document's own size or at several.
     *
     * What the button has to label itself with, and not the same question as [sizeAt]: selecting a
     * sized run selects its hidden tag along with it, so the caret-in-a-span test alone would report
     * "body" for the very text it is showing at 32 — and then set a second size around the first.
     */
    fun sizeIn(text: String, start: Int, end: Int): Int? {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        sizeSpanAt(text, from, to)?.let { return it.size }
        if (from == to) return null

        // Whole tags swallowed by the selection: one size, if they agree and there is nothing but
        // blank space between them. A selection half in and half out of a span has no one answer.
        val spans = sizeSpansIn(text, from, to)
        val size = spans.firstOrNull()?.size ?: return null
        if (spans.any { it.size != size }) return null
        var cursor = from
        for (span in spans) {
            if (text.substring(cursor, span.start).isNotBlank()) return null
            cursor = span.end
        }
        return if (text.substring(cursor, to).isBlank()) size else null
    }

    /**
     * The innermost `[…]{size=N}` that `[from, to)` sits inside.
     *
     * Searched from the start of the text rather than by scanning outwards from the caret, because
     * only a parse can tell a real span from a bracket somebody typed: `[a](b)` and `[a]` and an
     * unclosed `[` all begin identically, and the caret has no idea which of them it is sitting in.
     *
     * "Inside" is generous at the edges on purpose. The tag is hidden, so a user who drags across
     * the words they can see hands back a selection that reaches over the `[` and stops before the
     * `]{size=N}` — and selecting all of a box gives one that covers both. Every one of those means
     * the same span, so anything that stays within the tag and touches the text inside it counts.
     */
    private fun sizeSpanAt(text: String, from: Int, to: Int): SizeSpan? {
        var best: SizeSpan? = null
        forEachSizeSpan(text, 0, text.length) { span ->
            val within = from >= span.start && to <= span.end
            val touches = from <= span.close && to >= span.open
            // Innermost wins: a span inside another covers a shorter run, and it is the one whose
            // size the reader actually sees at that offset.
            if (within && touches && (best == null || span.close - span.open < best!!.close - best!!.open)) {
                best = span
            }
        }
        return best
    }

    /** The outermost tags lying wholly inside `[from, to)`, in the order they appear. */
    private fun sizeSpansIn(text: String, from: Int, to: Int): List<SizeSpan> {
        val found = ArrayList<SizeSpan>()
        var i = from
        while (i < to) {
            val span = if (text[i] == '[') sizeSpanStartingAt(text, i) else null
            if (span != null && span.end <= to) {
                found += span
                i = span.end
            } else {
                i++
            }
        }
        return found
    }

    /** [text] with every size tag in it removed and the words they wrapped kept. */
    private fun stripSizes(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val span = if (text[i] == '[') sizeSpanStartingAt(text, i) else null
            if (span != null) {
                // Recursive, so a nested size goes too: having selected the lot and asked for one
                // size, being given two is not an answer to that.
                out.append(stripSizes(text.substring(span.open, span.close)))
                i = span.end
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** Every size tag in `[from, to)`, nested ones included. */
    private inline fun forEachSizeSpan(text: String, from: Int, to: Int, action: (SizeSpan) -> Unit) {
        var i = from
        while (i < to) {
            if (text[i] == '[') sizeSpanStartingAt(text, i)?.let(action)
            i++
        }
    }

    /** `[body]{size=N}` beginning at [at], with brackets counted so nesting closes correctly. */
    private fun sizeSpanStartingAt(text: String, at: Int): SizeSpan? {
        var depth = 0
        var k = at
        while (k < text.length) {
            when (text[k]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val (size, end) = MarkdownRenderer.sizeSuffixAt(text, k + 1, text.length)
                            ?: return null
                        return SizeSpan(start = at, open = at + 1, close = k, end = end, size = size)
                    }
                }
            }
            k++
        }
        return null
    }

    /** `start` and `end` bracket the whole tag; `open` and `close` bracket the text inside it. */
    private data class SizeSpan(
        val start: Int,
        val open: Int,
        val close: Int,
        val end: Int,
        val size: Int,
    )

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

    /**
     * Where the line holding [offset] starts.
     *
     * Offset zero is its own case rather than a search: `lastIndexOf` takes its bound inclusively,
     * so a document that opens with a blank line answered "line 1 starts at 1" for a caret sitting
     * at 0 — a line start *past* the caret, which every caller then handed to `substring` as a
     * range that ran backwards.
     */
    private fun lineStartAt(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        return text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
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
