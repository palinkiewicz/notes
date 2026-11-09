package pl.dakil.notes.editor.markdown

/**
 * A run of source the formatting bar has to keep whole — a code span, a link, an escape — in
 * **source** coordinates, `[start, end)`.
 *
 * Its own syntax is inside it, and editing half of it is editing what it *is* rather than what it
 * says, so [InlineModel] treats each as one indivisible character of prose. Reported by the parser
 * that found it, so the model can never disagree with the renderer about where one ends.
 */
data class MdAtom(val start: Int, val end: Int)

/**
 * One line's inline syntax, as CommonMark reads it — with GFM strikethrough, Pandoc maths and
 * bracketed spans, and the three HTML emphasis tags the writer falls back on.
 *
 * Emphasis is resolved by the spec's own algorithm (§6.3, "process emphasis"): delimiter runs are
 * collected on a stack as the line is read and paired off afterwards by flanking and the rule of
 * three, and brackets are matched on a second stack as each `]` is met. That replaced a scanner that
 * went looking ahead for a closing marker with `indexOf` and grew a special case for every way the
 * answer came out wrong; the spec's procedure is the definition those special cases were each
 * approximating, and it reads a line in one pass instead of one pass per unmatched marker.
 *
 * Plain Kotlin and no state, so that what a line means is a JUnit question.
 */
internal object MarkdownInline {

    /** What an inline image is shown as in the middle of prose. */
    private const val IMAGE_GLYPH = "🖼 "

    class Result {
        val edits = ArrayList<MdEdit>()
        val styles = ArrayList<MdStyleRange>()
        val spans = ArrayList<MdInline>()
        val atoms = ArrayList<MdAtom>()
    }

    /** Reads `[from, to)` of [text], which must lie within one line (or one table cell). */
    fun parse(text: String, from: Int, to: Int, references: Map<String, String>): Result {
        val marks = ArrayList<Mark>()
        scan(text, from, to, references, marks)
        return emit(mergeTriples(text, marks))
    }

    // ---- What was found ----------------------------------------------------------------------

    private enum class Kind { EMPHASIS, CODE, MATH, TAG, ATTR, LINK, IMAGE, AUTOLINK, ESCAPE }

    /**
     * One piece of syntax: what opens it, `[openStart, openEnd)`, and what closes it,
     * `[closeStart, closeEnd)`. An escape has nothing to close and carries an empty closer.
     */
    private class Mark(
        val kind: Kind,
        val openStart: Int,
        val openEnd: Int,
        val closeStart: Int,
        val closeEnd: Int,
        val styles: List<MdStyleRange> = emptyList(),
    )

    // ---- Reading -----------------------------------------------------------------------------

    /** A run of `*`, `_` or `~~` that may open or close emphasis. See [processEmphasis]. */
    private class Delim(
        val char: Char,
        var pos: Int,
        var count: Int,
        val original: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
        var removed: Boolean = false,
    )

    /**
     * A `[` or `![` waiting for its `]`. [bottom] is how many delimiters were on the stack when it
     * was met, so what is inside it can be paired off on its own once it closes.
     */
    private class Bracket(val pos: Int, val image: Boolean, val bottom: Int) {
        /** Set once a link has formed after it: a link may not contain another link. */
        var noLink = false
    }

    private fun scan(text: String, from: Int, to: Int, references: Map<String, String>, out: MutableList<Mark>) {
        val delims = ArrayList<Delim>()
        val brackets = ArrayList<Bracket>()
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                // An escaped character is a literal one: it opens nothing, closes nothing, and the
                // backslash that says so is not shown.
                c == '\\' && i + 1 < to && isAsciiPunctuation(text[i + 1]) -> {
                    out += Mark(Kind.ESCAPE, i, i + 1, i + 2, i + 2)
                    i += 2
                }

                c == '`' -> {
                    val n = runLength(text, i, to, '`')
                    val close = backtickRun(text, i + n, to, n)
                    if (close >= 0) {
                        out += Mark(Kind.CODE, i, i + n, close, close + n, listOf(MdStyleRange(i + n, close, MdStyle.CODE)))
                        i = close + n
                    } else {
                        // An opener with no closer of its own length is literal, all of it.
                        i += n
                    }
                }

                c == '$' -> {
                    val n = runLength(text, i, to, '$')
                    i = math(text, i, n, to, out).takeIf { it > 0 } ?: (i + n)
                }

                c == '<' -> i = tag(text, i, to, references, out).takeIf { it > 0 }
                    ?: autolink(text, i, to, out).takeIf { it > 0 }
                    ?: (i + 1)

                c == '!' && i + 1 < to && text[i + 1] == '[' -> {
                    brackets += Bracket(i, image = true, bottom = delims.size)
                    i += 2
                }

                c == '[' -> {
                    brackets += Bracket(i, image = false, bottom = delims.size)
                    i++
                }

                c == ']' -> i = closeBracket(text, i, to, references, delims, brackets, out)

                c == '*' || c == '_' || c == '~' -> {
                    val n = runLength(text, i, to, c)
                    // Strikethrough is `~~` and nothing else: one tilde is a tilde, three a fence
                    // character somebody typed in prose.
                    if (c != '~' || n == 2) delimiter(text, c, i, n)?.let { delims += it }
                    i += n
                }

                else -> i++
            }
        }
        processEmphasis(delims, 0, out)
    }

    /** A delimiter run of [n] [char]s at [at], or null when it can neither open nor close. */
    private fun delimiter(text: String, char: Char, at: Int, n: Int): Delim? {
        val before = charBefore(text, at)
        val after = charAfter(text, at + n)
        // CommonMark's flanking rules, which are about the whole run and what stands either side
        // of it — what keeps `2 * 3 * 4` arithmetic and `** bold**` asterisks.
        val leftFlanking = !after.isWhitespace() &&
            (!isPunctuation(after) || before.isWhitespace() || isPunctuation(before))
        val rightFlanking = !before.isWhitespace() &&
            (!isPunctuation(before) || after.isWhitespace() || isPunctuation(after))
        // An underscore inside a word is `snake_case`, never emphasis, at either end.
        val canOpen = if (char == '_') leftFlanking && (!rightFlanking || isPunctuation(before)) else leftFlanking
        val canClose = if (char == '_') rightFlanking && (!leftFlanking || isPunctuation(after)) else rightFlanking
        if (!canOpen && !canClose) return null
        return Delim(char, at, n, n, canOpen, canClose)
    }

    /** The start of the next run of exactly [n] backticks in `[from, to)`, or -1. */
    private fun backtickRun(text: String, from: Int, to: Int, n: Int): Int {
        var i = from
        while (i < to) {
            if (text[i] != '`') {
                i++
                continue
            }
            val run = runLength(text, i, to, '`')
            if (run == n) return i
            i += run
        }
        return -1
    }

    /**
     * Pandoc's `$…$` and `$$…$$`, returning the offset past it, or 0 when this is not maths.
     *
     * Pandoc's rule, because a dollar sign in prose is usually money: the opening `$` has to stand
     * against what it opens and the closing one against what it closes, and a closer followed by a
     * digit closes nothing — so "$5 and $10" is two prices and not the formula "5 and ".
     */
    private fun math(text: String, at: Int, n: Int, to: Int, out: MutableList<Mark>): Int {
        if (n == 2) {
            val close = text.indexOf("$$", at + 2)
            if (close < at + 3 || close + 2 > to) return 0
            out += Mark(Kind.MATH, at, at + 2, close, close + 2, listOf(MdStyleRange(at + 2, close, MdStyle.MATH)))
            return close + 2
        }
        if (n != 1 || at + 1 >= to || text[at + 1].isWhitespace()) return 0
        var j = at + 2
        while (j < to) {
            when (text[j]) {
                '\\' -> {
                    j += 2
                    continue
                }

                '$' -> if (!text[j - 1].isWhitespace() && !(j + 1 < to && text[j + 1].isDigit())) {
                    out += Mark(Kind.MATH, at, at + 1, j, j + 1, listOf(MdStyleRange(at + 1, j, MdStyle.MATH)))
                    return j + 1
                }
            }
            j++
        }
        return 0
    }

    /**
     * The three emphases HTML can also say, which is how this app says them when asterisks cannot.
     *
     * Markdown's markers are ambiguous where two runs of the same symbol come to touch, and two
     * styles that *overlap* rather than nest have no arrangement of asterisks at all. These tags are
     * the way out, and not an invention: raw HTML is part of CommonMark and every reader renders
     * them. The writer reaches for them only where markers cannot say the right thing.
     *
     * Their contents are read on their own, as a line of their own would be, so nothing inside can
     * pair with anything outside.
     */
    private fun tag(text: String, at: Int, to: Int, references: Map<String, String>, out: MutableList<Mark>): Int {
        for ((name, style) in HTML_EMPHASIS) {
            val open = "<$name>"
            val close = "</$name>"
            if (!text.startsWith(open, at)) continue
            val body = at + open.length
            val end = text.indexOf(close, body)
            // An empty pair styles nothing and would hide the tags that say so.
            if (end <= body || end + close.length > to) continue
            out += Mark(Kind.TAG, at, body, end, end + close.length, listOf(MdStyleRange(body, end, style)))
            scan(text, body, end, references, out)
            return end + close.length
        }
        return 0
    }

    private val HTML_EMPHASIS = listOf(
        "strong" to MdStyle.BOLD,
        "em" to MdStyle.ITALIC,
        "del" to MdStyle.STRIKE,
    )

    /** `<https://…>`, returning the offset past it, or 0. The address is what is shown. */
    private fun autolink(text: String, at: Int, to: Int, out: MutableList<Mark>): Int {
        var i = at + 1
        val schemeStart = i
        while (i < to && (text[i].isAsciiLetterOrDigit() || text[i] in "+.-")) i++
        val scheme = i - schemeStart
        if (scheme !in 2..32 || !text[schemeStart].isAsciiLetter() || i >= to || text[i] != ':') return 0
        while (i < to && text[i] != '>') {
            if (text[i].isWhitespace() || text[i] == '<') return 0
            i++
        }
        if (i >= to) return 0
        out += Mark(Kind.AUTOLINK, at, at + 1, i, i + 1, listOf(MdStyleRange(at + 1, i, MdStyle.LINK)))
        return i + 1
    }

    /**
     * A `]`: what it closes, if anything, and the offset to carry on reading from.
     *
     * Tried in order: a bracketed span's `]{…}`, an inline link's `](…)`, then a reference — full
     * `[text][id]`, collapsed `[id][]` or shortcut `[id]`. A link takes the delimiters inside its
     * text and pairs them off among themselves, so emphasis cannot open inside a link and close
     * outside it.
     */
    private fun closeBracket(
        text: String,
        at: Int,
        to: Int,
        references: Map<String, String>,
        delims: MutableList<Delim>,
        brackets: MutableList<Bracket>,
        out: MutableList<Mark>,
    ): Int {
        val opener = brackets.removeLastOrNull() ?: return at + 1
        val labelStart = opener.pos + if (opener.image) 2 else 1

        fun close(kind: Kind, end: Int, styles: List<MdStyleRange>): Int {
            if (kind == Kind.IMAGE) {
                // A picture's description is not prose: nothing in it is paired off.
                truncate(delims, opener.bottom)
            } else {
                processEmphasis(delims, opener.bottom, out)
            }
            out += Mark(kind, opener.pos, labelStart, at, end, styles)
            if (kind == Kind.LINK) for (bracket in brackets) if (!bracket.image) bracket.noLink = true
            return end
        }

        if (!opener.image && at > labelStart) {
            MarkdownRenderer.attrSuffixAt(text, at + 1, to)?.let { suffix ->
                val styles = ArrayList<MdStyleRange>(2)
                suffix.attrs.size?.let { styles += MdStyleRange(labelStart, at, MdStyle.SIZE, it) }
                suffix.attrs.color?.let { styles += MdStyleRange(labelStart, at, MdStyle.COLOR, it) }
                return close(Kind.ATTR, suffix.end, styles)
            }
        }
        if (!opener.image && opener.noLink) return at + 1

        val kind = if (opener.image) Kind.IMAGE else Kind.LINK
        val style = listOf(MdStyleRange(opener.pos, at, if (opener.image) MdStyle.IMAGE else MdStyle.LINK))

        inlineLinkEnd(text, at + 1, to)?.let { return close(kind, it, style) }

        if (at + 1 < to && text[at + 1] == '[') {
            val labelEnd = text.indexOf(']', at + 2)
            if (labelEnd in (at + 2) until to && text.indexOf('[', at + 2) !in (at + 2) until labelEnd) {
                val collapsed = labelEnd == at + 2
                // A full reference is a link whether or not the label is defined — as it always
                // was here; a collapsed one has nothing else to go on.
                if (!collapsed || normalized(text.substring(labelStart, at)) in references) {
                    return close(kind, labelEnd + 1, style)
                }
            }
        }

        if (normalized(text.substring(labelStart, at)) in references) return close(kind, at + 1, style)
        return at + 1
    }

    /** The offset past `(destination "title")` starting at [at], or null if that is not what is there. */
    private fun inlineLinkEnd(text: String, at: Int, to: Int): Int? {
        if (at >= to || text[at] != '(') return null
        var i = skipSpaces(text, at + 1, to)
        if (i < to && text[i] == '<') {
            i++
            while (i < to && text[i] != '>') {
                if (text[i] == '<') return null
                if (text[i] == '\\') i++
                i++
            }
            if (i >= to) return null
            i++
        } else {
            // Parentheses balance inside a bare destination: a Wikipedia address ends in one.
            var depth = 0
            while (i < to) {
                val c = text[i]
                if (c == '\\' && i + 1 < to) {
                    i += 2
                    continue
                }
                if (c.isWhitespace()) break
                if (c == '(') depth++
                if (c == ')') {
                    if (depth == 0) break
                    depth--
                }
                i++
            }
            if (depth != 0) return null
        }
        val beforeTitle = i
        i = skipSpaces(text, i, to)
        if (i < to && i > beforeTitle && text[i] in "\"'(") {
            val end = if (text[i] == '(') ')' else text[i]
            i++
            while (i < to && text[i] != end) {
                if (text[i] == '\\') i++
                i++
            }
            if (i >= to) return null
            i = skipSpaces(text, i + 1, to)
        }
        return if (i < to && text[i] == ')') i + 1 else null
    }

    // ---- Pairing emphasis --------------------------------------------------------------------

    /**
     * Pairs the delimiters above [bottom] off with each other, as §6.3 of the spec describes, and
     * takes all of them off the stack afterwards — what is left unpaired is literal.
     *
     * Each closer looks back for the nearest opener of its own character that the rule of three
     * allows, and the pair takes two characters from each run where both have two to give and one
     * otherwise — the inside of the opener and the start of the closer, so `***a***` is emphasis
     * round strong and `*a **b***` closes the bold before the italic.
     */
    private fun processEmphasis(delims: MutableList<Delim>, bottom: Int, out: MutableList<Mark>) {
        // How far back it is worth looking for each kind of closer: a search that failed once
        // fails again for every later closer of the same kind, so it is not repeated.
        val floors = HashMap<Int, Int>()
        var ci = bottom
        while (ci < delims.size) {
            val closer = delims[ci]
            if (closer.removed || !closer.canClose) {
                ci++
                continue
            }
            val key = closer.char.code * 8 + (closer.original % 3) * 2 + if (closer.canOpen) 1 else 0
            val floor = maxOf(bottom, floors[key] ?: bottom)

            var oi = ci - 1
            while (oi >= floor) {
                val opener = delims[oi]
                if (!opener.removed && opener.char == closer.char && opener.canOpen && pairs(opener, closer)) break
                oi--
            }
            if (oi < floor) {
                floors[key] = ci
                if (!closer.canOpen) closer.removed = true
                ci++
                continue
            }

            val opener = delims[oi]
            val use = if (closer.char == '~' || (opener.count >= 2 && closer.count >= 2)) 2 else 1
            val style = when {
                closer.char == '~' -> MdStyle.STRIKE
                use == 2 -> MdStyle.BOLD
                else -> MdStyle.ITALIC
            }
            val openEnd = opener.pos + opener.count
            out += Mark(
                Kind.EMPHASIS, openEnd - use, openEnd, closer.pos, closer.pos + use,
                listOf(MdStyleRange(openEnd, closer.pos, style)),
            )
            opener.count -= use
            closer.pos += use
            closer.count -= use
            for (k in oi + 1 until ci) delims[k].removed = true
            if (opener.count == 0) opener.removed = true
            if (closer.count == 0) {
                closer.removed = true
                ci++
            }
        }
        truncate(delims, bottom)
    }

    /** Whether [opener] and [closer] may pair — the rule of three, and strikethrough's exact match. */
    private fun pairs(opener: Delim, closer: Delim): Boolean {
        if (closer.char == '~') return opener.count == 2 && closer.count == 2
        if ((opener.canClose || closer.canOpen) &&
            (opener.original + closer.original) % 3 == 0 &&
            !(opener.original % 3 == 0 && closer.original % 3 == 0)
        ) {
            return false
        }
        return true
    }

    private fun truncate(delims: MutableList<Delim>, size: Int) {
        while (delims.size > size) delims.removeAt(delims.lastIndex)
    }

    // ---- Writing the plan --------------------------------------------------------------------

    /**
     * `***both***` as the one span it always was here, rather than the emphasis-round-strong pair
     * the spec builds it from: the formatting bar and the editing guards treat a triple marker as a
     * single run, and two spans sharing every edge say nothing more than one.
     */
    private fun mergeTriples(text: String, marks: List<Mark>): List<Mark> {
        val emphasis = marks.filter { it.kind == Kind.EMPHASIS }
        if (emphasis.size < 2) return marks
        val byOpen = emphasis.associateBy { it.openStart }
        val merged = HashSet<Mark>()
        val out = ArrayList<Mark>(marks.size)
        for (outer in emphasis) {
            val inner = byOpen[outer.openEnd] ?: continue
            if (inner in merged || outer in merged) continue
            if (inner.closeEnd != outer.closeStart || text[inner.openStart] != text[outer.openStart]) continue
            val styles = (inner.styles + outer.styles).map { it.style }.toSet()
            if (styles != setOf(MdStyle.BOLD, MdStyle.ITALIC)) continue
            merged += inner
            merged += outer
            out += Mark(
                Kind.EMPHASIS, outer.openStart, inner.openEnd, inner.closeStart, outer.closeEnd,
                listOf(
                    MdStyleRange(inner.openEnd, inner.closeStart, MdStyle.BOLD),
                    MdStyleRange(inner.openEnd, inner.closeStart, MdStyle.ITALIC),
                ),
            )
        }
        if (merged.isEmpty()) return marks
        for (mark in marks) if (mark !in merged) out += mark
        return out
    }

    private fun emit(marks: List<Mark>): Result {
        val result = Result()
        // Outermost first: by where each opens, and of two opening together, the one closing last.
        val ordered = marks.sortedWith(compareBy<Mark>({ it.openStart }, { -it.closeEnd }))
        for (mark in ordered) {
            when (mark.kind) {
                Kind.ESCAPE -> {
                    result.edits += MdEdit(mark.openStart, mark.openEnd, "")
                    result.atoms += MdAtom(mark.openStart, mark.closeEnd)
                }

                Kind.IMAGE -> {
                    // A glyph in place of `![`, so a picture with no description does not vanish.
                    result.edits += MdEdit(mark.openStart, mark.openEnd, IMAGE_GLYPH)
                    result.edits += MdEdit(mark.closeStart, mark.closeEnd, "")
                    result.atoms += MdAtom(mark.openStart, mark.closeEnd)
                }

                else -> {
                    result.edits += MdEdit(mark.openStart, mark.openEnd, "")
                    result.edits += MdEdit(mark.closeStart, mark.closeEnd, "")
                    when (mark.kind) {
                        // A link's target is written once and is not reopened by an edit, so it is
                        // kept whole rather than offered as a run to split.
                        Kind.LINK, Kind.AUTOLINK, Kind.CODE, Kind.MATH ->
                            result.atoms += MdAtom(mark.openStart, mark.closeEnd)

                        else -> Unit
                    }
                    if (mark.kind != Kind.LINK && mark.kind != Kind.AUTOLINK) {
                        result.spans += MdInline(mark.openStart, mark.openEnd, mark.closeStart, mark.closeEnd)
                    }
                }
            }
            result.styles += mark.styles
        }
        result.edits.sortWith(compareBy<MdEdit>({ it.start }, { it.end }))
        return result
    }

    // ---- Characters --------------------------------------------------------------------------

    /** A line's edges count as whitespace, as the spec says they do. */
    private fun charBefore(text: String, at: Int): Char =
        if (at <= 0 || text[at - 1] == '\n') ' ' else text[at - 1]

    private fun charAfter(text: String, at: Int): Char =
        if (at >= text.length || text[at] == '\n') ' ' else text[at]

    private fun runLength(text: String, at: Int, to: Int, char: Char): Int {
        var i = at
        while (i < to && text[i] == char) i++
        return i - at
    }

    private fun skipSpaces(text: String, at: Int, to: Int): Int {
        var i = at
        while (i < to && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    private fun isAsciiPunctuation(c: Char): Boolean = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Unicode punctuation and symbols, which is what the spec's flanking rules mean by it. */
    private fun isPunctuation(c: Char): Boolean {
        if (isAsciiPunctuation(c)) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true

            else -> false
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || this in '0'..'9'

    /** How a reference label is looked up: case and surrounding blanks do not count. */
    fun normalized(label: String): String = label.trim().lowercase(java.util.Locale.ROOT)
}
