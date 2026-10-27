package pl.dakil.notes.ui.sheet

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import pl.dakil.notes.model.ColorCodec

/**
 * How a picture with a [pl.dakil.notes.model.ImageBlock.tint] is shown: in that colour, on white.
 *
 * Dark becomes the colour and light stays light, by brightness — so a scanned page of handwriting
 * comes out written in the chosen ink, and a photograph as a duotone of it. The same filter the
 * editor, the library card and the export all use, so a recoloured picture looks one way wherever
 * it is drawn.
 */
fun imageTintFilter(argb: Int): ColorFilter {
    val r = ColorCodec.red(argb) / 255f
    val g = ColorCodec.green(argb) / 255f
    val b = ColorCodec.blue(argb) / 255f
    // Each channel is `colour + (1 − colour) × luminance`: the colour at black, white at white.
    fun row(c: Float) = floatArrayOf(
        (1 - c) * LUMA_R, (1 - c) * LUMA_G, (1 - c) * LUMA_B, 0f, c * 255f,
    )
    return ColorFilter.colorMatrix(
        ColorMatrix(row(r) + row(g) + row(b) + floatArrayOf(0f, 0f, 0f, 1f, 0f)),
    )
}

// Rec. 709, the weights `ColorCodec.luminance` and the rest of the app judge brightness by.
private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

