package pl.dakil.notes.editor.canvas

import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.ui.sheet.SheetPainter

/**
 * How much smaller than usual the strip has to be measured for Compose to be able to measure it.
 *
 * The whole strip is one layout node, and a Compose [androidx.compose.ui.unit.Constraints] cannot
 * hold a height past 2¹⁸ − 1 pixels — less again once the width needs more than 13 bits. At the
 * usual scale of a point to 160/72 dp that is about fifty A4 pages on a phone, and one imported PDF
 * gets past it in a tap. Past it the editor did not draw a blank page; it crashed.
 *
 * So a strip that would be too tall is measured at a power-of-two fraction of the usual scale, and
 * the zoom is multiplied by the same factor: everything on the glass is where and how large it
 * would have been, because the paper is drawn as vectors and the fraction is undone by the scale it
 * is placed at. A power of two so the factor changes rarely — at fifty pages, at a hundred — and
 * never for a note short enough not to need it, which is every note this app had before PDFs.
 */
object StripScale {

    /** Comfortably inside 2¹⁸ − 1, so a few pages of chrome and rounding can never tip it over. */
    const val MAX_HEIGHT_PX = 240_000f

    /** The widest a strip can be and still be allowed that height: 13 bits. */
    const val MAX_WIDTH_PX = 8_000f

    /** The divisor for [basePtToPx]: 1 for any strip that fits as it is, else the power of two that makes it. */
    fun of(format: PageFormat, pageCount: Int, basePtToPx: Float, paged: Boolean): Float {
        var k = 1f
        while (k < MAX_FACTOR) {
            val ptToPx = basePtToPx / k
            val height = SheetPainter.stripHeightPx(format, pageCount, ptToPx, paged)
            val width = format.width * ptToPx
            if (height <= MAX_HEIGHT_PX && width <= MAX_WIDTH_PX) break
            k *= 2f
        }
        return k
    }

    /** Ten thousand A4 pages; past this the note has bigger problems than its layout. */
    private const val MAX_FACTOR = 256f
}
