package pl.dakil.notes.editor.markdown

import pl.dakil.notes.editor.markdown.code.CodeHighlighter
import pl.dakil.notes.editor.markdown.code.CodeLanguages
import pl.dakil.notes.editor.markdown.code.CodeToken
import pl.dakil.notes.model.ColorCodec
import java.util.Locale

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

    /** A nested list item's indent: monospace, so one level is always the same step. */
    INDENT,

    /** The blank a checkbox stands in: monospace, so its width is known before the box is placed. */
    TASK_BOX,

    /**
     * Extra leading, worn by the newline that ends a line inside a drawn block.
     *
     * A line is as tall as the tallest thing on it, and a newline is a thing on a line, so an
     * oversized terminator buys the line it ends a little more room. What it does *not* buy is
     * space between one paragraph and the next: the room lands on the terminator's own visual
     * line, which on a line that wrapped is the last of several — so used as paragraph spacing it
     * put the gap in the middle of a wrapped list item instead of after it. Only blocks wear it
     * now, where every row is a line of its own and there is no wrapping to be caught out by.
     */
    LEADING_TIGHT,

    /** The same again, sized to hold a table's rows off the rules drawn between them. */
    LEADING_CELL,

    /**
     * Text set at a size the author picked, in sp, carried in [MdStyleRange.arg].
     *
     * One of the two things Pandoc's bracketed-span syntax says here — see [MdAttrs]. Understood
     * and hidden in both kinds of note; whether it is *obeyed* is a document's own affair, because
     * a `.md` file has to stay a Markdown file that other editors render sensibly. A sheet always
     * obeys it, and a note does when the user has asked for it. See `rememberMarkdownStyles`.
     */
    SIZE,

    /** Text in a colour the author picked, as packed ARGB in [MdStyleRange.arg]. See [SIZE]. */
    COLOR,

    /** A blank line standing between one paragraph and the next. */
    PARAGRAPH_GAP,

    /** The same, narrower, between two items of the same list. */
    LIST_GAP,

    /** The same, wider, where either side of it is a drawn block. */
    BLOCK_GAP,
}

/**
 * Replace `[start, end)` of the source with [replacement].
 *
 * [hides] says whether what stood there is gone from the reader's view, which is almost always the
 * same question as whether the replacement is empty. Almost: a whole line that renders as nothing
 * but the blank line after it is replaced by a newline and is every bit as invisible. See [gap].
 */
data class MdEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val hides: Boolean = replacement.isEmpty(),
)

/**
 * A run of characters wearing one [MdStyle].
 *
 * [arg] is the style's parameter where it has one, and zero where it does not — a size in sp for
 * [MdStyle.SIZE], packed ARGB for [MdStyle.COLOR], and nothing for every other style. A whole
 * parallel list of parameterised ranges would have been the alternative, and would have meant every
 * consumer of a plan learning that styles come in two kinds.
 */
data class MdStyleRange(val start: Int, val end: Int, val style: MdStyle, val arg: Int = 0)

/**
 * What a bracketed span's braces say: `{size=18 color=#c0392b}`.
 *
 * Both fields are optional and null means "not stated" rather than "set to the default" — a span
 * that names only a colour leaves the size alone, which is what lets one word inside a large
 * heading be recoloured without also being pinned to a size the user never chose.
 *
 * One type for the pair of them, rather than two independent tags, because they share a span: the
 * renderer reads them out of one set of braces and the formatting bar writes them back into one.
 */
data class MdAttrs(val size: Int? = null, val color: Int? = null) {

    val isEmpty: Boolean get() = size == null && color == null

    /**
     * [other]'s stated attributes laid over this one's.
     *
     * How setting a colour on already-sized text keeps the size: the bar names the one attribute
     * the button is about, and everything the span already carried comes through underneath.
     */
    fun mergedWith(other: MdAttrs): MdAttrs =
        MdAttrs(size = other.size ?: size, color = other.color ?: color)

    /** The braces, as written into the document. Empty when there is nothing left to say. */
    fun render(): String {
        if (isEmpty) return ""
        val out = StringBuilder("{")
        size?.let { out.append("size=").append(it) }
        color?.let {
            if (out.length > 1) out.append(' ')
            out.append("color=").append(hexOf(it))
        }
        return out.append('}').toString()
    }

    private companion object {
        /**
         * `#rrggbb`, or `#aarrggbb` where the colour is not opaque.
         *
         * Lower case, and folded with [Locale.ROOT] rather than the device's: a Turkish phone folds
         * `I` to a dotless `ı`, and a colour written that way is one no other device can read back.
         */
        fun hexOf(argb: Int): String =
            ColorCodec.toHex(argb, includeAlpha = ColorCodec.alpha(argb) != 0xFF)
                .lowercase(Locale.ROOT)
    }
}

/** [MdAttrs] plus where the braces holding them end. See [MarkdownRenderer.attrSuffixAt]. */
data class MdAttrSuffix(val attrs: MdAttrs, val end: Int)

/**
 * A whole `[body]{…}` in source coordinates.
 *
 * [start] and [end] bracket the tag; [open] and [close] bracket the text inside it. One definition,
 * because the renderer, the formatting bar and the inline model all have to agree about where a tag
 * begins and ends — and three bracket-counting loops would be three chances to disagree.
 */
data class MdAttrSpan(
    val start: Int,
    val open: Int,
    val close: Int,
    val end: Int,
    val attrs: MdAttrs,
)

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
    val sourceStart: Int,
    val sourceEnd: Int,
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
 * One inline span — `**bold**`, `` `code` ``, `[text]{size=18}` — in **source** coordinates.
 *
 * What opens it is `[openStart, openEnd)` and what closes it `[closeStart, closeEnd)`, kept as
 * ranges rather than as a marker string because not every span is symmetrical: a sized one opens
 * with `[` and closes with `]{size=18}`. The body between them is what wears the style.
 *
 * Recorded so that an edit which would *break* a span can put it back together — see
 * [MarkdownStructure.lineBreak]. Inline syntax cannot cross a line break, so a newline typed in the
 * middle of one is the one insertion that can undo formatting the user cannot even see.
 */
data class MdInline(val openStart: Int, val openEnd: Int, val closeStart: Int, val closeEnd: Int)

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
    /**
     * Ranges that need a hanging indent — the one thing a `SpanStyle` cannot say.
     *
     * A quotation is held clear of the bar drawn beside it, and an indent written into the line as
     * spaces only ever indents the visual line those spaces are on: a quoted sentence long enough
     * to wrap had a first line at the indent and every line after it hard against the bar. So the
     * indent is stated once for the whole block and applied by the layout, which is what makes it
     * survive a wrap. Whole blocks rather than lines, and ending short of the newline that ends the
     * last of them, because a `ParagraphStyle` range is laid out as its own block of text and one
     * ending on a newline gets an empty line under it.
     */
    val indents: List<MdStyleRange> = emptyList(),
    /**
     * The inline spans, outermost first, and the one part of a plan still in source coordinates.
     *
     * They have to be: their only use is rewriting the source around an edit, and a rewrite works
     * in the coordinates the document is written in.
     */
    val inline: List<MdInline> = emptyList(),
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

    /** One level of list nesting, in the monospace face [MdStyle.INDENT] sets. */
    private const val INDENT_BLANK = "   "


    /**
     * What holds a fenced block's code off the box drawn round it.
     *
     * One column of the block's own monospace face, which is about eight dp — the same step, in the
     * same face, that the blank left by a row's opening pipe holds a table's first cell off *its*
     * box. That is what puts a code block and a table at one indent: both boxes are drawn flush
     * with the column the document is set in, and both hold their contents a column inside it.
     *
     * The cost is a code line long enough to wrap: its continuation loses the indent and starts
     * against the border. Nothing typed can survive a wrap, and a box drawn wide enough to give the
     * continuation room is a box hanging out past every other block on the page.
     */
    private const val CODE_INDENT = " "

    /** Beyond this a table cell is a paragraph, and padding it out would waste more than it buys. */
    private const val MAX_CELL_WIDTH = 200

    /**
     * What counts as a font size, in sp.
     *
     * Bounded at both ends because the tag is text a user can type by hand, and neither `{size=0}`
     * nor `{size=99999}` is a document anyone meant to write — one is invisible and the other is a
     * single letter filling the page, and both are easier to create by accident than to undo.
     */
    const val MIN_SIZE_SP = 4
    const val MAX_SIZE_SP = 200

    /**
     * The blank column kept past the end of a cell's text.
     *
     * One, which is what makes a cell's padding the same on both sides. Its rules are drawn down
     * the *left* edge of the blank each pipe became, so the cell to the right of one starts a full
     * column in from it — and this is what buys the cell to the left of one the same column back.
     */
    private const val CELL_PAD = 1

    // The transformation and the drawing layer both ask for the plan of the same string on the same
    // frame. Both run on the main thread, so one slot is enough to make the second call free.
    private var lastSource: String? = null
    private var lastPlan: MarkdownRenderPlan? = null

    fun plan(markdown: String): MarkdownRenderPlan {
        lastPlan?.let { if (lastSource == markdown) return it }

        val edits = ArrayList<MdEdit>()
        val styles = ArrayList<MdStyleRange>()
        val decorations = ArrayList<MdDecoration>()
        val spans = ArrayList<MdInline>()
        val gaps = ArrayList<MdStyleRange>()
        val lines = Lines(markdown)
        val units = units(lines)

        var k = 0
        while (k < lines.count) {
            gapBefore(lines, units, k, edits, gaps)
            k = when {
                MarkdownParser.FENCE.matchEntire(lines.text(k)) != null ->
                    planFence(markdown, lines, k, edits, styles, decorations)

                lines.text(k).trim() == "$$" -> planMathBlock(lines, k, edits, styles)

                lines.startsTable(k) -> planTable(markdown, lines, k, edits, styles, decorations)

                else -> {
                    scanLine(lines, k, edits, styles, decorations, spans)
                    k + 1
                }
            }
        }

        val result = MarkdownRenderPlan(
            edits = edits,
            // The gaps go on last so that nothing overrides them: a leading style hung on the
            // newline that ends a block's last line reaches over the gap below it as well, and
            // would otherwise leave the blank line as tall as a line of code. They arrive already
            // in transformed coordinates — see [gap] for why they cannot be mapped like the rest.
            styles = styles.map { it.mapped(edits) } + gaps,
            decorations = decorations.map { it.mapped(edits) },
            // Read off the bars rather than counted again: a bar spans exactly the block of quoted
            // lines that has to be held clear of it, and is already merged and already mapped.
            indents = decorations.map { it.mapped(edits) }.filterIsInstance<MdQuote>()
                .map { MdStyleRange(it.start, it.end, MdStyle.QUOTE) },
            inline = spans,
        )
        lastSource = markdown
        lastPlan = result
        return result
    }

    /**
     * What [markdown] reads as on screen: every edit applied, nothing styled.
     *
     * The same string the text field is showing, worked out without a field or a layout — which is
     * what lets a copy be checked against what was on screen before it is turned back into source.
     */
    fun render(markdown: String): String {
        val out = StringBuilder(markdown)
        for (edit in plan(markdown).edits.asReversed()) out.replace(edit.start, edit.end, edit.replacement)
        return out.toString()
    }

    /**
     * Whether `[start, end)` of [markdown] covers anything the reader can actually see.
     *
     * The question a text field cannot be asked directly, and the one that says whether a range is a
     * selection a person made or one the field invented. A caret resting against hidden syntax is
     * reported back in source coordinates as the *whole* hidden run — a range several characters
     * wide that is nothing at all on screen — and treating that as a deliberate selection is what
     * used to let a backspace beside a code block quietly eat its closing fence.
     *
     * Asked one character at a time rather than by measuring how much rendered text the range came
     * out as, because the two answers differ where a line is hidden by being turned into the blank
     * one below it: a closing fence renders as one newline and is still nothing anybody can see.
     */
    fun coversVisibleText(markdown: String, start: Int, end: Int): Boolean {
        val edits = plan(markdown).edits
        val from = minOf(start, end).coerceAtLeast(0)
        val to = maxOf(start, end).coerceAtMost(markdown.length)
        return (from until to).any { !isHidden(edits, it) }
    }

    /**
     * Whether `[start, end)` takes away more than one character the reader can see.
     *
     * What separates a keystroke from a request. A backspace removes one thing on screen, and the
     * range a field produces for one is wider than that only in syntax nobody can see: pressed at
     * the end of the bold word in `Apple **is red** now`, the field asks to delete `d**` — the
     * letter the user pointed at and the two markers behind it, because a caret against a hidden run
     * maps back to the whole of it. Every such range holds exactly one visible character.
     *
     * A range holding several is nobody's near miss. It is a selection somebody asked to have
     * deleted — and it arrives looking like a bare caret's keystroke, because an AOSP-derived
     * keyboard answers backspace-with-a-selection by *collapsing* the selection first and then
     * asking for as many characters back as it had covered. By the time the deletion is seen the
     * selection is already gone, so the field can only be judged by what it is taking, and what it
     * is taking is half a note.
     */
    fun takesMoreThanOneVisibleCharacter(markdown: String, start: Int, end: Int): Boolean {
        val edits = plan(markdown).edits
        val from = start.coerceAtLeast(0)
        val to = end.coerceAtMost(markdown.length)
        var seen = 0
        for (i in from until to) {
            if (isHidden(edits, i)) continue
            // Counted no further than it takes to answer: a deletion of half a note would otherwise
            // walk every character of it to say what its second one already said.
            if (++seen > 1) return true
        }
        return false
    }

    /**
     * `[start, end)` pulled in to the span its visible characters occupy, or null if it holds none.
     *
     * What a range means once it is granted that the reader aimed it: everything they can see in it,
     * and whatever invisible syntax stands *between* the first and the last of that — but nothing
     * hanging off either end. A selection dragged to just past a bold word comes back in source
     * coordinates with the closing `**` on it, because a caret against a hidden run maps to the whole
     * run; deleting the range as given takes markers the user never saw and unstyles the words left
     * behind. Syntax inside the span is theirs to lose — they selected across it.
     */
    fun visibleSpan(markdown: String, start: Int, end: Int): Pair<Int, Int>? {
        val edits = plan(markdown).edits
        val from = start.coerceAtLeast(0)
        val to = end.coerceAtMost(markdown.length)
        var first = -1
        var last = -1
        for (i in from until to) {
            if (isHidden(edits, i)) continue
            if (first < 0) first = i
            last = i
        }
        return if (first < 0) null else first to (last + 1)
    }

    /**
     * Whether anything in `[start, end)` is struck out of the rendering entirely.
     *
     * "Struck out" is narrower than "rewritten": a bullet's `- ` becomes a glyph and a checkbox's
     * `[ ]` becomes a blank, and both are on screen for the user to aim a key at. Only a run that
     * renders to nothing at all can be deleted without anything appearing to happen, and that is the
     * only kind a keystroke has to be protected from.
     */
    fun hidesAnythingIn(markdown: String, start: Int, end: Int): Boolean {
        val edits = plan(markdown).edits
        return (start.coerceAtLeast(0) until end.coerceAtMost(markdown.length)).any { isHidden(edits, it) }
    }

    /**
     * The last character in `[from, at)` the reader can see, or null when there is none.
     *
     * What a backspace pressed at [at] actually meant. A field deleting backwards across hidden
     * syntax cannot tell the syntax from the letter beside it and takes a run of both: backspacing
     * at the end of `` `text` `` deleted the letter, the closing backtick *and* the space after it,
     * so the span silently stopped being one. It also left the document two characters shorter than
     * the keyboard had accounted for, and a keyboard that has miscounted will index past the end of
     * the text on its very next command — which is the crash behind this.
     */
    fun lastVisibleBefore(markdown: String, from: Int, at: Int): Int? {
        val edits = plan(markdown).edits
        for (i in (at.coerceAtMost(markdown.length) - 1) downTo from.coerceAtLeast(0)) {
            if (!isHidden(edits, i)) return i
        }
        return null
    }

    /**
     * Where a caret dropped at [at] should really sit, or null when it is already somewhere the
     * user could have aimed at.
     *
     * A run that renders to nothing is still a run of offsets, and a caret can come to rest inside
     * one: a heading's `### ` is four characters wide on the way past and no width at all on
     * screen, so the strip of the line above it is a place a tap lands *in* the hashes. What the
     * user gets is a cursor drawn on the blank line above the heading, at the height of that blank
     * line — a stray mark beside their document, which is not a place anybody meant to put a caret
     * and from which every keystroke means something they cannot predict.
     *
     * Only the run a line *opens* with can strand a caret this way: anything visible in front of it
     * is something the user can aim at, and a caret beside that is one they placed. A line that is
     * markup from end to end — a closing fence, a `$$`, a rule — has nowhere better to put one, and
     * moving it off the line would take it out of the block it belongs to.
     */
    fun visibleCaret(markdown: String, at: Int): Int? {
        if (at !in 0..markdown.length) return null
        val lines = Lines(markdown)
        val k = lines.lineOf(at)
        val edits = plan(markdown).edits
        if ((lines.start(k) until at).any { !isHidden(edits, it) }) return null
        var to = at
        while (to < lines.end(k) && isHidden(edits, to)) to++
        return to.takeIf { it != at && to < lines.end(k) }
    }

    /**
     * The inline spans whose body [at] falls inside, outermost first.
     *
     * A link is not among them, deliberately: its target is written once and reopening one would
     * mean two copies of a URL the reader cannot see. Everything else — emphasis, code, maths, a
     * size — is a pair of markers round a run of text, and putting a second pair round the rest of
     * that run says exactly what the first one said.
     */
    fun openInlineAt(markdown: String, at: Int): List<MdInline> =
        plan(markdown).inline.filter { at >= it.openEnd && at <= it.closeStart }

    /** Whether the character at [at] is inside a run the plan leaves nothing on screen for. */
    private fun isHidden(edits: List<MdEdit>, at: Int): Boolean =
        edits.any { it.hides && at >= it.start && at < it.end }

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
     * Puts a blank line at [anchor] to hold whatever starts there off what came before it.
     *
     * A real inserted newline, kept short by the style on it. The alternative — leaning on the
     * terminator of the line above, as this used to — cannot be aimed: the space it makes lands on
     * that terminator's own visual line, which is the last line of a paragraph that wrapped, so the
     * gap opened *inside* a long list item rather than after it. A line of its own always falls
     * between the two paragraphs, however either of them wraps.
     */
    private fun gap(
        lines: Lines,
        k: Int,
        style: MdStyle,
        edits: MutableList<MdEdit>,
        gaps: MutableList<MdStyleRange>,
    ) {
        val breakAbove = lines.end(k - 1)
        val start = lines.start(k)

        // Nothing is *inserted* here — the break above the line is rewritten into two, or the edit
        // that already swallowed it is made to leave one behind. Either way no offset gains a
        // second place to be, and that is the whole point.
        //
        // An insertion is a seam: the offset it was made at still stands on both sides of what went
        // in, and a text field asked to put a caret there has to pick a side. It picks the near one
        // — so the caret for the start of every paragraph was drawn on the blank line above it, at
        // that blank line's own few pixels of height. That is the stray little cursor that could be
        // tapped into above a heading, and the stub left behind by pressing Enter. Rewriting has no
        // seam: the offset in front of the break and the offset behind it are each one place and
        // nowhere else.
        val above = edits.lastOrNull()?.takeIf { breakAbove >= it.start && breakAbove < it.end }
        val at = if (above == null) {
            edits += MdEdit(breakAbove, start, "\n\n")
            transformedOffset(edits, breakAbove)
        } else {
            // The break has already gone with something else: a closing fence and a `$$` take their
            // own newline with them. So the blank line is made *out of* that edit rather than added
            // beside it — what was hiding the line is left hiding it and spelling the gap as well.
            // `hides` is carried over deliberately: it does not follow the replacement here, and a
            // fence that renders as a blank line is every bit as gone as one that renders as
            // nothing at all.
            edits[edits.lastIndex] = above.copy(replacement = above.replacement + "\n", hides = above.hides)
            transformedOffset(edits, above.start)
        }
        val made = edits.last().replacement.length

        // Recorded already mapped, which is only possible here: a gap's size belongs to the *last*
        // newline of whatever the break became, since a line takes the height of what ends it and
        // an earlier newline ends the line above instead. There is no source range that picks that
        // one character out, so there is nothing for [mapped] to work from. Every edit that could
        // move it is already in the list, so the offset worked out now is the offset it keeps.
        gaps += MdStyleRange(at + made - 1, at + made, style)
    }

    /** What every line of a drawn block belongs to, and the one unit that never absorbs a gap. */
    private const val BLOCK_UNIT = "block"

    /**
     * What a list item's unit begins with, whatever marker follows.
     *
     * Items of one list are still a list — they are spaced closer to each other than to anything
     * around them — but not one paragraph: a list of one-line items set solid reads as a block of
     * text rather than as a list of things.
     */
    private const val LIST_UNIT = "list "

    /**
     * What each line belongs to, for the sole purpose of deciding what goes above it.
     *
     * Two lines carrying the same unit are one paragraph and get no space between them; that is
     * what makes a list read as a list rather than as a stack of one-line paragraphs. Ordinary
     * lines each get a unit of their own, so no two of them ever match.
     */
    private fun units(lines: Lines): Array<String> {
        val out = Array(lines.count) { "" }

        var k = 0
        while (k < lines.count) {
            val line = lines.text(k)
            // The same order [plan] dispatches in, or a line would be spaced as one thing and
            // rendered as another — `- - -` is a rule, not the first item of a list.
            k = when {
                MarkdownParser.FENCE.matchEntire(line) != null -> {
                    var close = k + 1
                    while (close < lines.count && MarkdownParser.FENCE.matchEntire(lines.text(close)) == null) close++
                    block(out, k, close)
                }

                line.trim() == "$$" -> {
                    var close = k + 1
                    while (close < lines.count && lines.text(close).trim() != "$$") close++
                    block(out, k, close)
                }

                lines.startsTable(k) -> {
                    var last = k + 1
                    while (last + 1 < lines.count && lines.text(last + 1).contains('|')) last++
                    block(out, k, last)
                }

                MarkdownParser.RULE.matches(line) -> block(out, k, k)

                else -> {
                    // Keyed on the marker, because that is what CommonMark calls a list: `- a`
                    // followed by `1. b` is two lists, and two lists want a space between them.
                    out[k] = when {
                        MarkdownParser.TASK.matchEntire(line) != null ||
                            MarkdownParser.BULLET.matchEntire(line) != null ->
                            "$LIST_UNIT${line.trimStart().first()}"

                        MarkdownParser.ORDERED.matchEntire(line) != null -> "${LIST_UNIT}1"
                        MarkdownParser.QUOTE.matchEntire(line) != null -> "quote"
                        else -> "line $k"
                    }
                    k + 1
                }
            }
        }
        return out
    }

    /** Marks `[first, last]` as one block and returns the line after it. */
    private fun block(out: Array<String>, first: Int, last: Int): Int {
        val end = minOf(last, out.size - 1)
        for (k in first..end) out[k] = BLOCK_UNIT
        return end + 1
    }

    /**
     * Opens whatever space belongs above line [k].
     *
     * Nothing above the first line of the document: a blank line at the top of a note is not a
     * margin, it is a blank line.
     */
    private fun gapBefore(
        lines: Lines,
        units: Array<String>,
        k: Int,
        edits: MutableList<MdEdit>,
        gaps: MutableList<MdStyleRange>,
    ) {
        if (k == 0) return
        // A blank line the author left is already the space between the two things it stands
        // between. Opening another one above it and a third below it — which is what this used to
        // do, the author's own line being just another unit — put the best part of three empty
        // lines between two tables, and the same between two paragraphs.
        if (lines.text(k).isBlank()) {
            if (separates(lines, k)) spaceBlank(lines, units, k, edits, gaps)
            return
        }
        if (lines.text(k - 1).isBlank()) return

        val style = gapStyle(units[k - 1], units[k]) ?: return
        gap(lines, k, style, edits, gaps)
    }

    /**
     * How much space belongs between a [above] and a [here], or null where they want none.
     *
     * The same answer whether the space has to be made or was typed by the author, so that moving
     * a blank line in or out of a document does not change how far apart things are set.
     */
    private fun gapStyle(above: String, here: String): MdStyle? = when {
        above == BLOCK_UNIT || here == BLOCK_UNIT -> MdStyle.BLOCK_GAP
        above != here -> MdStyle.PARAGRAPH_GAP
        // The same unit twice over: two items of one list, or two lines of one quotation. A
        // quotation is prose and is set solid; a list is a column of separate things.
        here.startsWith(LIST_UNIT) -> MdStyle.LIST_GAP
        else -> null
    }

    /**
     * Whether line [k] is one blank line with something on either side of it — a line that is there
     * to *separate* rather than to be blank, which is the one Markdown asks an author for.
     *
     * A run of several is somebody holding text down the page on purpose, and a blank line at
     * either end of the document separates nothing. Both are left at their full height: they are
     * the author's own spacing, and nothing here has any business tightening them.
     */
    private fun separates(lines: Lines, k: Int): Boolean =
        k in 1 until lines.count - 1 &&
            lines.text(k).isBlank() &&
            !lines.text(k - 1).isBlank() &&
            !lines.text(k + 1).isBlank()

    /**
     * Sets the blank line [k] to the height of the space it stands for.
     *
     * Only between two drawn blocks, where the blank line is not spacing at all but syntax: a table
     * runs on until a line that is not one of its rows, so an author who wants two of them has no
     * choice about the line between — and no reason to be given a line of text's worth of air for
     * it. Everywhere else a blank line is a decision, and two paragraphs written a line apart go on
     * looking further apart than two written without one. Pressing Enter twice has to do something.
     *
     * The newline that *ends* the line is what its height hangs on, the same rule [gap] works by —
     * but this one is a character of the source rather than one the plan made up, so there is a
     * range to map and no seam to reason about. Recorded with the gaps rather than the styles so
     * that nothing drawn on the line above can override it.
     */
    private fun spaceBlank(
        lines: Lines,
        units: Array<String>,
        k: Int,
        edits: MutableList<MdEdit>,
        gaps: MutableList<MdStyleRange>,
    ) {
        if (gapStyle(units[k - 1], units[k + 1]) != MdStyle.BLOCK_GAP) return
        val at = opens(edits, lines.end(k))
        gaps += MdStyleRange(at, at + 1, MdStyle.BLOCK_GAP)
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
        MdStyleRange(opens(edits, start), closes(edits, end), style, arg)

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
            // The source span is left alone: it is how a tap on a border finds the table in the
            // document it has to rewrite, and the document is not what is on screen.
        )

        is MdRule -> MdRule(closes(edits, offset))

        is MdQuote -> copy(start = closes(edits, start), end = closes(edits, end))

        // `sourceMark` is deliberately left alone: it names a character in the document, not on the
        // screen, and mapping it would point the toggle at whatever the renderer put there instead.
        is MdTask -> copy(offset = closes(edits, offset))
    }

    // ---- Lines -------------------------------------------------------------------------------

    /** The document split into lines once, so no pass has to go looking for newlines again. */
    internal class Lines(val source: String) {
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

        /** The line [offset] falls on. A newline belongs to the line it ends, not the one it opens. */
        fun lineOf(offset: Int): Int {
            var low = 0
            var high = count - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (starts[mid] <= offset) low = mid else high = mid - 1
            }
            return low
        }
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

        val languageStart = if (language.isEmpty()) {
            lines.end(open)
        } else {
            lines.end(open) - lines.text(open).substringAfter("```").trimStart().length
        }

        // The backticks become the indent that holds the code off the box drawn round it.
        edits += MdEdit(lines.start(open), languageStart, CODE_INDENT)
        if (language.isNotEmpty()) {
            val languageEnd = languageStart + language.length
            // Trailing whitespace after the word goes too, or the header chip is drawn round a
            // word plus however many spaces happened to follow it.
            if (languageEnd < lines.end(open)) edits += MdEdit(languageEnd, lines.end(open), "")
            styles += MdStyleRange(languageStart, languageEnd, MdStyle.FENCE_HEADER)
        }

        leading(lines, open, styles, MdStyle.LEADING_TIGHT)
        for (k in open + 1..lastBody) {
            edits += MdEdit(lines.start(k), lines.start(k), CODE_INDENT)
            // The range opens before that insertion, so the indent is set in the block's own face
            // and comes out the same width on every line whatever the line starts with.
            styles += MdStyleRange(lines.start(k), lines.end(k), MdStyle.FENCE)
            leading(lines, k, styles, MdStyle.LEADING_TIGHT)
        }

        // Tokens after the block styles, so their colours win where the two overlap.
        if (hasBody) highlight(source, language, lines.start(open + 1), lines.end(lastBody), styles)

        if (close < lines.count) edits += MdEdit(lines.start(close), lines.endInclusive(close), "")

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
            leading(lines, k, styles, MdStyle.LEADING_TIGHT)
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
     * table survives, and the delimiter row is hidden outright. Real lines are drawn down the left
     * edge of those spaces and under the header, from [MdTable].
     *
     * The box therefore sits exactly on the column the document is set in — the blank a row's own
     * opening pipe left behind is what holds the first cell off it. A code block is indented to
     * match, by one column of the same monospace face; see `CODE_INDENT`.
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
                widths[column] = maxOf(widths[column], renderedWidth(source, cell))
            }
        }
        // A column is its widest cell plus one blank column. Every cell's own leading blanks are
        // struck out, so each starts exactly one column in from the rule to its left, and the
        // trailing blank leaves the widest of them exactly one column short of the rule to its
        // right. Padding is only ever added, never taken away, so nothing is squeezed by this.
        for (column in widths.indices) {
            widths[column] = if (widths[column] > MAX_CELL_WIDTH) 0 else widths[column] + CELL_PAD
        }

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
            // Rows were the one kind of line with no leading at all, which made a cell exactly as
            // tall as its text: no padding above or below it, and no easier to hit than the rule
            // drawn across the top of it.
            leading(lines, k, styles, MdStyle.LEADING_CELL)
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
                    padCell(source, cell, widths.getOrElse(column) { renderedWidth(source, cell) }, edits)
                    pipe = cell.last + 1
                    column++
                    continue
                }
                pipe++
            }
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
            sourceStart = lines.start(first),
            sourceEnd = lines.end(last),
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
     * Renders one cell [target] characters wide: its own blanks struck off both ends, the shortfall
     * added back after the text.
     *
     * Whatever a cell was written with — `| a |`, `|a|`, `|   a  |` — reads the same, which is what
     * lets the rules be drawn a fixed distance from the text rather than at whatever distance the
     * author happened to type. One blank column is what is left on each side, and one column of the
     * table's monospace face is about eight dp. It also leaves nowhere inside a cell for a caret to
     * hide: there is no offset past the end of the text that is on screen to be tapped.
     *
     * The shortfall is *added* rather than substituted, which is the difference between a cell that
     * behaves like a field and one that does not. Compose maps a caret sitting in a replaced run to
     * the far end of the replacement, so a cell padded by replacement drew its cursor hard against
     * the right border with no offset in the document meaning "here, at the start". Blanks struck
     * out have the opposite pull — every offset inside them maps to where the text is — so both
     * ends of a cell hold a caret exactly where it was put.
     */
    private fun padCell(source: String, cell: IntRange, target: Int, edits: MutableList<MdEdit>) {
        val text = textIn(source, cell)
        if (text.first > cell.first) edits += MdEdit(cell.first, text.first, "")
        if (text.last + 1 <= cell.last) edits += MdEdit(text.last + 1, cell.last + 1, "")

        val fill = target - (text.last + 1 - text.first)
        if (fill > 0) edits += MdEdit(cell.last + 1, cell.last + 1, pad(fill))
    }

    /**
     * The part of a cell that stays on screen: everything but the blanks at either end.
     *
     * A cell of nothing but blanks keeps one of them. A run struck out whole has no offset inside
     * it for a caret to rest at, and the inside of an empty cell is exactly where a caret goes.
     */
    private fun textIn(source: String, cell: IntRange): IntRange {
        val to = cell.last + 1
        var from = cell.first
        while (from < to && source[from] == ' ') from++
        if (from >= to) return cell.first..cell.first.coerceAtMost(cell.last)
        var end = to
        while (end > from && source[end - 1] == ' ') end--
        return from..(end - 1)
    }

    /** How wide a cell ends up on screen before its column is squared off. */
    private fun renderedWidth(source: String, cell: IntRange): Int {
        if (cell.isEmpty()) return 0
        val text = textIn(source, cell)
        return text.last + 1 - text.first
    }

    private fun pad(n: Int): String = if (n <= 0) "" else " ".repeat(n)

    // ---- Block level -------------------------------------------------------------------------

    private fun scanLine(
        lines: Lines,
        k: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        decorations: MutableList<MdDecoration>,
        spans: MutableList<MdInline>,
    ) {
        val text = lines.source
        val start = lines.start(k)
        val end = lines.end(k)
        val line = lines.text(k)
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
            scanInline(text, textStart, end, edits, styles, spans)
            return
        }

        // Tasks before bullets: a task item is a bullet whose text happens to start with `[ ]`.
        MarkdownParser.TASK.matchEntire(line)?.let { match ->
            val markerStart = indentTo(lines, k, match.groupValues[1], edits, styles)
            val textStart = end - match.groupValues[3].length
            val done = match.groupValues[2].equals("x", ignoreCase = true)
            // The whole marker becomes blank space, and a real checkbox is put over it. A glyph
            // would be the easy answer and cannot be ticked: the user asked for a control.
            edits += MdEdit(markerStart, textStart, TASK_BLANK)
            styles += MdStyleRange(markerStart, textStart, MdStyle.TASK_BOX)
            decorations += MdTask(markerStart, done, start + match.groups[2]!!.range.first)
            val body = planHeading(text, textStart, end, edits, styles)
            if (done) styles += MdStyleRange(body, end, MdStyle.STRIKE)
            scanInline(text, body, end, edits, styles, spans)
            return
        }

        MarkdownParser.BULLET.matchEntire(line)?.let { match ->
            val markerStart = indentTo(lines, k, match.groupValues[1], edits, styles)
            val textStart = end - match.groupValues[2].length
            edits += MdEdit(markerStart, textStart, BULLET_GLYPH)
            styles += MdStyleRange(markerStart, textStart, MdStyle.MARKER)
            scanInline(text, planHeading(text, textStart, end, edits, styles), end, edits, styles, spans)
            return
        }

        // The number is not syntax to be hidden — it is what the reader is meant to see — so it is
        // only tinted, never removed.
        MarkdownParser.ORDERED.matchEntire(line)?.let { match ->
            val markerStart = indentTo(lines, k, match.groupValues[1], edits, styles)
            val textStart = end - match.groupValues[3].length
            styles += MdStyleRange(markerStart, textStart, MdStyle.MARKER)
            scanInline(text, planHeading(text, textStart, end, edits, styles), end, edits, styles, spans)
            return
        }

        MarkdownParser.QUOTE.matchEntire(line)?.let { match ->
            val textStart = end - match.groupValues[1].length
            // The marker goes without leaving anything in its place: the indent that clears the
            // bar is [MarkdownRenderPlan.indents]' to state, once for the whole quotation, so that
            // the lines it wraps onto are held clear of the bar as well as the lines it is typed
            // on. Three spaces written here in its place indented neither.
            edits += MdEdit(start, textStart, "")
            styles += MdStyleRange(textStart, end, MdStyle.QUOTE)
            openQuote(lines, k, decorations)
            scanInline(text, textStart, end, edits, styles, spans)
            return
        }

        scanInline(text, start, end, edits, styles, spans)
    }

    /**
     * Widens a list item's indent to something the eye can count, and returns where its marker is.
     *
     * Two source spaces per level is what the parser counts in and what other Markdown tools write,
     * and two proportional spaces on screen is a few pixels — a nested item looked all but flush
     * with its parent. The source keeps its two; the reader gets a monospace blank wide enough to
     * read as a step, which is the same trick a task item's checkbox blank uses.
     */
    private fun indentTo(
        lines: Lines,
        k: Int,
        indent: String,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ): Int {
        val start = lines.start(k)
        val markerStart = start + indent.length
        // A tab is one level wherever it appears; spaces come in pairs. An odd space left over is
        // someone's own spacing and is left exactly as they typed it.
        val depth = indent.sumOf { if (it == '\t') 2 else 1 } / 2
        if (depth == 0) return markerStart
        edits += MdEdit(start, markerStart, INDENT_BLANK.repeat(depth))
        styles += MdStyleRange(start, markerStart, MdStyle.INDENT)
        return markerStart
    }

    /**
     * Renders a heading that opens `[from, to)` — a list item's own heading — and returns where its
     * text starts.
     *
     * `- # Alpha` is a list item containing a heading, which is what CommonMark says it is. The
     * hashes are hidden exactly as they are on a heading of its own, so what the reader sees is a
     * bullet beside big text rather than a bullet beside a hash.
     */
    private fun planHeading(
        text: String,
        from: Int,
        to: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
    ): Int {
        val match = MarkdownParser.HEADING.matchEntire(text.substring(from, to)) ?: return from
        val textStart = to - match.groupValues[2].length
        edits += MdEdit(from, textStart, "")
        styles += MdStyleRange(textStart, to, headingStyle(match.groupValues[1].length))
        return textStart
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
        spans: MutableList<MdInline>,
    ) {
        var i = from
        while (i < to) {
            // Code first: nothing inside a code span is Markdown.
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close in (i + 1) until to) {
                    edits += MdEdit(i, i + 1, "")
                    spans += MdInline(i, i + 1, close, close + 1)
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
                    spans += MdInline(i, i + 1, close, close + 1)
                    styles += MdStyleRange(i + 1, close, MdStyle.MATH)
                    edits += MdEdit(close, close + 1, "")
                    i = close + 1
                    continue
                }
            }

            if (text[i] == '<') {
                val tagged = scanHtmlEmphasis(text, i, to, edits, styles, spans)
                if (tagged > 0) {
                    i = tagged
                    continue
                }
            }

            if (text.startsWith("![", i)) {
                val consumed = scanReference(text, i, to, edits, styles, spans, image = true)
                if (consumed > 0) {
                    i = consumed
                    continue
                }
            }

            if (text[i] == '[') {
                // Before the link scan, because both start with a bracket and only the *suffix*
                // tells them apart. The two cannot be confused once past it: `scanReference` wants
                // `](`, this wants `]{`, and each declines what the other is looking at.
                val attributed = scanAttrSpan(text, i, to, edits, styles, spans)
                if (attributed > 0) {
                    i = attributed
                    continue
                }

                val consumed = scanReference(text, i, to, edits, styles, spans, image = false)
                if (consumed > 0) {
                    i = consumed
                    continue
                }
            }

            val emphasis = scanEmphasis(text, i, to, edits, styles, spans)
            if (emphasis > 0) {
                i = emphasis
                continue
            }

            i++
        }
    }

    /**
     * The three emphases HTML can also say, which is how this app says them when asterisks cannot.
     *
     * Markdown's markers are ambiguous where two runs of the same symbol come to touch: `**` beside
     * `*` is one run of three, and a phrase carrying one of those at each end has no reading. Two
     * styles that *overlap* rather than nest always end that way, and there is no arrangement of
     * asterisks that avoids it — it is the syntax, not a shortcoming of the writer.
     *
     * These tags are the way out, and they are not an invention: raw HTML is part of CommonMark and
     * every reader renders them. `MarkdownWriter` reaches for them only where the markers cannot be
     * made to say the right thing, so an ordinary note has none in it.
     */
    private val HTML_EMPHASIS = listOf(
        "strong" to MdStyle.BOLD,
        "em" to MdStyle.ITALIC,
        "del" to MdStyle.STRIKE,
    )

    /** Handles `<em>text</em>`, returning the offset just past it, or 0 if that is not what is here. */
    private fun scanHtmlEmphasis(
        text: String,
        i: Int,
        to: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        spans: MutableList<MdInline>,
    ): Int {
        for ((tag, style) in HTML_EMPHASIS) {
            val open = "<$tag>"
            val close = "</$tag>"
            if (!text.startsWith(open, i)) continue
            val at = text.indexOf(close, i + open.length)
            // An empty pair styles nothing and would hide the tags that say so.
            if (at <= i + open.length || at + close.length > to) continue
            edits += MdEdit(i, i + open.length, "")
            spans += MdInline(i, i + open.length, at, at + close.length)
            styles += MdStyleRange(i + open.length, at, style)
            scanInline(text, i + open.length, at, edits, styles, spans)
            edits += MdEdit(at, at + close.length, "")
            return at + close.length
        }
        return 0
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
        spans: MutableList<MdInline>,
    ): Int {
        for (rule in EMPHASIS) {
            val length = rule.marker.length
            val symbol = rule.marker[0]
            if (!text.startsWith(rule.marker, i)) continue
            // Underscores inside a word are `snake_case`, not emphasis. Asterisks are: CommonMark
            // reads `test*2*` as an italic 2, and so does every other renderer this app's files
            // will be opened in, so a note that hid the difference would render two ways.
            if (symbol == '_' && isWordChar(text.getOrNull(i - 1))) continue
            // CommonMark's flanking rule, and the whole of it: emphasis opens only on a marker up
            // against the text it styles and closes only on one up against the end of it. It is
            // what keeps `2 * 3 * 4` arithmetic instead of italics — and it applies to every width
            // of marker, not just one. `** bold**` is bold in no other Markdown reader, so a note
            // written here that leant on it would open somewhere else with the asterisks on show;
            // showing them here too is what makes the file mean one thing.
            if (isBlank(text.getOrNull(runEndAt(text, i, symbol)))) continue

            var close = text.indexOf(rule.marker, i + length)
            while (close > i && !closesSpan(text, i, i + length, close, symbol, length)) {
                close = text.indexOf(rule.marker, close + 1)
            }
            // An empty span is left alone so a shorter marker gets its turn at the same characters.
            if (close <= i || close == i + length || close + length > to) continue

            // The closing run can be wider than this marker, and then not all of it is this span's:
            // `**test*2***` ends with three asterisks, and the first of them belongs to the italic
            // span opened inside the bold one. The outer span takes the outside of the run and
            // leaves the inside to whatever the body opened — take the wrong end and the two spans
            // interleave, which is one stray asterisk on screen and a word that is not italic.
            var runEnd = close + length
            while (runEnd < to && text[runEnd] == symbol) runEnd++
            val spare = runEnd - close - length
            if (spare > 0) close += minOf(spare, openedWithin(text, i + length, close, symbol))
            if (close + length > to) continue

            edits += MdEdit(i, i + length, "")
            // Before the recursion, so the spans come out outermost first.
            spans += MdInline(i, i + length, close, close + length)
            for (style in rule.styles) styles += MdStyleRange(i + length, close, style)
            scanInline(text, i + length, close, edits, styles, spans)
            edits += MdEdit(close, close + length, "")
            return close + length
        }
        return 0
    }

    /**
     * Handles `[text]{size=18 color=#c0392b}`, returning the offset just past it, or 0 if this is
     * not one.
     *
     * Pandoc's bracketed-span syntax, chosen over inventing something: it is a real convention, it
     * cannot collide with a link, it nests, and a Markdown tool that does not know it shows the
     * words with some punctuation around them rather than swallowing them.
     *
     * One span carries *every* attribute it was given, which is the whole reason the braces hold a
     * list rather than a single key. Two spans wrapped round the same words — `[[a]{size=18}]{...}`
     * — would say the same thing, and would be four more characters for every edit to step over and
     * one more level for a later split to take apart. See [attrSuffixAt].
     *
     * Brackets are counted rather than searched for, so the closing one is the one that matches the
     * opening one. `[a [b]{size=12} c]{size=24}` is a span inside a span; taking the first `]{`
     * would end the outer span in the middle of the inner one and style half a sentence.
     */
    private fun scanAttrSpan(
        text: String,
        i: Int,
        to: Int,
        edits: MutableList<MdEdit>,
        styles: MutableList<MdStyleRange>,
        spans: MutableList<MdInline>,
    ): Int {
        var depth = 0
        var k = i
        while (k < to) {
            when (text[k]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val suffix = attrSuffixAt(text, k + 1, to) ?: return 0
                        // An empty span would style nothing and hide the braces for no reason.
                        if (k == i + 1) return 0
                        edits += MdEdit(i, i + 1, "")
                        spans += MdInline(i, i + 1, k, suffix.end)
                        suffix.attrs.size?.let { styles += MdStyleRange(i + 1, k, MdStyle.SIZE, it) }
                        suffix.attrs.color?.let { styles += MdStyleRange(i + 1, k, MdStyle.COLOR, it) }
                        scanInline(text, i + 1, k, edits, styles, spans)
                        edits += MdEdit(k, suffix.end, "")
                        return suffix.end
                    }
                }
            }
            k++
        }
        return 0
    }

    /**
     * The attribute braces at [at]: what they say and where they end, or null if that is not what
     * stands there.
     *
     * Read character by character rather than with a pattern, and `internal` so that the formatting
     * bar reads it the same way. One definition of the syntax, so the thing that *renders* a span
     * and the thing that *sets* one cannot come to disagree about what one looks like — and no
     * second regex to get subtly wrong. (The first one was: a literal `}` unescaped, which the JVM
     * accepts and Android's ICU engine refuses, so every unit test passed and the app died on the
     * first formatting bar it drew.)
     *
     * Strict about the whole of the braces, not just the part it understands. An attribute list
     * carrying anything this does not know is refused outright and stays on screen as the text it
     * is, because hiding braces whose meaning is only half read is how a note quietly loses the
     * half nobody parsed — and a user who mistyped `{sixe=18}` is owed the sight of their mistake.
     */
    /**
     * The `[body]{…}` beginning at [at], or null if what stands there is not one.
     *
     * Brackets are counted rather than searched for, so the closing one is the one that matches the
     * opening one: `[a [b]{size=12} c]{size=24}` is a span inside a span, and taking the first `]{`
     * would end the outer span in the middle of the inner one.
     *
     * The opening bracket has to be the first thing at [at]. Without that the count starts at
     * whatever is there and finds the *next* span along instead.
     */
    internal fun attrSpanAt(text: String, at: Int, to: Int): MdAttrSpan? {
        if (at < 0 || at >= to || text[at] != '[') return null
        var depth = 0
        var k = at
        while (k < to) {
            when (text[k]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val suffix = attrSuffixAt(text, k + 1, to) ?: return null
                        return MdAttrSpan(at, at + 1, k, suffix.end, suffix.attrs)
                    }
                }
            }
            k++
        }
        return null
    }

    internal fun attrSuffixAt(text: String, at: Int, to: Int): MdAttrSuffix? {
        if (at >= to || text[at] != '{') return null
        var k = at + 1
        var size: Int? = null
        var color: Int? = null
        while (k < to && text[k] != '}') {
            if (text[k] == ' ') {
                k++
                continue
            }
            val equals = text.indexOf('=', k)
            if (equals < 0 || equals >= to) return null
            val key = text.substring(k, equals)
            var end = equals + 1
            while (end < to && text[end] != ' ' && text[end] != '}') end++
            val value = text.substring(equals + 1, end)
            // A key seen twice is refused along with a key never heard of: `{size=12 size=18}` has
            // no answer, and picking one of them silently is worse than showing the braces and
            // letting the author see what they wrote.
            when (key) {
                "size" -> size = if (size == null) sizeValue(value) ?: return null else return null
                "color" -> color = if (color == null) colorValue(value) ?: return null else return null
                else -> return null
            }
            k = end
        }
        // An empty pair says nothing, and a run of it says nothing twice.
        if (k >= to || (size == null && color == null)) return null
        return MdAttrSuffix(MdAttrs(size = size, color = color), k + 1)
    }

    /**
     * A size in sp, or null where the digits are not one.
     *
     * Bounded at both ends because the tag is text a user can type by hand, and neither `{size=0}`
     * nor `{size=99999}` is a document anyone meant to write — one is invisible and the other is a
     * single letter filling the page, and both are easier to create by accident than to undo.
     */
    private fun sizeValue(value: String): Int? {
        if (value.isEmpty() || value.any { !it.isDigit() }) return null
        // Long enough to overflow is not a number to try to make sense of.
        if (value.length > 4) return null
        return value.toInt().takeIf { it in MIN_SIZE_SP..MAX_SIZE_SP }
    }

    /**
     * A colour, as `#rgb`, `#rrggbb` or `#aarrggbb`.
     *
     * The `#` is required, unlike [ColorCodec.parse], which is lenient because it backs a field
     * somebody is typing into. Here the string is already written down, and a bare `ff0000` in the
     * braces is far more likely to be a word than a colour.
     */
    private fun colorValue(value: String): Int? {
        if (!value.startsWith('#') || value.length !in setOf(4, 7, 9)) return null
        return ColorCodec.parse(value)
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
        spans: MutableList<MdInline>,
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
        if (!image) scanInline(text, i + 1, close, edits, styles, spans)
        edits += MdEdit(close, end + 1, "")
        return end + 1
    }

    private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

    /** The end of the text counts as blank: there is nothing there for a marker to be against. */
    private fun isBlank(c: Char?): Boolean = c == null || c.isWhitespace()

    /**
     * Where the run of [symbol] through [at] ends, and where it starts.
     *
     * Flanking is a property of the whole delimiter run, not of the one character next to the
     * marker being tried. Judging it a character at a time let the second `*` of `**bold **` close
     * the first, because what stood next to it was the *other* asterisk of its own run rather than
     * the space in front of both — so a pair that closes nothing rendered as an italic anyway.
     */
    private fun runEndAt(text: String, at: Int, symbol: Char): Int {
        var i = at
        while (i < text.length && text[i] == symbol) i++
        return i
    }

    private fun runStartAt(text: String, at: Int, symbol: Char): Int {
        var i = at
        while (i > 0 && text[i - 1] == symbol) i--
        return i
    }

    /**
     * Whether the run of [symbol] through [close] is the one that ends a span opened at
     * [openStart], whose body starts at [bodyStart] and whose marker is [length] wide.
     *
     * Three ways a run of the right characters is still not this span's closer, and every one of
     * them used to leave markers standing in the formatted view:
     *
     * 1. **It is not against the text it would close.** CommonMark's flanking rule, read over the
     *    whole delimiter run rather than the one character beside the marker being tried — `**bold
     *    **` closes nothing, and judging it a character at a time let the second asterisk of the
     *    pair close the first.
     * 2. **The body left more open than this run can close.** Markers pair off innermost first, so
     *    what the body opened comes off the front of this run and only what is left over can end
     *    this span. `*a **b** c*` closes the bold on the run after "b" and the italic on the last
     *    asterisk of the line; reading the bold's closer as the italic's cut the span in half and
     *    put the rest of it on screen.
     * 3. **CommonMark's rule of three.** Where either run could go both ways — text on both sides
     *    of it — a pairing whose two widths add to a multiple of three is refused, unless both are
     *    multiples of three themselves. It is the rule that decides which side of `*a**b***` the
     *    middle pair belongs to: 1 + 2 is three, so the `**` opens the bold rather than closing the
     *    italic, and the run at the end is read as the three closers it is. Taking it the other way
     *    stranded the last two asterisks in plain view, which is what an italic word with something
     *    bold at the end of it used to look like.
     */
    private fun closesSpan(
        text: String,
        openStart: Int,
        bodyStart: Int,
        close: Int,
        symbol: Char,
        length: Int,
    ): Boolean {
        val runStart = runStartAt(text, close, symbol)
        val runEnd = runEndAt(text, close, symbol)
        if (isBlank(text.getOrNull(runStart - 1))) return false

        val width = runEnd - runStart
        if (width < openedWithin(text, bodyStart, runStart, symbol) + length) return false

        val opener = runEndAt(text, openStart, symbol) - openStart
        val eitherWay = !isBlank(text.getOrNull(openStart - 1)) || !isBlank(text.getOrNull(runEnd))
        if (!eitherWay) return true
        return (opener + width) % 3 != 0 || (opener % 3 == 0 && width % 3 == 0)
    }

    /**
     * How wide a run of [symbol] `[from, to)` has left open — what a run after it has to close.
     *
     * Runs are paired off as they are met, which is all [scanEmphasis] needs to know: whether the
     * span it is closing has to give the front of its closing run to something inside it.
     */
    private fun openedWithin(text: String, from: Int, to: Int, symbol: Char): Int {
        var open = 0
        var k = from
        while (k < to) {
            if (text[k] != symbol) {
                k++
                continue
            }
            var end = k
            while (end < to && text[end] == symbol) end++
            // Only a run that could actually be one counts, by the same flanking rule the scan
            // itself goes by. A run with a space on both sides of it — the middle pair of
            // `**a ** b**` — opens nothing and closes nothing, and counting it as an open left the
            // run at the end of the line looking too narrow to close anything either.
            open = when {
                open != 0 && !isBlank(text.getOrNull(k - 1)) -> 0
                open == 0 && !isBlank(text.getOrNull(end)) -> end - k
                else -> open
            }
            k = end
        }
        return open
    }
}
