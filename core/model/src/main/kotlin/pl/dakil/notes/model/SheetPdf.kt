package pl.dakil.notes.model

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.nextUp

/** One page of a PDF as it will be shown: its index in the file, and its size in points. */
data class PdfSourcePage(val index: Int, val width: Float, val height: Float)

/** Where a PDF page goes on the sheet. */
object PdfFit {

    /**
     * The rect a [srcWidth] × [srcHeight] PDF page is shown in on sheet page [page]: as large as fits,
     * aspect kept, centred.
     *
     * The edges are then nudged inwards by a float step at a time until the arithmetic agrees with
     * the geometry — its top on [page] and its bottom no further than that page's foot. Exact `k·H`
     * is not representable for most page heights, and a page whose top rounded onto the page above,
     * or whose bottom rounded past its own, would be counted there by every rule that measures pages:
     * the top by an older build's [Sheet.pageOf], the bottom by [Sheet.contentPageCount].
     */
    fun rect(page: Int, format: PageFormat, srcWidth: Float, srcHeight: Float): Rect {
        val pageW = format.width
        val pageH = format.height
        if (srcWidth <= 0f || srcHeight <= 0f || pageW <= 0f || pageH <= 0f) {
            return Rect(0f, page * pageH, pageW, (page + 1) * pageH)
        }
        val scale = min(pageW / srcWidth, pageH / srcHeight)
        // Within a hundredth of a point of the paper is the paper: the same page saved by another
        // tool with its size rounded differently should not come back letterboxed by a sliver.
        val w = (srcWidth * scale).let { if (pageW - it < FULL_BLEED) pageW else it }
        val h = (srcHeight * scale).let { if (pageH - it < FULL_BLEED) pageH else it }
        val left = (pageW - w) / 2f
        var top = page * pageH + (pageH - h) / 2f
        while ((top / pageH).toInt() < page) top = top.nextUp()
        var bottom = top + h
        while (bottom > top && ceil(bottom / pageH - 1e-4f).toInt() > page + 1) bottom = bottom.nextDown()
        return Rect(left, top, left + w, bottom)
    }

    private const val FULL_BLEED = 0.01f
}

/** The pages that have a PDF page as their backdrop, and so no pattern of their own. */
fun Sheet.pdfPageIndices(): Set<Int> =
    blocks.mapNotNullTo(HashSet()) { block -> if (block is PdfPageBlock) pageOf(block) else null }

/**
 * Every PDF page put back exactly where its page says it belongs.
 *
 * A page operation moves blocks by whole page heights, and float sums drift: after enough swaps a
 * page that started full-bleed would sit a few ulps off its page. Re-deriving each rect from the
 * page it is on, rather than trusting the arithmetic that moved it, makes that drift impossible.
 */
fun Sheet.withPdfBlocksSnapped(): Sheet {
    if (blocks.none { it is PdfPageBlock }) return this
    return copy(
        blocks = blocks.map { block ->
            if (block !is PdfPageBlock) block
            else block.copy(rect = PdfFit.rect(pageOf(block), format, block.rect.width, block.rect.height))
        },
    )
}

/**
 * The same sheet on different paper, with each PDF page refitted to the page it was on.
 *
 * The page is read from the sheet as it was, since the new height would put a block's middle on a
 * different page; everything else stays where it is, exactly as changing the paper always has.
 */
fun Sheet.withFormatRefitted(format: PageFormat): Sheet {
    if (blocks.none { it is PdfPageBlock }) return copy(format = format)
    val pages = blocks.associate { it.id to pageOf(it) }
    return copy(
        format = format,
        blocks = blocks.map { block ->
            if (block !is PdfPageBlock) block
            else block.copy(rect = PdfFit.rect(pages.getValue(block.id), format, block.rect.width, block.rect.height))
        },
    )
}

/**
 * Inserts [pages] of one PDF as new pages, the first of them becoming page [at].
 *
 * Every new block sits beneath everything already on the sheet. Rendering does not consult it — a
 * PDF page is always the backdrop — but `sheet.json` is written in z order, and the backdrop coming
 * first keeps the file reading the way the page looks.
 */
fun Sheet.withPdfPagesInserted(at: Int, src: String, data: MediaData, pages: List<PdfSourcePage>): Sheet {
    if (pages.isEmpty()) return this
    val opened = withPagesInserted(at, pages.size)
    if (opened === this) return this
    val z = (blocks.minOfOrNull { it.z } ?: 0) - 1
    val taken = opened.blocks.mapTo(HashSet()) { it.id.raw }
    var n = opened.blocks.size
    val added = pages.mapIndexed { i, source ->
        while ("b$n" in taken) n++
        taken += "b$n"
        PdfPageBlock(
            id = BlockId("b$n"),
            z = z,
            rect = PdfFit.rect(at + i, format, source.width, source.height),
            src = src,
            page = source.index,
            data = data,
        )
    }
    return opened.copy(blocks = added + opened.blocks)
}
