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
 * One continuous sheet of paper: Markdown text as the base layer, ink drawn over it.
 *
 * There is no "text mode" and no "drawing mode" — there is one surface. [markdown] flows down the
 * strip inside the page margins; [blocks] (ink layers, and later charts or stickers) sit on top of
 * it in the same coordinate space. A note that is only typed simply has no ink; a note that is only
 * drawn simply has no text.
 *
 * ### Coordinates
 *
 * Everything is in *strip* coordinates: x runs across one page width, y runs from the top of the
 * first page downwards without limit. Page `k` occupies `y ∈ [k·height, (k+1)·height)`, so
 * pagination is arithmetic rather than stored state, and printing is a matter of slicing.
 *
 * ### Ink does not follow reflow
 *
 * Strokes are positioned on the paper, not attached to the text. Typing above a stroke does not
 * drag it down — the same contract as annotating a printed page. It is the only model that stays
 * predictable when a stroke spans several paragraphs, and it means text editing can never silently
 * rearrange a drawing.
 */
data class Sheet(
    val format: PageFormat = PageFormat.DEFAULT,
    /** The base Markdown flow. Empty for a pure drawing note. */
    val markdown: String = "",
    /** Ink layers and future block types, in strip coordinates, drawn over the text. */
    val blocks: List<Block> = emptyList(),
    /**
     * Height of the strip in points, as last laid out.
     *
     * Cached from the renderer because pagination depends on how tall the text turned out, which
     * only the text layout engine knows. Persisted so a note reopens with the right page count
     * before its text has been measured.
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

    /** The layer new strokes land on: the topmost visible, unlocked one. */
    fun defaultInkLayer(): InkBlock? =
        inkLayers().filter { it.visible && !it.locked }.maxByOrNull { it.z }

    /** Bottom-most extent of anything drawn, ignoring the text. */
    fun inkBottom(): Float {
        var bottom = 0f
        for (block in blocks) {
            val b = block.worldBounds().bottom
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
        val extent = maxOf(contentHeight, inkBottom(), 1f)
        return maxOf(1, ceil(extent / height - 1e-4f).toInt())
    }

    /** True when the last page is the author's rather than the content's, so it can be given back. */
    fun canRemoveLastPage(): Boolean = pageCount() > 1 && pageCount() > contentPageCount()

    fun withPages(count: Int): Sheet = copy(pages = count.coerceAtLeast(1))

    /** Total strip height including the trailing part-page, in points. */
    fun stripHeight(): Float = pageCount() * format.height

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
}
