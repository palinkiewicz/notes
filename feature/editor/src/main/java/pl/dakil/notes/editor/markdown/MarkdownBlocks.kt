package pl.dakil.notes.editor.markdown

/**
 * One piece of a document cut up for drawing: `[start, end)` of the source.
 *
 * A run of top-level lines with no blank line among them, and the blank lines that follow it. That
 * is the unit the `.md` screen lays out on its own — a note of any length is a list of these, and
 * only the one being typed in is a text field.
 */
data class MdItem(val start: Int, val end: Int)

/**
 * What `[start, end)` of a document looks like drawn on its own, read off the plan of the *whole*
 * document — which is what makes it look exactly as it does in place: a reference defined three
 * screens away still resolves, and a table's last row still has the leading its newline gives it.
 *
 * [text] starts with the newline in front of [start] when there is one ([lead] is then 1). That
 * character is drawn a hair tall, and it is there for the caret's sake: it is something to step
 * onto from the first line, which is how an arrow key or a backspace leaves the block upwards.
 * Every offset here is in [text]'s coordinates except the source ones decorations carry
 * (`sourceMark`, `sourceStart`…), which name characters of the document and stay absolute.
 */
class MdSlice(
    val start: Int,
    val end: Int,
    val lead: Int,
    /** Whether this runs to the end of the document, where the trailing blank line goes. */
    val isLast: Boolean,
    /** The plan's edits inside the slice, in the coordinates of `source.substring(start - lead, end)`. */
    val edits: List<MdEdit>,
    val text: String,
    val styles: List<MdStyleRange>,
    val indents: List<MdStyleRange>,
    val decorations: List<MdDecoration>,
) {
    /** Where source offset [offset] falls in [text]. */
    fun toSlice(offset: Int): Int =
        MarkdownRenderer.transformedOffset(edits, (offset - (start - lead)).coerceIn(0, end - start + lead))

    /** The source offset under [offset] of [text]. See [MarkdownBlocks.toSource]. */
    fun toSource(offset: Int): Int = start - lead + MarkdownBlocks.toSource(edits, offset)
}

/**
 * Cuts a Markdown document into [MdItem]s and draws any run of them on its own.
 *
 * Pure Kotlin, like the plan it reads, so every question about where a document may be cut is a
 * JUnit test rather than a scroll on a phone.
 */
object MarkdownBlocks {

    /**
     * How much source a live window may cover before it stops growing to follow a selection.
     *
     * A selection dragged across several blocks brings them into the field one at a time, so its
     * handles can keep going; beyond this the field would be the slow thing it replaced, so a
     * selection that big (Select All, mostly) is shown from the block it ends in.
     */
    const val WINDOW_CAP = 6_000

    /**
     * The document cut at every top-level blank line, as far as the plan allows.
     *
     * A cut goes in front of the first line of a run that follows a blank line; the blank lines
     * themselves stay with the run above them, so every item but the first starts right after a
     * newline. Fences and `$$` blocks are walked over whole — blank lines inside them are code, and
     * an unclosed one runs to the end of the document — and so is a table.
     *
     * A cut that one of the plan's [edits] would straddle is dropped, merging the two items: an
     * image takes the newline above it, a closing fence or a reference definition its own. Merging
     * rather than moving the cut keeps the promise that the character in front of an item is an
     * untouched newline, which is what [slice] draws as the step out of the block.
     */
    fun partition(source: String, edits: List<MdEdit>): List<MdItem> {
        if (source.isEmpty()) return listOf(MdItem(0, 0))
        val lines = MarkdownRenderer.Lines(source)
        val cuts = ArrayList<Int>()
        var blankAbove = false
        var k = 0
        while (k < lines.count) {
            val line = lines.text(k)
            if (line.isBlank()) {
                blankAbove = true
                k++
                continue
            }
            if (k > 0 && blankAbove) cuts += lines.start(k)
            blankAbove = false
            // The same order the plan dispatches in, so a block is walked over exactly where the
            // plan reads one.
            k = when {
                MarkdownParser.openFence(line) != null -> minOf(lines.fenceClose(k), lines.count - 1) + 1

                line.trim() == "$$" -> {
                    var close = k + 1
                    while (close < lines.count && lines.text(close).trim() != "$$") close++
                    minOf(close, lines.count - 1) + 1
                }

                lines.startsTable(k) -> {
                    var last = k + 1
                    while (last + 1 < lines.count && lines.text(last + 1).contains('|')) last++
                    last + 1
                }

                else -> k + 1
            }
        }

        val items = ArrayList<MdItem>(cuts.size + 1)
        var from = 0
        for (cut in cuts) {
            if (straddled(edits, cut)) continue
            items += MdItem(from, cut)
            from = cut
        }
        items += MdItem(from, source.length)
        return items
    }

    /**
     * Whether an edit reaches the newline in front of [cut] or across the cut itself.
     *
     * Edits are sorted and disjoint, so only the last one starting before the cut can reach it.
     */
    private fun straddled(edits: List<MdEdit>, cut: Int): Boolean {
        var low = 0
        var high = edits.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (edits[mid].start < cut) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return false
        val edit = edits[found]
        return edit.end >= cut || edit.start == cut - 1
    }

    /** The index of the item holding [offset]; the end of the document belongs to the last one. */
    fun itemAt(items: List<MdItem>, offset: Int): Int {
        var low = 0
        var high = items.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (items[mid].start <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * The items the live field shows for a selection `[selStart, selEnd]`, as an index range.
     *
     * Every item the selection touches while that stays under [WINDOW_CAP], so a handle dragged out
     * of the block finds the next one already there; otherwise only the item the selection *ends*
     * in, which is where the user is looking.
     */
    fun windowFor(items: List<MdItem>, selStart: Int, selEnd: Int): IntRange {
        val first = itemAt(items, minOf(selStart, selEnd))
        val last = itemAt(items, maxOf(selStart, selEnd))
        if (first == last) return first..first
        if (items[last].end - items[first].start <= WINDOW_CAP) return first..last
        val end = itemAt(items, selEnd)
        return end..end
    }

    /**
     * `[start, end)` of [source] drawn on its own, from the [plan] of the whole document.
     *
     * [start] and [end] have to be item boundaries — that is what guarantees no edit reaches across
     * either of them, and that a newline nobody edits stands in front of [start].
     */
    fun slice(source: String, plan: MarkdownRenderPlan, start: Int, end: Int): MdSlice {
        val lead = if (start > 0) 1 else 0
        val base = start - lead
        val edits = ArrayList<MdEdit>()
        val last = end >= source.length
        for (edit in plan.edits) {
            // Text inserted at a boundary belongs to the item starting there — except at the end
            // of the document, where no item starts and the last one takes it.
            if (edit.start > end || (edit.start == end && !last)) break
            if (edit.start >= start) {
                edits += edit.copy(start = edit.start - base, end = edit.end - base)
            }
        }
        val text = StringBuilder(source.length.coerceAtMost(end - base + 16))
            .append(source, base, end)
        for (edit in edits.asReversed()) text.replace(edit.start, edit.end, edit.replacement)

        // Where the slice sits in the rendering of the whole document. Opening edges, both of them:
        // text inserted exactly at a boundary belongs to the item that starts there.
        val from = MarkdownRenderer.transformedOffset(plan.edits, start, includeInsertionAt = false)
        val to = MarkdownRenderer.transformedOffset(plan.edits, end, includeInsertionAt = last)
        val shift = lead - from

        return MdSlice(
            start = start,
            end = end,
            lead = lead,
            isLast = last,
            edits = edits,
            text = text.toString(),
            styles = clip(plan.styles, from, to, shift),
            indents = clip(plan.indents, from, to, shift),
            decorations = plan.decorations.mapNotNull { decoration ->
                val at = anchorOf(decoration)
                if (at in from until to || (last && at == to)) decoration.shifted(shift) else null
            },
        )
    }

    private fun clip(ranges: List<MdStyleRange>, from: Int, to: Int, shift: Int): List<MdStyleRange> {
        val out = ArrayList<MdStyleRange>()
        for (range in ranges) {
            val s = maxOf(range.start, from)
            val e = minOf(range.end, to)
            if (e > s) out += MdStyleRange(s + shift, e + shift, range.style, range.arg)
        }
        return out
    }

    /** The rendered offset a decoration is placed by, which says which item it belongs to. */
    private fun anchorOf(decoration: MdDecoration): Int = when (decoration) {
        is MdCodeBlock -> decoration.start
        is MdTable -> decoration.start
        is MdRule -> decoration.offset
        is MdQuote -> decoration.start
        is MdTask -> decoration.offset
        is MdImage -> decoration.offset
    }

    /** Moves the rendered offsets of a decoration by [by], leaving the source ones where they are. */
    private fun MdDecoration.shifted(by: Int): MdDecoration = when (this) {
        is MdCodeBlock -> copy(
            start = start + by,
            end = end + by,
            headerStart = headerStart + by,
            headerEnd = headerEnd + by,
        )

        is MdTable -> copy(
            start = start + by,
            end = end + by,
            headerEnd = headerEnd + by,
            rows = rows.map { (it.first + by)..(it.last + by) },
            columnStops = columnStops.map { it + by },
        )

        is MdRule -> copy(offset = offset + by)
        is MdQuote -> copy(start = start + by, end = end + by)
        is MdTask -> copy(offset = offset + by)
        is MdImage -> copy(offset = offset + by)
    }

    /**
     * The source offset under rendered [offset], for [edits] applied to some source.
     *
     * The inverse of [MarkdownRenderer.transformedOffset] wherever one exists. A rendered offset
     * that lands *inside* a replacement — on a bullet glyph, in a table cell's padding — has no
     * source character of its own and goes to the start of what was replaced; the caret snaps
     * the editor already makes take it from there, as they do for a tap.
     */
    fun toSource(edits: List<MdEdit>, offset: Int): Int {
        var delta = 0
        for (edit in edits) {
            val at = edit.start + delta
            if (offset < at) return offset - delta
            // Where something hidden stood, the character on screen is the one after it: a tap at
            // the start of a heading means its first letter, not the hashes in front of it.
            if (offset == at && edit.replacement.isNotEmpty()) return edit.start
            if (offset < at + edit.replacement.length) return edit.start
            delta += edit.replacement.length - (edit.end - edit.start)
        }
        return offset - delta
    }

    /**
     * A key for each item that stays put while the document changes around it.
     *
     * Its text, and how many identical items came before it — `---` and `- [ ]` repeat, and a list
     * handed two equal keys refuses them.
     */
    fun keys(source: String, items: List<MdItem>): List<String> {
        val seen = HashMap<String, Int>()
        return items.map { item ->
            val text = source.substring(item.start, item.end)
            val n = seen.merge(text, 1, Int::plus)!!
            "${text.hashCode()}:${text.length}:$n"
        }
    }

    // ---- Keeping the field's edits inside its window -----------------------------------------

    /**
     * Where the live field's caret may stand for the window `[start, end)`: from the newline in
     * front of it (the step up and out) to the first offset after it (the step down and out).
     */
    fun bounds(start: Int, end: Int): IntRange = (if (start > 0) start - 1 else 0)..end

    /**
     * A selection the field reported, put back inside what it can show — or null to leave it be.
     *
     * The field only has the window to place a caret in, and everything trimmed off either side of
     * it is one zero-width point on screen. A caret moved onto that point comes back as a *range*
     * covering all of it — half the note, selected by an arrow key — and a selection's far end the
     * field could not see comes back moved there too. So an end that has newly left the window is
     * pulled to its edge, where the next frame's window picks it up, and an end that was already
     * outside (the anchor of a selection reaching out of the window) is put back where it was.
     * Select All is the one range spanning the trimmed parts on purpose, and is left alone.
     */
    fun repairSelection(
        window: IntRange,
        length: Int,
        beforeStart: Int,
        beforeEnd: Int,
        afterStart: Int,
        afterEnd: Int,
    ): Pair<Int, Int>? {
        if (minOf(afterStart, afterEnd) == 0 && maxOf(afterStart, afterEnd) == length) return null
        fun fix(after: Int, before: Int): Int = when {
            after in window -> after
            before !in window -> before
            after < window.first -> window.first
            else -> window.last
        }
        val start = fix(afterStart, beforeStart)
        val end = fix(afterEnd, beforeEnd)
        return if (start == afterStart && end == afterEnd) null else start to end
    }

    /**
     * The range a one-change edit should have replaced, when the field reached past its window to
     * make it — or null when it stayed inside, or the user's own selection covered that much.
     *
     * A deletion against the window's edge maps back through the trimmed-off part, which is one
     * point on screen and the whole rest of the document in the source: a backspace there deleted
     * everything above the block. Re-aimed, a backspace from a bare caret takes the one character
     * behind it, a forward delete the one in front, and anything else is cut to the window.
     */
    fun repairChange(
        window: IntRange,
        selStart: Int,
        selEnd: Int,
        changeStart: Int,
        changeEnd: Int,
        inserted: Int,
    ): IntRange? {
        if (changeStart >= window.first && changeEnd <= window.last) return null
        if (minOf(selStart, selEnd) <= changeStart && maxOf(selStart, selEnd) >= changeEnd) return null
        val caret = selEnd
        if (inserted == 0 && selStart == selEnd) {
            if (changeEnd == caret && changeStart < caret) return (caret - 1).coerceAtLeast(0) until caret
            if (changeStart == caret && changeEnd > caret) return caret until caret + 1
        }
        val from = changeStart.coerceIn(window.first, window.last)
        val to = changeEnd.coerceIn(from, window.last)
        return from until to
    }

    // ---- The document the screen shows ------------------------------------------------------

    /**
     * Everything a frame of the `.md` screen needs to know about one state of the document: the
     * items, and which of them the live field covers.
     *
     * Worked out by the same function in the field's output transformation and in the list around
     * it, from the same text and selection — which is what keeps the two agreeing in every frame.
     */
    class Layout(
        val source: String,
        val items: List<MdItem>,
        val window: IntRange,
        val formatted: Boolean,
    ) {
        val windowStart: Int get() = items[window.first].start
        val windowEnd: Int get() = items[window.last].end
    }

    fun layout(source: String, selStart: Int, selEnd: Int, formatted: Boolean): Layout {
        val items = items(source, formatted)
        // Select All is not a place in the document; it is shown from wherever the caret was,
        // which the transformation cannot know — so from the block it ends in, like any other
        // selection too big to follow.
        return Layout(source, items, windowFor(items, selStart, selEnd), formatted)
    }

    /** The items of [source]; in source mode nothing is hidden and the plan has no say. */
    fun items(source: String, formatted: Boolean): List<MdItem> {
        val key = source to formatted
        synchronized(cache) { cache[key] }?.let { return it }
        val result = partition(source, if (formatted) MarkdownRenderer.plan(source).edits else emptyList())
        synchronized(cache) { cache[key] = result }
        return result
    }

    /** The slice the live field shows, for [layout]. In source mode, the source itself. */
    fun window(layout: Layout, activeCell: Int): MdSlice = sliceOf(layout, layout.windowStart, layout.windowEnd, activeCell)

    fun sliceOf(layout: Layout, start: Int, end: Int, activeCell: Int = -1): MdSlice {
        val plan = if (layout.formatted) MarkdownRenderer.plan(layout.source, activeCell) else EMPTY_PLAN
        return slice(layout.source, plan, start, end)
    }

    private val EMPTY_PLAN = MarkdownRenderPlan(emptyList(), emptyList(), emptyList())

    /** The last few partitions, for the same reason [MarkdownRenderer] keeps several plans. */
    private val cache = object : LinkedHashMap<Pair<String, Boolean>, List<MdItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, List<MdItem>>) =
            size > 6
    }
}
