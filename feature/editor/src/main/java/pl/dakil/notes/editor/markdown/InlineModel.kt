package pl.dakil.notes.editor.markdown

/**
 * A line's prose as *what it looks like*, rather than as the characters that make it look that way.
 *
 * The formatting bar used to work by string surgery: find the markers around the selection, add a
 * pair or take a pair away. That is correct for a bare word and wrong for everything else, because
 * a marker only means something where the document says one may go. A selection reaching from
 * outside a `[…]{size=24}` to inside it has no single pair of markers around it; an edge the text
 * field widened over a hidden `**` is not the edge the user pointed at; and two marker runs of the
 * same symbol that come to touch stop being two runs at all. Each of those was fixed on its own and
 * each fix uncovered the next one, because none of them was the bug — the *representation* was.
 *
 * So the bar no longer edits Markdown. It reads the line into [InlineCell]s, changes the styles on
 * the cells the user selected, and writes the line back out from scratch. Every question that used
 * to be delicate — where do the markers go, do they nest, does this one need splitting, what is the
 * selection now — is answered once, here, by [write]. Nothing else has to know.
 *
 * What comes back is checked against what went in before it is used ([writeChecked]). Markdown
 * cannot express every combination of overlapping styles unambiguously, so the writer is allowed to
 * be wrong; it is not allowed to be wrong *silently*, and a form that does not read back as what it
 * was meant to say is discarded in favour of one that does.
 */
internal object InlineModel {

    /** The styles this app can write on a run of text. */
    data class InlineStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val attrs: MdAttrs = MdAttrs(),
    ) {
        val hasEmphasis: Boolean get() = bold || italic || strike
        val isEmpty: Boolean get() = !hasEmphasis && attrs.isEmpty
    }

    /**
     * One unit of prose: a single character, or something copied through whole.
     *
     * An atom — a code span, a formula, a link, an image — has structure of its own that the styles
     * here say nothing about, and rewriting it from a model that does not understand it would be a
     * way to lose it. So its source is kept exactly as written and only what surrounds it changes.
     * [visible] is what the reader sees of it, which is what the selection is measured in.
     */
    data class InlineCell(
        /** Where the cell's source begins, so a selection edge can be placed among the cells. */
        val at: Int,
        val source: String,
        val visible: String,
        val style: InlineStyle,
    )

    /** Which emphasis a marker pair stands for, and how it is written. */
    enum class Kind(val marker: String, val tag: String) {
        BOLD("**", "strong"),
        STRIKE("~~", "del"),
        ITALIC("*", "em");

        fun on(style: InlineStyle): Boolean = when (this) {
            BOLD -> style.bold
            STRIKE -> style.strike
            ITALIC -> style.italic
        }

        fun set(style: InlineStyle, value: Boolean): InlineStyle = when (this) {
            BOLD -> style.copy(bold = value)
            STRIKE -> style.copy(strike = value)
            ITALIC -> style.copy(italic = value)
        }

        fun off(style: InlineStyle): InlineStyle = set(style, false)
    }

    // ---- Reading ---------------------------------------------------------------------------------

    /**
     * The prose in `[from, to)` of [text], one cell per character the reader can see.
     *
     * [from] must be the start of a line's prose — past whatever block markers it carries — and [to]
     * its end, because inline syntax cannot cross a line break and a half-read span is not a span.
     *
     * Styles are folded outermost inwards, so the innermost `[…]{size=…}` is the one whose size a
     * character ends up with, which is the one the reader sees it at.
     */
    fun parse(text: String, from: Int, to: Int): List<InlineCell> {
        val spans = MarkdownRenderer.plan(text).inline
            .filter { it.openStart >= from && it.closeEnd <= to }

        fun styleAt(at: Int): InlineStyle {
            var style = InlineStyle()
            for (span in spans) {
                if (at < span.openEnd || at >= span.closeStart) continue
                style = applying(text, span, style)
            }
            return style
        }

        val cells = ArrayList<InlineCell>()
        var i = from
        while (i < to) {
            val atom = atomAt(text, i, to)
            if (atom != null) {
                cells += InlineCell(
                    at = i,
                    source = text.substring(i, atom),
                    visible = MarkdownRenderer.render(text.substring(i, atom)),
                    // The atom's own syntax is inside it; what it wears is what surrounds it.
                    style = styleAt(i),
                )
                i = atom
                continue
            }
            // Marker characters belong to the syntax, not the prose: the styles they confer are
            // already on the cells between them.
            if (spans.any { i >= it.openStart && i < it.openEnd || i >= it.closeStart && i < it.closeEnd }) {
                i++
                continue
            }
            cells += InlineCell(i, text[i].toString(), text[i].toString(), styleAt(i))
            i++
        }
        return cells
    }

    /** [style] with what [span] confers laid over it. */
    private fun applying(text: String, span: MdInline, style: InlineStyle): InlineStyle {
        val opener = text.substring(span.openStart, span.openEnd)
        if (opener.startsWith('[')) {
            val attrs = MarkdownRenderer
                .attrSuffixAt(text, span.closeStart + 1, span.closeEnd)?.attrs ?: return style
            return style.copy(attrs = style.attrs.mergedWith(attrs))
        }
        if (opener.startsWith('<')) {
            val kind = Kind.entries.firstOrNull { opener == "<" + it.tag + ">" } ?: return style
            return kind.set(style, true)
        }
        val symbol = opener[0]
        // A run is counted, never measured: `***` is bold *and* italic, and three is not two plus
        // one of something else. See [MarkdownActions.confers].
        return when (symbol) {
            '~' -> style.copy(strike = true)
            '*', '_' -> style.copy(
                bold = style.bold || opener.length >= 2,
                italic = style.italic || opener.length % 2 == 1,
            )
            else -> style
        }
    }

    /**
     * Where the atom starting at [at] ends, or null if nothing there is one.
     *
     * A code span and a formula are atoms because what is between their markers is characters
     * rather than Markdown; a link and an image are atoms because half of each of them — the
     * destination — is not prose at all, and a model of prose has nowhere to put it.
     */
    private fun atomAt(text: String, at: Int, to: Int): Int? {
        when (text[at]) {
            '`', '$' -> {
                val close = text.indexOf(text[at], at + 1)
                if (close in (at + 1) until to) return close + 1
            }
            '!' -> if (text.startsWith("![", at)) return referenceEnd(text, at + 2, to)
            '[' -> {
                // A bracketed span is not an atom: its contents are prose, and its braces are a
                // style the model carries. Only a link wears the same opening bracket.
                if (MarkdownRenderer.attrSpanAt(text, at, to) != null) return null
                return referenceEnd(text, at + 1, to)
            }
        }
        return null
    }

    /** The end of a `](url)` tail opened at [labelStart], or null if there is not one. */
    private fun referenceEnd(text: String, labelStart: Int, to: Int): Int? {
        val close = text.indexOf(']', labelStart)
        if (close < 0 || close + 1 >= to || text[close + 1] != '(') return null
        val end = text.indexOf(')', close + 1)
        return if (end in 0 until to) end + 1 else null
    }

    // ---- Writing ---------------------------------------------------------------------------------

    /**
     * The cells written back as Markdown, in the first arrangement that reads as what they say.
     *
     * Markdown cannot always say what a set of overlapping styles means. Two marker runs of the
     * same symbol that end up touching are one run of their combined width — `**` against `*` is
     * `***`, which is a third thing — and where a phrase has one of those at each end the two pair
     * off with each other and the words between them come apart. There is no arrangement of
     * asterisks that avoids it; that is the syntax, not this code.
     *
     * So several arrangements are tried and each is read back before it is accepted. A bracketed
     * span makes a good wall — nothing on one side of a `]{size=24}[` can bind to anything on the
     * other — so where the styles carry one, splitting on it settles cases that markers alone
     * cannot. What is returned is the first arrangement that reads back correctly, or, if none
     * does, the one that at least leaves the reader's own words untouched.
     */
    fun write(cells: List<InlineCell>): String {
        // Tidied once, before anything is written, and every candidate then measured against the
        // tidied model rather than the one that came in. Tidying is a real change — a style run
        // that had been left touching a space gives that character up — so checking the output
        // against the *untidied* cells reported every arrangement as wrong, and the writer fell
        // back to its first guess every single time. The clever variants below were never reached.
        val tidy = groups(cells).flatMap(::tidied)
        var fallback: String? = null
        for (variant in Variant.entries) {
            val candidate = writeAs(tidy, variant)
            if (reads(candidate, tidy)) return candidate
            // Losing a style is a disappointment; losing the words is a broken document.
            if (fallback == null && visibleOf(candidate) == tidy.joinToString("") { it.visible }) {
                fallback = candidate
            }
        }
        return fallback ?: writeAs(tidy, Variant.NESTED)
    }

    /**
     * [cells] cut where the size or colour changes.
     *
     * The top-level structure of a written line, and the unit tidying works in: a run of bold that
     * carries on across a change of size is written as two marker pairs, and each of them has to
     * keep clear of whitespace on its own. `** g**` is two asterisks on screen in every reader.
     */
    private fun groups(cells: List<InlineCell>): List<List<InlineCell>> {
        val out = ArrayList<List<InlineCell>>()
        var i = 0
        while (i < cells.size) {
            val attrs = cells[i].style.attrs
            var j = i
            while (j < cells.size && cells[j].style.attrs == attrs) j++
            out += cells.subList(i, j)
            i = j
        }
        return out
    }

    /**
     * How much the writer is allowed to take apart to keep the markup readable.
     *
     * In order of how much the source is disturbed, so the tidiest arrangement that works is the
     * one used and the blunter ones are only reached for when it does not.
     */
    private enum class Variant {
        /** Markers nested as deeply as they will go: `[**a *b* c**]{size=24}`. */
        NESTED,

        /** Italic taken outside bold where their runs coincide, which moves where a `***` falls. */
        ITALIC_OUTSIDE,

        /** A bracketed span per run of emphasis, so no two marker runs can touch. */
        SPLIT,

        /**
         * `<em>` and `<strong>` in place of the markers.
         *
         * The last resort, and the only one that always works: a tag has an unmistakable start and
         * end, so two of them may sit against each other and still be two. Reached for only where
         * asterisks cannot be arranged to say the right thing — two styles that overlap rather than
         * nest, most often — so a note that never needed it never grows one.
         */
        TAGS,
    }

    private fun writeAs(cells: List<InlineCell>, variant: Variant): String {
        val out = StringBuilder()
        for (group in groups(cells)) {
            val attrs = group.first().style.attrs
            if (attrs.isEmpty) {
                out.append(emphasis(group, variant))
            } else if (variant == Variant.SPLIT) {
                // One span per run of emphasis: the brackets then stand between every pair of
                // marker runs, and two of them can never come to touch.
                var k = 0
                while (k < group.size) {
                    var m = k
                    while (m < group.size && sameEmphasis(group[m].style, group[k].style)) m++
                    out.append(bracketed(emphasis(group.subList(k, m), Variant.NESTED), attrs))
                    k = m
                }
            } else {
                out.append(bracketed(emphasis(group, variant), attrs))
            }
        }
        return out.toString()
    }

    private fun sameEmphasis(a: InlineStyle, b: InlineStyle): Boolean =
        a.bold == b.bold && a.italic == b.italic && a.strike == b.strike

    /**
     * `[body]{…}`, with any whitespace at its ends left outside the brackets.
     *
     * A tag whose first character is a space is one whose words cannot be selected without picking
     * up the gap beside them, and every later split would then grow another space.
     */
    private fun bracketed(body: String, attrs: MdAttrs): String {
        val core = body.trim()
        if (core.isEmpty()) return body
        val lead = body.take(body.length - body.trimStart().length)
        val trail = body.takeLast(body.length - body.trimEnd().length)
        return "$lead[$core]${attrs.render()}$trail"
    }

    /**
     * The emphasis on [cells], written as nested marker pairs.
     *
     * Greedy and longest-first: the style whose run reaches furthest from the start of what is left
     * becomes the outer pair, and the rest is written inside it. That is what makes the markup nest
     * rather than interleave — `**a *b* c**` and never `**a *b** c*` — and it is the shortest
     * arrangement, because a style that runs the whole way is written once instead of per word.
     */
    private fun emphasis(cells: List<InlineCell>, variant: Variant): String {
        if (cells.isEmpty()) return ""
        val first = cells[0].style
        val kinds = Kind.entries.filter { it.on(first) }
        if (kinds.isEmpty()) {
            val plain = cells.takeWhile { !it.style.hasEmphasis }
            return plain.joinToString("") { it.source } + emphasis(cells.drop(plain.size), variant)
        }

        val reach = { kind: Kind -> cells.takeWhile { kind.on(it.style) }.size }
        val longest = kinds.maxOf(reach)
        val outer = kinds.filter { reach(it) == longest }.let { tied ->
            // A tie is bold and italic over the very same run — `***word***`. Which of the two is
            // written outside decides which end of the run carries the three, and that is the only
            // lever there is when a `***` at one end has to be moved to the other.
            if (variant == Variant.ITALIC_OUTSIDE) tied.last() else tied.first()
        }
        val head = cells.take(reach(outer)).map { it.copy(style = outer.off(it.style)) }
        val open = if (variant == Variant.TAGS) "<" + outer.tag + ">" else outer.marker
        val close = if (variant == Variant.TAGS) "</" + outer.tag + ">" else outer.marker
        return open + emphasis(head, variant) + close + emphasis(cells.drop(reach(outer)), variant)
    }

    // ---- Checking --------------------------------------------------------------------------------

    /** Whether [markdown] reads back as exactly the styles [cells] were asking for. */
    private fun reads(markdown: String, cells: List<InlineCell>): Boolean {
        val read = parse(markdown, 0, markdown.length)
        if (read.size != cells.size) return false
        return read.indices.all { read[it].visible == cells[it].visible && read[it].style == cells[it].style }
    }

    private fun visibleOf(markdown: String): String = MarkdownRenderer.render(markdown)

    // ---- Changing --------------------------------------------------------------------------------

    /**
     * [cells] with [change] applied to `[from, to)`, and the result tidied.
     *
     * Tidying is one rule: **no style may begin or end on whitespace.** A marker against a space is
     * not a marker — `** bold**` is two asterisks on screen in every reader there is — so a run
     * that has been left touching one gives that character up rather than the whole run being
     * refused. It is done after the change rather than before it because a change is what leaves a
     * run touching a space in the first place.
     */
    fun changed(
        cells: List<InlineCell>,
        from: Int,
        to: Int,
        change: (InlineStyle) -> InlineStyle,
    ): List<InlineCell> {
        val out = cells.mapIndexed { i, cell ->
            if (i in from until to) cell.copy(style = change(cell.style)) else cell
        }
        return tidied(out)
    }

    fun tidied(cells: List<InlineCell>): List<InlineCell> {
        val styles = cells.map { it.style }.toMutableList()
        for (kind in Kind.entries) trimRuns(cells, styles, { kind.on(it) }, { kind.off(it) })
        trimRuns(cells, styles, { !it.attrs.isEmpty }, { it.copy(attrs = MdAttrs()) })
        return cells.mapIndexed { i, cell -> cell.copy(style = styles[i]) }
    }

    /** Takes a style off the blank cells at either end of every run of it. */
    private inline fun trimRuns(
        cells: List<InlineCell>,
        styles: MutableList<InlineStyle>,
        on: (InlineStyle) -> Boolean,
        off: (InlineStyle) -> InlineStyle,
    ) {
        var i = 0
        while (i < cells.size) {
            if (!on(styles[i])) {
                i++
                continue
            }
            var end = i
            while (end < cells.size && on(styles[end])) end++
            var start = i
            while (start < end && cells[start].visible.isBlank()) {
                styles[start] = off(styles[start])
                start++
            }
            var last = end
            while (last > start && cells[last - 1].visible.isBlank()) {
                styles[last - 1] = off(styles[last - 1])
                last--
            }
            i = end
        }
    }
}
