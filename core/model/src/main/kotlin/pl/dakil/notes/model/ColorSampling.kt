package pl.dakil.notes.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic of taking a colour off the screen, apart from the screen.
 *
 * Everything here is about one mistake or another that would only show as a slightly wrong colour:
 * the neighbouring pixel, a magnifier shifted by one, or a highlighter that turned opaque.
 */
object ColorSampling {

    /**
     * The colour a sample becomes: its red, green and blue, at the opacity of the colour it replaces.
     *
     * A screen pixel is always opaque, so its alpha says nothing about the ink that made it. Keeping
     * the current opacity means a translucent highlighter stays one when it takes a new hue.
     */
    fun picked(sampled: Int, current: Int): Int =
        ColorCodec.withAlpha(sampled, ColorCodec.alpha(current))

    /** The pixel a touch at [coordinate] is over, on an axis [size] pixels long. */
    fun pixelAt(coordinate: Float, size: Int): Int =
        floor(coordinate).toInt().coerceIn(0, size - 1)

    /**
     * Fills [out], a square [side] cells wide, with the pixels around ([centerX], [centerY]).
     *
     * The centre pixel lands in the middle cell wherever it is. Cells past the edge of the image are
     * left at 0, so at the edge the magnifier runs out rather than sliding its contents sideways and
     * showing a different pixel under the crosshair from the one being taken.
     *
     * [read] has the shape of `Bitmap.getPixels`, which refuses any rectangle not wholly inside the
     * bitmap: (out, offset, stride, x, y, width, height).
     */
    fun readAround(
        out: IntArray,
        side: Int,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        read: (IntArray, Int, Int, Int, Int, Int, Int) -> Unit,
    ) {
        out.fill(0)
        val radius = side / 2
        val firstX = centerX - radius
        val firstY = centerY - radius
        val left = max(firstX, 0)
        val top = max(firstY, 0)
        val right = min(firstX + side, width)
        val bottom = min(firstY + side, height)
        if (left >= right || top >= bottom) return
        val offset = (top - firstY) * side + (left - firstX)
        read(out, offset, side, left, top, right - left, bottom - top)
    }

    /**
     * Where a magnifier goes for a touch at ([touchX], [touchY]): centred above it, clear of the
     * finger, and below it instead when there is no room above.
     *
     * Both axes stay inside the view, because a magnifier cut off at the edge hides the very part of
     * the note the user has moved there to look at.
     */
    fun loupeOrigin(
        touchX: Float,
        touchY: Float,
        width: Float,
        height: Float,
        gap: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): Pair<Float, Float> {
        val x = (touchX - width / 2f).coerceIn(0f, max(viewWidth - width, 0f))
        val above = touchY - gap - height
        val y = if (above >= 0f) above else min(touchY + gap, max(viewHeight - height, 0f))
        return x to y
    }
}
