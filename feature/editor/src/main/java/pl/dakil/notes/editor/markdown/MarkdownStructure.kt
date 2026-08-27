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
 * A line break taken through the middle of inline markup.
 *
 * [edit] rewrites the run so both halves stand on their own, and [carry] is what could not be
 * written down: the styles of a half with no text in it yet. Those are armed at the new caret
 * instead — the same bargain the formatting bar makes when it is pressed with nothing selected.
 * See [PendingStyles].
 */
data class MdLineBreak(val edit: MdEditAt, val carry: List<MdPending>)

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
     * The one thing a backspace pressed at [caret] was aimed at, or null when there is nothing here
     * this can work out better than the field already has.
     *
     * A field cannot count in what the reader sees. Standing at the start of `### Heading`, with the
     * hashes hidden, the keyboard asks for a deletion that runs from the line above to four
     * characters *into* the word — one press, the heading demoted and `Head` gone with it. So the
     * question is answered here instead, from the caret alone: the last character in front of it
     * that is actually on screen is the one that goes, and nothing else does.
     *
     * The search stops at the break that opens the caret's line. Further back is another block,
     * whose own line ends are hidden too, and what a backspace means at *that* edge is
     * [backspaceAt]'s question rather than this one.
     */
    fun backspaceTarget(source: String, caret: Int): MdEditAt? {
        if (caret !in 1..source.length) return null
        val lines = MarkdownRenderer.Lines(source)
        val k = lines.lineOf(caret)
        val start = lines.start(k)
        val at = MarkdownRenderer.lastVisibleBefore(source, start - 1, caret)
        if (at == null) {
            // The top of the document, with nothing in front of the caret but markup — a note that
            // opens with a heading, and a caret in front of its first word. There is nothing there
            // to delete. Taking the markup instead would be a key that removes no text and restyles
            // text it never touched, so the key does nothing at all, which is what it does at the
            // start of any other document.
            if (k == 0) return MdEditAt(caret, caret, "", caret)
            // Otherwise the break above is hidden too, which means a block is up there and what a
            // backspace means at its edge is [backspaceAt]'s question rather than this one.
            return null
        }
        // Something on the caret's own line: that character, and only that character.
        if (at >= start) return MdEditAt(at, at + 1, "", at)

        // Otherwise everything in front of the caret on this line is hidden, and what the reader
        // can see behind them is the line break itself. Deleting it joins the two lines, which is
        // what a backspace at the start of a line has always meant.
        val heading = MarkdownParser.HEADING.matchEntire(lines.text(k))
        // A heading's hashes are markup only while they stand at the start of a line. Carried into
        // the middle of one by the join they turn back into text, so the user would see `###`
        // appear in their document out of a key that deletes. They go with it — which is also what
        // a word processor does with the style of a paragraph merged into the one above.
        val join = if (heading != null && k > 0 && lines.text(k - 1).isNotEmpty()) {
            lines.end(k) - heading.groupValues[2].length
        } else {
            start
        }
        return MdEditAt(at, join, "", at)
    }

    /**
     * The emphasis markers left holding nothing once [at] had its text deleted out of it, or null.
     *
     * Bold is a pair of markers with a word between them, and deleting the word leaves the pair.
     * They are hidden while they have something to style and are nothing of the kind once they do
     * not: `****` is not an empty bold run to the parser, it is four asterisks, so what the user
     * gets for deleting their own word is four characters they never typed appearing in its place.
     * Styling only exists to be worn by text, so when the last of the text goes, it goes too.
     *
     * [before] is the document as it was, and is what says whether these characters were styling at
     * all: markers the renderer had hidden were doing that job, and a pair of asterisks it left on
     * screen was always just a pair of asterisks and is the user's to keep.
     */
    fun strandedMarkers(before: String, after: String, at: Int): MdEditAt? {
        for (marker in MARKERS) {
            val open = at - marker.length
            if (open < 0 || at + marker.length > after.length) continue
            if (!after.startsWith(marker, open) || !after.startsWith(marker, at)) continue
            if (!MarkdownRenderer.hidesAnythingIn(before, open, at)) continue
            return MdEditAt(open, at + marker.length, "", open)
        }
        return null
    }

    /**
     * Longest first, for the same reason the renderer scans them that way: `**` matched inside
     * `***` would take two of the three and leave the last one standing on its own.
     */
    private val MARKERS = listOf("***", "___", "**", "__", "~~", "*", "_", "`", "$")

    /**
     * How to break the line at [at] without taking the formatting apart, or null when there is no
     * formatting there to take apart.
     *
     * Inline markup cannot cross a line break — `**bold` on one line and `text**` on the next is
     * not bold, it is four asterisks and two plain lines — so Enter pressed in the middle of a run
     * used to undo styling the user had no way to see was at stake. A word processor answers this
     * by ending the run at the break and starting an identical one after it, and so does this: the
     * markers open at [at] are closed before the newline, in the order that closes the innermost
     * first, and opened again after it.
     *
     * A half with nothing in it gets no markers at all rather than an empty pair — `****` styles
     * nothing and hides nothing — so pressing Enter at the end of a bold word leaves the word bold
     * and the new line bare, with the boldness [carried][MdLineBreak.carry] on the caret instead.
     *
     * [separator] is the break as the field would have written it, list marker and all, so that
     * this and [ContinueList] can both have their way: the marker starts the new line and the
     * reopened styling follows it.
     */
    fun lineBreak(source: String, at: Int, separator: String): MdLineBreak? {
        val split = splitRuns(source, at, at, separator) ?: return null
        return MdLineBreak(
            edit = split.edit,
            carry = split.open.filterIndexed { i, _ -> split.tailEmpty[i] }.mapNotNull { carried(source, it) },
        )
    }

    /**
     * How to leave `[start, end)` standing *outside* the runs that enclose it.
     *
     * The other thing a split is for. [lineBreak] answers "the user pressed Enter in the middle of
     * a bold word"; this answers "the user turned bold off and typed" — and it is the same surgery,
     * because a style is ended in Markdown by closing its run and opening a fresh one after the
     * text that is not to wear it. Wrapping the typed characters where they stand is right in the
     * *middle* of a run and wrong at either edge, where the half it cuts off has no text in it and
     * the empty pair left behind is not markup at all: `**Apple**` typed into at its closing marker
     * gave `**Apple**s****`, four asterisks the user could suddenly see.
     *
     * [middle] is what goes between the two halves, markers and all — this puts it there and does
     * not ask what it says. [middleCaret] is an offset within it, and comes back absolute.
     *
     * Null when there is nothing open here, when [start] and [end] do not stand inside the same
     * runs — the typed text has changed the parse, which is what typing a `*` does — or when
     * neither half has anything to style.
     */
    fun splitOpenRuns(source: String, start: Int, end: Int, middle: String, middleCaret: Int): MdEditAt? {
        val split = splitRuns(source, start, end, middle) ?: return null
        return split.edit.copy(caret = split.middleStart + middleCaret)
    }

    /** The rebuilt text round a split, and the landmarks its two callers pick different carets from. */
    private class MdSplit(
        val edit: MdEditAt,
        /** Where [splitRuns]' `middle` begins, in the document the edit will have made. */
        val middleStart: Int,
        /** Per open span, whether the half after the split had nothing for it to style. */
        val tailEmpty: List<Boolean>,
        val open: List<MdInline>,
    )

    /**
     * Closes every run open at [start] before `[start, end)`, and opens it again afterwards.
     *
     * Inline markup cannot cross a line break and an empty pair of markers is not markup, so both
     * of the things this serves come down to the same rewrite: end the runs, put something between
     * them, begin them again. A half with nothing in it gets no markers at all rather than an empty
     * pair — which is what makes Enter at the end of a bold word leave the word bold and the new
     * line bare, and what makes bold turned off at the end of a run leave no `****` behind.
     *
     * The whole of the outermost span is rewritten, because that is the smallest range whose
     * markers all move.
     */
    private fun splitRuns(source: String, start: Int, end: Int, middle: String): MdSplit? {
        if (start !in 0..source.length || end !in start..source.length) return null
        val open = MarkdownRenderer.openInlineAt(source, start)
        if (open.isEmpty()) return null
        // Both ends have to stand in the same runs, or what lies between them is not one run's body
        // and there is no pair of halves here to make.
        if (end != start && MarkdownRenderer.openInlineAt(source, end) != open) return null

        // A span with nothing but other spans' markers on one side of the split has no text there
        // to style, so its markers do not belong on that side.
        val before = open.map { nothingToStyle(source, it.openEnd, start, open) }
        val after = open.map { nothingToStyle(source, end, it.closeStart, open) }
        if (before.all { it } && after.all { it }) return null

        val outer = open.first()
        // Where the markers actually go, which is never against a space. A closing run walks back
        // over the whitespace at the end of the half it closes and an opening run walks forward
        // over the whitespace at the start of the half it opens: `**apple is red**` split after
        // "apple" is `**apple**x **is red**`, not `**apple**x** is red**`, whose second pair opens
        // on a space and is therefore not a pair at all. See [MarkdownRenderer.scanEmphasis].
        var headEnd = start
        if (open.indices.any { !before[it] && slidesOffSpace(source, open[it]) }) {
            while (headEnd > outer.openEnd && source[headEnd - 1].isWhitespace()) headEnd--
        }
        var tailStart = end
        if (open.indices.any { !after[it] && slidesOffSpace(source, open[it]) }) {
            while (tailStart < outer.closeStart && source[tailStart].isWhitespace()) tailStart++
        }

        val out = StringBuilder()
        appendKeeping(out, source, outer.openStart, headEnd, open, before) { it.openStart to it.openEnd }
        for (i in open.indices.reversed()) {
            if (!before[i]) out.append(source, open[i].closeStart, open[i].closeEnd)
        }
        out.append(source, headEnd, start)
        val middleStart = outer.openStart + out.length
        out.append(middle)
        out.append(source, end, tailStart)
        for (i in open.indices) {
            if (!after[i]) out.append(source, open[i].openStart, open[i].openEnd)
        }
        val caret = outer.openStart + out.length
        appendKeeping(out, source, tailStart, outer.closeEnd, open, after) { it.closeStart to it.closeEnd }

        return MdSplit(
            edit = MdEditAt(outer.openStart, outer.closeEnd, out.toString(), caret),
            middleStart = middleStart,
            tailEmpty = after,
            open = open,
        )
    }

    /**
     * Whether [span]'s markers have to keep clear of whitespace.
     *
     * Emphasis does: CommonMark opens it only on a marker against the text it styles. A code span
     * and a `[…]{size=N}` do not — `` ` a ` `` is code with a space in it, and the size tag is this
     * app's own and brackets whatever it is given.
     */
    private fun slidesOffSpace(source: String, span: MdInline): Boolean =
        source[span.openStart] in "*_~"

    /**
     * Where [spaces] typed at [at] belong, when [at] is against a run's closing marker.
     *
     * Past it: `**test|**` given a space is `**test** ` and never `**test **`, which closes nothing.
     * Past *all* of it, where several runs end together — leaving the space between two closers
     * would only move the problem to the outer one.
     *
     * Null everywhere else, which is almost everywhere: a space in the middle of a run is an
     * ordinary space and is not this function's to move.
     */
    fun spaceOutsideRun(source: String, at: Int, spaces: String): MdEditAt? {
        if (at !in 0..source.length) return null
        val open = MarkdownRenderer.openInlineAt(source, at)
        if (open.none { it.closeStart == at && slidesOffSpace(source, it) }) return null

        var past = at
        while (past < source.length && source[past] in "*_~" &&
            MarkdownRenderer.hidesAnythingIn(source, past, past + 1)
        ) {
            past++
        }
        if (past == at) return null
        return MdEditAt(past, past, spaces, past + spaces.length)
    }

    /**
     * Where a run's closer belongs once a deletion has left it touching whitespace it did not touch
     * before.
     *
     * [spaceOutsideRun] moves a space typed against a closing marker to the far side of it, so a run
     * being finished stays closeable while its author is still typing. The same collision happens in
     * reverse under a backspace: deleting the last word out of `**Apple is red**` removes it one
     * visible character at a time, and the keystroke that takes the final letter off "red" lands on
     * the space that used to separate it from "is". What is left is `**Apple is **` — a closer
     * preceded by whitespace, which CommonMark will not read as a closer, so a run the user never
     * touched comes apart on the keystroke that only removed a word they meant to remove.
     *
     * [source] is the document as it stood *before* the deletion, with `[deletedStart, deletedEnd)`
     * the range about to go — the run has to be read there, still whole, because by the time the
     * deletion has happened its closer is no longer parsed as one and there is nothing left in the
     * broken text to recognise. The fix is [spaceOutsideRun]'s move made in reverse: the closer steps
     * back over the space instead of the space stepping over the closer, landing on `**Apple is** `,
     * exactly where a run finished with [spaceOutsideRun]'s help would already be.
     *
     * Null when the run would be left with nothing but whitespace to style — that is not a shorter
     * run, it is an empty one, and [strandedMarkers] is where an empty run is dealt with.
     */
    fun spaceOutsideRunAfterDeletion(source: String, deletedStart: Int, deletedEnd: Int): MdEditAt? {
        if (deletedStart !in 0..source.length || deletedEnd !in deletedStart..source.length) return null
        if (deletedStart == 0) return null
        val exposed = source[deletedStart - 1]
        if (!exposed.isWhitespace() || exposed == '\n') return null

        val open = MarkdownRenderer.openInlineAt(source, deletedEnd)
        if (open.none { it.closeStart == deletedEnd && slidesOffSpace(source, it) }) return null

        var wsStart = deletedStart
        while (wsStart > 0 && source[wsStart - 1].isWhitespace() && source[wsStart - 1] != '\n') wsStart--
        if (wsStart <= open.first().openEnd) return null

        var past = deletedEnd
        while (past < source.length && source[past] in "*_~" &&
            MarkdownRenderer.hidesAnythingIn(source, past, past + 1)
        ) {
            past++
        }
        if (past == deletedEnd) return null

        val closers = source.substring(deletedEnd, past)
        val spaces = source.substring(wsStart, deletedStart)
        return MdEditAt(wsStart, past, closers + spaces, wsStart + closers.length + spaces.length)
    }

    /**
     * [spaceOutsideRunAfterDeletion] at the other end of the run: where an *opener* is left touching
     * whitespace the deletion uncovered in front of it.
     *
     * Emphasis opens only on a marker up against the text it styles, so deleting the first word out
     * of `**This is my string**` gives `** my string**` — an opening pair against a space, which
     * CommonMark reads as two asterisks and not as markup at all. The whole run comes apart on a
     * keystroke that was meant to shorten it.
     *
     * The same move as its mirror, made the other way: the opener steps forward over the space
     * instead of the space stepping back over the opener, landing on ` **my string**` — the space
     * kept, the run intact, and the caret inside it ready to carry on.
     *
     * [source] is the document as it stood before the deletion, for the reason given there: once the
     * opener is against a space it is no longer parsed as an opener, and there is nothing left in the
     * broken text to recognise.
     */
    fun openerOutsideRunAfterDeletion(source: String, deletedStart: Int, deletedEnd: Int): MdEditAt? {
        if (deletedStart !in 0..source.length || deletedEnd !in deletedStart..source.length) return null
        if (deletedEnd >= source.length) return null
        val exposed = source[deletedEnd]
        if (!exposed.isWhitespace() || exposed == '\n') return null

        val open = MarkdownRenderer.openInlineAt(source, deletedStart)
        if (open.none { it.openEnd == deletedStart && slidesOffSpace(source, it) }) return null

        var wsEnd = deletedEnd
        while (wsEnd < source.length && source[wsEnd].isWhitespace() && source[wsEnd] != '\n') wsEnd++
        // Nothing but whitespace left for the run to style is an empty run, which is
        // [strandedMarkers]' case and not a marker to relocate.
        if (wsEnd >= open.first().closeStart) return null

        var back = deletedStart
        while (back > 0 && source[back - 1] in "*_~" &&
            MarkdownRenderer.hidesAnythingIn(source, back - 1, back)
        ) {
            back--
        }
        if (back == deletedStart) return null

        val openers = source.substring(back, deletedStart)
        val spaces = source.substring(deletedEnd, wsEnd)
        return MdEditAt(back, wsEnd, spaces + openers, back + spaces.length + openers.length)
    }

    /**
     * Where a caret standing at the end of an inline run really belongs, or null if it is already
     * there.
     *
     * A run's closing markers are not on screen, so the offset before them and the offset after
     * them are the *same place* to the reader — the end of the bold word. Which of the two the
     * field picks for a tap is nobody's decision: it falls out of how the tap mapped back through
     * the hidden run, and past the markers is a caret standing outside a run the user pointed at
     * the inside of. Typing there came out unstyled, and no amount of aiming could fix it, because
     * both answers look identical.
     *
     * So the one inside wins, which is also the rule every word processor follows: what is typed
     * wears what the character to the left of it wears. The way *out* of a run is the formatting
     * bar — press bold at the end of a bold word and the next thing typed is not bold, which is
     * [PendingStyles]' whole job — and not a caret nudged to a place that cannot be seen.
     *
     * Innermost first, by construction: pulling back past one closer lands the caret on the next
     * one in, and the walk repeats until it is against real text. `**a *b***` tapped at the end
     * comes to rest inside the italic, which is inside the bold.
     */
    fun runCaret(source: String, at: Int): Int? {
        if (at !in 0..source.length) return null
        val spans = MarkdownRenderer.plan(source).inline
        var caret = at
        while (true) {
            // Strictly inside the closing run or just past it. A caret already *at* `closeStart` is
            // where this is trying to get to, and stops the walk.
            val span = spans.firstOrNull { caret > it.closeStart && caret <= it.closeEnd } ?: break
            caret = span.closeStart
        }
        return caret.takeIf { it != at }
    }

    /**
     * The run of [marker] that ends just before [at], with nothing but whitespace since.
     *
     * What lets a style survive the space that ends a word. A space typed against a closing marker
     * is moved outside it — see [KeepSpaceOutside] — which leaves the caret past the end of a run
     * the user has not finished writing, and starting a second run for the next word would give
     * `**test** **more**` where they meant `**test more**`. So the closer comes back off and goes
     * on again after the new text instead.
     *
     * A line break is not whitespace for this purpose but a wall: inline markup cannot cross one,
     * so a run reopened over it is not a run at all. Enter at the end of a bold word carries the
     * boldness onto the next line as an armed style ([MdLineBreak.carry]) and the word typed there
     * gets markers of its own — carrying the *run* down instead put the closer on the line below
     * and unbolded everything between.
     */
    fun extendableRun(source: String, at: Int, marker: String): MdInline? {
        if (at !in 0..source.length) return null
        return MarkdownRenderer.plan(source).inline.lastOrNull { span ->
            span.closeEnd <= at &&
                source.substring(span.openStart, span.openEnd) == marker &&
                source.substring(span.closeEnd, at).let { it.isBlank() && '\n' !in it }
        }
    }

    /**
     * The styles in force at [at], outermost first — what the text there is already wearing.
     *
     * Read off the parse rather than off the marker runs beside the caret, because this decides a
     * rewrite of the document and only a parse knows which asterisks turned out to be markup.
     * [MarkdownActions.activeInlineMarkers], which lights the bar's buttons, is allowed to guess;
     * this is not.
     */
    fun stylesOpenAt(source: String, at: Int): List<MdPending> =
        MarkdownRenderer.openInlineAt(source, at).mapNotNull { carried(source, it) }

    /**
     * The same edit, pulled clear of any hidden markers it had reached over.
     *
     * A keyboard works on the text it can see, and the formatted view does not show a `**`. Asked
     * to correct "Aple" to "Apple" inside `**Aple**`, the IME names the four characters it knows
     * about; those offsets are mapped back into the source through the runs the renderer struck
     * out, and an offset that lands *on* a struck-out run maps to the whole of it — so the range
     * that comes back covers the markers as well, and replacing it takes the styling apart on a
     * keystroke that was only ever about a spelling.
     *
     * So: an edit may not consume hidden characters at its own edges. Both edges, because the
     * mapping widens at both. Null when there were none to begin with — the ordinary case, left
     * exactly alone — or when trimming leaves nothing, which means the edit covered no visible text
     * at all and there is no honest place to put [text].
     *
     * Nor may it leave one touching a space. Accepting a suggestion writes the word *and* the space
     * after it in one go, and putting that back between the markers gives `**Apple **`, where the
     * closing pair is against whitespace and is not a closing pair at all. So the markers go back
     * where they were and the space goes outside them, which is the same rule
     * [MarkdownActions.applyPending] follows and the same one it is broken for.
     */
    fun keepMarkers(source: String, start: Int, end: Int, text: String): MdEditAt? {
        val from = start.coerceIn(0, source.length)
        val to = end.coerceIn(from, source.length)

        // The one visible run the keyboard can have been aiming at: forward past any hidden syntax
        // the range opened on, then only as far as the first hidden character or line break after
        // that. Everything past that barrier is left where it is — not trimmed off the edges of the
        // range and put back, simply never touched. A correction is one word, and a range that
        // reaches over a closing marker and the newline behind it into the line below is not a
        // range anybody asked for.
        var head = from
        while (head < to && hides(source, head)) head++
        var tail = head
        while (tail < to && !hides(source, tail) && source[tail] != '\n') tail++
        if (head == from && tail == to) return null

        // Nothing visible in the range at all: the text goes in where it was aimed and not one
        // character of markup goes anywhere.
        if (head == tail) return MdEditAt(head, head, text, head + text.length)

        val body = text.trim()
        val lead = text.take(text.length - text.trimStart().length)
        val trail = text.takeLast(text.length - text.trimEnd().length)
        if (body.isEmpty()) return MdEditAt(head, tail, text, head + text.length)

        // A marker may not be left touching a space, and accepting a suggestion writes the space
        // along with the word — so where the corrected word is up against a hidden marker, the
        // space steps over it. `**Aple**` corrected to "Apple " is `**Apple** `, not `**Apple **`.
        var opener = head
        while (opener > 0 && lead.isNotEmpty() && hides(source, opener - 1)) opener--
        var closer = tail
        while (closer < source.length && trail.isNotEmpty() && hides(source, closer)) closer++

        val out = lead + source.substring(opener, head) + body + source.substring(tail, closer) + trail
        // Where the keyboard thinks the caret now is when it wrote a space; inside the markers,
        // ready to carry on in the same style, when it did not.
        val caret =
            if (trail.isEmpty()) opener + lead.length + (head - opener) + body.length
            else opener + out.length
        return MdEditAt(opener, closer, out, caret)
    }

    /** Whether the character at [at] is struck out of the rendering entirely. */
    private fun hides(source: String, at: Int): Boolean =
        MarkdownRenderer.hidesAnythingIn(source, at, at + 1)

    /**
     * Copies `[from, to)` into [out], leaving behind the markers [drop] says have nothing to style.
     *
     * [markerOf] picks which end of a span is at stake — its opener when this is the text before
     * the break, its closer when it is the text after.
     */
    private inline fun appendKeeping(
        out: StringBuilder,
        source: String,
        from: Int,
        to: Int,
        open: List<MdInline>,
        drop: List<Boolean>,
        markerOf: (MdInline) -> Pair<Int, Int>,
    ) {
        var i = from
        while (i < to) {
            val marker = open.indices
                .firstOrNull { drop[it] && markerOf(open[it]).first == i }
                ?.let { markerOf(open[it]) }
            if (marker == null) {
                out.append(source[i])
                i++
            } else {
                i = marker.second
            }
        }
    }

    /**
     * Whether `[from, to)` holds nothing the spans in [open] could style.
     *
     * Their own markers do not count, and neither does whitespace: a marker may not touch a space
     * in Markdown, so `** **` is two pairs of asterisks on screen for exactly the reason `****` is
     * one. A half that holds only those has nothing worth reopening a run around.
     */
    private fun nothingToStyle(source: String, from: Int, to: Int, open: List<MdInline>): Boolean {
        var i = from
        while (i < to) {
            if (source[i].isWhitespace()) {
                i++
                continue
            }
            val span = open.firstOrNull {
                i >= it.openStart && i < it.openEnd || i >= it.closeStart && i < it.closeEnd
            } ?: return false
            i = if (i < span.openEnd) span.openEnd else span.closeEnd
        }
        return true
    }

    /** The style [span] stands for, for a caret to carry onto a line that has no markers yet. */
    internal fun carried(source: String, span: MdInline): MdPending? {
        val opener = source.substring(span.openStart, span.openEnd)
        if (opener != "[") return MdPending.Wrap(opener)
        // A size is not a marker that can be doubled up, so it is carried as the size it is.
        val size = MarkdownRenderer.sizeSuffixAt(source, span.closeStart + 1, span.closeEnd)
        return size?.let { MdPending.Size(it.first) }
    }

    /**
     * Whether a block written at [at] would be read as more of the table already above it.
     *
     * A table ends at the first line that is not one of its rows, and a table row is any line with
     * a pipe in it — so a second table started straight underneath is not a second table at all.
     * Its header becomes two more rows of the first and its `| --- |` a row of dashes through the
     * middle. Only a blank line makes them two, which is why one is written in.
     */
    fun underTable(source: String, at: Int): Boolean {
        val caret = at.coerceIn(0, source.length)
        val lines = MarkdownRenderer.Lines(source)
        val k = lines.lineOf(caret)
        // A block goes on a line of its own: this one when the caret is at the head of it, the next
        // when there is text in the way. Either way it is the line above that has to be clear.
        val above = if (caret == lines.start(k)) k - 1 else k
        if (above < 0) return false
        return blocks(lines).any { it.kind == MdBlockKind.TABLE && above in it.firstLine..it.lastLine }
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
     * The start is the first character past the opening pipe, which is where a cell's text begins:
     * the renderer strikes out whatever blanks the author left at either end and draws the padding
     * instead. Counting the old leading space in as well put the caret of an empty cell a whole
     * column further in than its text would be — a cell that looked like it already held a space.
     */
    private fun cellRange(row: String, offset: Int): IntRange {
        val (open, close) = pipesAround(row, offset)
        return minOf(open + 1, close)..close
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
