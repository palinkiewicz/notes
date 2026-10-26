package pl.dakil.notes.editor

import pl.dakil.notes.data.ImportedImage
import pl.dakil.notes.editor.markdown.DataUriImages
import pl.dakil.notes.format.DakNote
import pl.dakil.notes.model.Block
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.MIN_IMAGE_SIDE
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock

/**
 * Where pictures go on a sheet, as plain arithmetic.
 *
 * Kept out of the view model so the sizing rules are JUnit tests: a picture placed too large or off
 * the paper is something only a device shows otherwise.
 */
object SheetImages {

    /**
     * How many points one of a picture's own pixels takes before anything is clamped.
     *
     * The pixels are read as if taken on a 2× screen — 0.5 dp each, which at 72/160 pt to the dp is
     * 0.225 pt — so a screenshot comes out near the size it was on the phone and a photograph is
     * clamped to the column long before that matters.
     */
    const val PT_PER_PX = 0.225f

    /**
     * The rect a [widthPx] × [heightPx] picture takes when placed with its top-left at ([x], [y]).
     *
     * Kept to the column, never past the margin, and short enough to end on the page it starts on:
     * a picture belongs to the page its corner is on, like a text box, and one hanging into the next
     * page would be cut at the foot of this one when printed.
     */
    fun placement(sheet: Sheet, x: Float, y: Float, widthPx: Int, heightPx: Int): Rect {
        val format = sheet.format
        val aspect = if (widthPx > 0 && heightPx > 0) heightPx.toFloat() / widthPx else 0.75f
        val left = x.coerceIn(format.contentLeft, (format.contentRight - MIN_IMAGE_SIDE).coerceAtLeast(format.contentLeft))
        val top = y.coerceAtLeast(0f)
        val pageBottom = (sheet.pageAt(top) + 1) * format.height - format.margins.bottom

        var width = (widthPx * PT_PER_PX).coerceAtLeast(MIN_IMAGE_SIDE).coerceAtMost(format.contentRight - left)
        val room = (pageBottom - top).coerceAtLeast(MIN_IMAGE_SIDE)
        if (width * aspect > room) width = room / aspect
        width = width.coerceAtLeast(MIN_IMAGE_SIDE)
        return Rect(left, top, left + width, top + width * aspect)
    }

    /** A block for [image] at [rect], drawn above everything already on the sheet. */
    fun blockFor(sheet: Sheet, image: ImportedImage, rect: Rect, id: BlockId = sheet.nextBlockId()) = ImageBlock(
        id = id,
        z = (sheet.blocks.maxOfOrNull { it.z } ?: 0) + 1,
        rect = rect,
        src = DakNote.mediaEntry(image.fileName),
        mimeType = image.mimeType,
        bytes = image.bytes,
    )

    /**
     * Turns the base64 images one release wrote into text boxes into image blocks.
     *
     * Each picture becomes a block where its box was — stacked down the page if there were several
     * — and the box keeps whatever prose it had, losing only the payloads. A box with nothing else
     * in it goes. [import] reads a payload's bytes; one it cannot read stays in the text, so nothing
     * is lost to a damaged image. Returns [sheet] itself when there was nothing to do.
     */
    fun migrateEmbedded(sheet: Sheet, import: (ByteArray, String) -> ImportedImage?): Sheet {
        if (sheet.blocks.none { it is TextBlock && it.markdown.contains("data:image/") }) return sheet
        var result = sheet
        val taken = sheet.blocks.mapTo(HashSet()) { it.id.raw }
        var n = taken.size
        for (box in sheet.blocks.filterIsInstance<TextBlock>()) {
            val found = DataUriImages.find(box.markdown)
            if (found.isEmpty()) continue
            val images = found.mapNotNull { embedded -> embedded.bytes()?.let { import(it, embedded.mimeType) } }
            if (images.size != found.size) continue

            val prose = DataUriImages.strip(box.markdown)
            var y = if (prose.isBlank()) box.rect.top else box.rect.bottom + GAP
            val added = ArrayList<Block>()
            for (image in images) {
                while ("b$n" in taken) n++
                taken += "b$n"
                val rect = placement(result, box.rect.left, y, image.width, image.height)
                added += blockFor(result, image, rect, BlockId("b$n")).copy(z = box.z)
                y = rect.bottom + GAP
            }
            result = if (prose.isBlank()) result.withoutBlock(box.id) else result.withBlock(box.copy(markdown = prose))
            result = result.copy(blocks = result.blocks + added)
        }
        return result
    }

    private const val GAP = 8f
}
