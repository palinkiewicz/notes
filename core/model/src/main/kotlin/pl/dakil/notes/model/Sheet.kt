package pl.dakil.notes.model

import pl.dakil.notes.model.json.JsonObject
import kotlin.math.ceil

/** Printable margins in points. Text flows inside them; ink may go anywhere. */
data class PageMargins(
    val left: Float = 56f,
    val top: Float = 56f,
    val right: Float = 56f,
    val bottom: Float = 56f,
) {
    companion object {
        val DEFAULT = PageMargins()
        val NONE = PageMargins(0f, 0f, 0f, 0f)
    }
}

/**
 * The paper a note is written on.
 *
 * A note always has a real paper size. "Infinite" is deliberately *not* a size — it is a way of
 * looking at the sheet ([ViewMode.CONTINUOUS]), so a note can be written as an endless scroll and
 * still print onto A4 without being converted first.
 */
data class PageFormat(
    val size: PageSize = PageSize.A4,
    val background: PageBackground = PageBackground.DEFAULT,
    val margins: PageMargins = PageMargins.DEFAULT,
) {
    val width: Float get() = size.width
    val height: Float get() = size.height

    /** The column text flows in. */
    val contentLeft: Float get() = margins.left
    val contentRight: Float get() = width - margins.right
    val contentWidth: Float get() = (contentRight - contentLeft).coerceAtLeast(1f)

    /** Usable height on one page, between the margins. */
    val usableHeight: Float get() = (height - margins.top - margins.bottom).coerceAtLeast(1f)

    companion object {
        val DEFAULT = PageFormat()
    }
}

/** How the sheet is presented. Both show the same document; only the furniture differs. */
enum class ViewMode {
    /** Discrete pages with visible edges and gaps — what will come out of the printer. */
    PAGED,

    /** One uninterrupted scroll. Page boundaries still exist; they are simply not drawn. */
    CONTINUOUS;

    companion object {
        fun fromKey(key: String): ViewMode = if (key == "continuous") CONTINUOUS else PAGED
    }

    val key: String get() = name.lowercase()
}

/**
 * One continuous sheet of paper: text boxes and ink, side by side on the same surface.
 *
 * There is no "text mode" and no "drawing mode" — there is one surface. Everything on it is a
 * [Block] in the same coordinate space: [TextBlock]s carry the typed words, [InkBlock]s the
 * strokes. A note that is only typed simply has no ink; a note that is only drawn simply has no
 * boxes.
 *
 * ### Coordinates
 *
 * Everything is in *strip* coordinates: x runs across one page width, y runs from the top of the
 * first page downwards without limit. Page `k` occupies `y ∈ [k·height, (k+1)·height)`, so
 * pagination is arithmetic rather than stored state, and printing is a matter of slicing.
 *
 * ### Nothing moves under anything else
 *
 * Strokes and boxes alike are positioned on the paper. Typing does not drag a stroke down and
 * drawing does not push a box aside — the same contract as annotating a printed page. It is the
 * only model that stays predictable when a stroke spans several paragraphs, and it is what lets a
 * page operation move a whole page's worth of work without having to ask what any of it means.
 */
data class Sheet(
    val format: PageFormat = PageFormat.DEFAULT,
    /**
     * The v2 document flow, kept only so a file written before text boxes existed can be migrated.
     *
     * `DakNoteReader` empties this into a [TextBlock] on open, so a sheet in the editor's hands
     * always has it blank. Nothing renders it and nothing writes it back; `content.md` is derived
     * from the boxes instead.
     */
    val markdown: String = "",
    /** Ink layers and future block types, in strip coordinates, drawn over the text. */
    val blocks: List<Block> = emptyList(),
    /**
     * A floor on the strip height in points, carried over from files that had a document flow.
     *
     * It existed because pagination once depended on how tall the flow turned out, which only the
     * text layout engine knew. A box states its own extent in its [Block.rect], so nothing needs to
     * be cached any more — but a v2 note's page count must not change under the user on the way to
     * v3, so the value is still read, still honoured by [contentPageCount], and still written back.
     */
    val contentHeight: Float = 0f,
    /**
     * How many pages this sheet has.
     *
     * A floor, not a total: writing past the bottom of the last page still adds another, exactly
     * as it did before this field existed, and saving absorbs that growth so pages are never
     * silently lost. What the field adds is the other direction — a page can now exist because the
     * author asked for it, before there is anything on it to imply its existence. Without that,
     * there is no way to start drawing on a second page.
     */
    val pages: Int = 1,
    val unknown: JsonObject = JsonObject.EMPTY,
) {
    fun block(id: BlockId): Block? = blocks.firstOrNull { it.id == id }

    fun blocksInPaintOrder(): List<Block> = blocks.sortedBy { it.z }

    fun inkLayers(): List<InkBlock> = blocks.filterIsInstance<InkBlock>()

    /**
     * Every text box, in reading order.
     *
     * Down the strip and then across, which is page order too: page `k` owns a contiguous band of
     * y, so sorting by y alone already puts page one's boxes before page two's. This is the order
     * the note's words are written to `content.md` and handed to the search index in.
     */
    fun textBlocks(): List<TextBlock> = blocks.filterIsInstance<TextBlock>()
        .sortedWith(compareBy({ it.worldBounds().top }, { it.worldBounds().left }))

    /** Every box's Markdown, in reading order, as one document. */
    fun textInReadingOrder(): String =
        textBlocks().map { it.markdown }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /** The layer new strokes land on: the topmost visible, unlocked one. */
    fun defaultInkLayer(): InkBlock? =
        inkLayers().filter { it.visible && !it.locked }.maxByOrNull { it.z }

    /**
     * Bottom-most extent of everything on the sheet.
     *
     * Ink is measured along the centreline of the stroke rather than its inked extent, because this
     * decides *how many pages there are*. A stroke cut at a page boundary ends exactly on it; judged
     * by the inked extent it reaches half a stroke width onto the page below, and that page can then
     * never be deleted — it is being held open by the end cap of a stroke on the page before it.
     * Everything else states its extent plainly in its bounds.
     */
    fun contentBottom(): Float {
        var bottom = 0f
        for (block in blocks) {
            val b = when (block) {
                is InkBlock -> {
                    var deepest = 0f
                    for (stroke in block.strokes) {
                        val y = block.transform.mapBounds(stroke.coreBounds).bottom
                        if (y > deepest) deepest = y
                    }
                    deepest
                }
                else -> block.worldBounds().bottom
            }
            if (b > bottom) bottom = b
        }
        return bottom
    }

    /** How many pages this sheet occupies. Always at least one. */
    fun pageCount(): Int = maxOf(pages, contentPageCount())

    /**
     * How many pages the content alone reaches onto.
     *
     * The floor for removing a page: a page holding text or ink is not one the user can delete by
     * decrementing a counter, because nothing about that gesture says the work on it is to be
     * thrown away.
     */
    fun contentPageCount(): Int {
        val height = format.height
        if (height <= 0f) return 1
        val extent = maxOf(contentHeight, contentBottom(), 1f)
        return maxOf(1, ceil(extent / height - 1e-4f).toInt())
    }

    /** True when the last page is the author's rather than the content's, so it can be given back. */
    fun canRemoveLastPage(): Boolean = pageCount() > 1 && pageCount() > contentPageCount()

    fun withPages(count: Int): Sheet = copy(pages = count.coerceAtLeast(1))

    // ---- Page operations -------------------------------------------------------------------------

    /**
     * Whether page [index] can be duplicated or removed — that is, unless there is no such page.
     *
     * There used to be a second condition here: a page the document flow reached could not be
     * touched, because inserting or deleting paper under flowing text would have slid the ink out
     * from under the words it was written against, and no page number can answer "which paragraph
     * did you mean to delete". Text lives in boxes now, and a box is on a page in exactly the way a
     * stroke is, so both questions have ordinary answers and the restriction is gone.
     */
    fun canEditPage(index: Int): Boolean = index in 0 until pageCount()

    /** Whether removing page [index] would leave at least one page standing. */
    fun canRemovePage(index: Int): Boolean = canEditPage(index) && pageCount() > 1

    /**
     * Whether page [index] can trade places with the one before it — that is, unless it is first.
     *
     * Reordering is not held to [canEditPage] the way duplicate and remove are. Swapping exchanges
     * what is *on* two pages without changing how many there are, so nothing slides out from under
     * anything: the only page you cannot move up is the one with nothing above it. The Markdown
     * flow does not move with a swap, since it is one continuous flow rather than a per-page thing.
     */
    fun canMovePageUp(index: Int): Boolean = index in 1 until pageCount()

    /** Whether page [index] can trade places with the one after it — that is, unless it is last. */
    fun canMovePageDown(index: Int): Boolean = index in 0 until pageCount() - 1

    /**
     * Inserts a copy of page [index] directly after it, sliding everything below down one page.
     *
     * Only what is actually *on* the page is copied. A stroke running across the page break is cut
     * at the boundary: the part on this page is what gets duplicated, and the part on the next page
     * travels down with the page it belongs to rather than being left overlapping the new sheet. A
     * box is never cut — it goes wherever [pageOf] says it is — and its copy is given an id of its
     * own, since two blocks sharing one is a document that cannot be edited.
     */
    fun withPageDuplicated(index: Int): Sheet {
        if (!canEditPage(index)) return this
        val height = format.height
        val top = index * height
        val bottom = top + height
        val down = Affine.translate(0f, height)
        val ids = IdAllocator(blocks.map { it.id.raw })

        val moved = mapBlocks(
            ink = { stroke ->
                buildList {
                    addAll(stroke.clippedToBand(ABOVE_ALL, bottom))
                    for (piece in stroke.clippedToBand(top, bottom)) add(piece.transformed(down))
                    for (piece in stroke.clippedToBand(bottom, BELOW_ALL)) add(piece.transformed(down))
                }
            },
            placed = { block ->
                when {
                    pageOf(block) < index -> listOf(block)
                    pageOf(block) == index ->
                        listOf(block, block.translated(0f, height).withId(ids.next()))
                    else -> listOf(block.translated(0f, height))
                }
            },
        )
        return copy(blocks = moved, pages = pageCount() + 1)
    }

    /** Removes page [index] along with everything on it, sliding what is below up one page. */
    fun withPageRemoved(index: Int): Sheet {
        if (!canRemovePage(index)) return this
        val height = format.height
        val top = index * height
        val bottom = top + height
        val up = Affine.translate(0f, -height)

        val moved = mapBlocks(
            ink = { stroke ->
                buildList {
                    addAll(stroke.clippedToBand(ABOVE_ALL, top))
                    for (piece in stroke.clippedToBand(bottom, BELOW_ALL)) add(piece.transformed(up))
                }
            },
            placed = { block ->
                when {
                    pageOf(block) < index -> listOf(block)
                    pageOf(block) == index -> emptyList()
                    else -> listOf(block.translated(0f, -height))
                }
            },
        )
        return copy(blocks = moved, pages = (pageCount() - 1).coerceAtLeast(1))
    }

    /**
     * Exchanges the contents of two pages.
     *
     * Everything between them, and everything outside them, stays exactly where it was — so moving
     * a page up repeatedly walks it through the note one position at a time without disturbing the
     * rest. Boxes travel with their page; strokes are cut at the boundaries and travel by the piece.
     */
    fun withPagesSwapped(a: Int, b: Int): Sheet {
        val range = 0 until pageCount()
        if (a == b || a !in range || b !in range) return this
        val height = format.height
        val first = minOf(a, b)
        val second = maxOf(a, b)
        val firstTop = first * height
        val secondTop = second * height
        val shift = secondTop - firstTop

        val moved = mapBlocks(
            ink = { stroke ->
                buildList {
                    addAll(stroke.clippedToBand(ABOVE_ALL, firstTop))
                    for (piece in stroke.clippedToBand(firstTop, firstTop + height)) {
                        add(piece.transformed(Affine.translate(0f, shift)))
                    }
                    addAll(stroke.clippedToBand(firstTop + height, secondTop))
                    for (piece in stroke.clippedToBand(secondTop, secondTop + height)) {
                        add(piece.transformed(Affine.translate(0f, -shift)))
                    }
                    addAll(stroke.clippedToBand(secondTop + height, BELOW_ALL))
                }
            },
            placed = { block ->
                listOf(
                    when (pageOf(block)) {
                        first -> block.translated(0f, shift)
                        second -> block.translated(0f, -shift)
                        else -> block
                    }
                )
            },
        )
        return copy(blocks = moved)
    }

    /**
     * Rebuilds the sheet a page operation at a time.
     *
     * The two halves are separate because they are addressed differently. An ink *layer* is not on
     * any page — it spans the whole strip — so it survives every operation and only its strokes
     * move, each cut at the page boundaries it crosses. Everything else is a positioned block that
     * belongs to one page, so [placed] is asked what becomes of the whole thing: nothing, itself
     * shifted, a copy alongside it, or gone.
     */
    private fun mapBlocks(
        ink: (Stroke) -> List<Stroke>,
        placed: (Block) -> List<Block>,
    ): List<Block> = blocks.flatMap { block ->
        if (block !is InkBlock) return@flatMap placed(block)
        val out = ArrayList<Stroke>(block.strokes.size)
        for (stroke in block.strokes) out += ink(stroke)
        listOf(block.copy(strokes = out).withRecomputedBounds())
    }

    /**
     * Hands out block ids that are free, and stay free as more are asked for.
     *
     * [nextBlockId] answers for one block at a time and would give the same id twice when a page
     * carrying two boxes is duplicated, which is a document whose blocks cannot be told apart.
     */
    private class IdAllocator(taken: Collection<String>) {
        private val used = HashSet(taken)
        private var n = used.size

        fun next(): BlockId {
            while ("b$n" in used) n++
            used += "b$n"
            return BlockId("b$n")
        }
    }

    /** Total strip height including the trailing part-page, in points. */
    fun stripHeight(): Float = pageCount() * format.height

    /**
     * The page a positioned block belongs to.
     *
     * Judged by the top-left corner of its bounds and nothing else, for the same reason a stroke's
     * page is judged by its centreline: a box whose last line pokes past the boundary is still on
     * the page it starts on. Deciding otherwise would let a box hold open the page below it, or —
     * worse — send it there behind the user's back the moment they typed one line too many.
     */
    fun pageOf(block: Block): Int = pageAt(block.worldBounds().top)

    /** The page index containing strip coordinate [y]. */
    fun pageAt(y: Float): Int =
        if (format.height <= 0f) 0 else (y / format.height).toInt().coerceAtLeast(0)

    fun withBlock(block: Block): Sheet {
        val i = blocks.indexOfFirst { it.id == block.id }
        return if (i < 0) copy(blocks = blocks + block)
        else copy(blocks = blocks.toMutableList().also { it[i] = block })
    }

    fun withoutBlock(id: BlockId): Sheet = copy(blocks = blocks.filterNot { it.id == id })

    /** The next unused block id, using the `b<n>` convention. */
    fun nextBlockId(): BlockId {
        var n = blocks.size
        val taken = blocks.mapTo(HashSet()) { it.id.raw }
        while ("b$n" in taken) n++
        return BlockId("b$n")
    }

    private companion object {
        /** Open-ended band edges: everything above the first page, everything below the last. */
        const val ABOVE_ALL = -Float.MAX_VALUE
        const val BELOW_ALL = Float.MAX_VALUE
    }
}
