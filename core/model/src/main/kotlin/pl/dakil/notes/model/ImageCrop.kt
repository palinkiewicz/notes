package pl.dakil.notes.model

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A picture's crop as plain arithmetic, so what a grip does and which pixels a decode reads are
 * JUnit tests rather than something only a device shows.
 *
 * A crop is a [Rect] in fractions of the picture: `(0, 0, 1, 1)` is all of it. See [ImageBlock.crop].
 */
object ImageCrop {

    /**
     * [crop] kept inside the picture and at least [minU] × [minV] of it.
     *
     * A crop that would be too narrow is widened from its right or bottom edge — or, at the far edge
     * of the picture, from the other one — so the result is always a crop of something.
     */
    fun clamped(crop: Rect, minU: Float, minV: Float): Rect {
        val left = crop.left.coerceIn(0f, 1f - minU)
        val top = crop.top.coerceIn(0f, 1f - minV)
        return Rect(left, top, crop.right.coerceIn(left + minU, 1f), crop.bottom.coerceIn(top + minV, 1f))
    }

    /**
     * [crop] with the edges named taken to the finger at ([u], [v]), in fractions of the picture.
     *
     * Each edge stops at the picture's own edge and [minU] or [minV] short of the edge opposite it,
     * so dragging a side past the other one leaves the crop at its narrowest rather than turned
     * inside out.
     */
    fun resize(
        crop: Rect,
        left: Boolean, top: Boolean, right: Boolean, bottom: Boolean,
        u: Float, v: Float,
        minU: Float, minV: Float,
    ): Rect = Rect(
        left = if (left) u.coerceIn(0f, crop.right - minU) else crop.left,
        top = if (top) v.coerceIn(0f, crop.bottom - minV) else crop.top,
        right = if (right) u.coerceIn(crop.left + minU, 1f) else crop.right,
        bottom = if (bottom) v.coerceIn(crop.top + minV, 1f) else crop.bottom,
    )

    /**
     * The pixels of a [width] × [height] picture that [crop] shows, as left, top, right and bottom.
     *
     * Rounded outwards, so the pixel a visible edge cuts through is decoded rather than lost, and
     * never empty: a sliver of a crop is still at least one pixel of picture.
     */
    fun pixelRegion(crop: Rect, width: Int, height: Int): IntArray {
        val left = floor(crop.left * width + EDGE).toInt().coerceIn(0, width - 1)
        val top = floor(crop.top * height + EDGE).toInt().coerceIn(0, height - 1)
        val right = ceil(crop.right * width - EDGE).toInt().coerceIn(left + 1, width)
        val bottom = ceil(crop.bottom * height - EDGE).toInt().coerceIn(top + 1, height)
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * The largest power of two a [sourceWidth]-pixel-wide region can be sampled down by and still be
     * at least [width] pixels wide — 1 when no width is asked for.
     *
     * Measured on the region decoded rather than the whole picture: a corner cut from a photograph is
     * shown as wide as the photograph was, and sampling it by the photograph's width would blur it.
     */
    fun sampleFor(sourceWidth: Int, width: Int): Int {
        if (width <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= width && sample < 64) sample *= 2
        return sample
    }

    /** Float noise in a fraction times a width, which must not round a whole pixel either way. */
    private const val EDGE = 1e-3f
}
