package pl.dakil.notes.ui.sheet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import pl.dakil.notes.model.ImageCrop
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.Rect
import kotlin.math.roundToInt

/**
 * The part of a picture that [crop] shows, decoded at roughly [widthPx] wide — or whole, at full
 * size, when no width is given.
 *
 * Only that part is ever turned into pixels. A crop of a corner of a photograph decodes the corner
 * through [BitmapRegionDecoder], which for a JPEG reads only the blocks the corner touches; and the
 * bitmap handed back holds nothing but what is shown, so a cache full of cropped pictures is not
 * secretly a cache full of whole ones. A format the region decoder refuses is decoded whole and
 * cut down before it is returned — slower, never larger.
 */
fun decodePicture(bytes: ByteArray, crop: Rect, widthPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    if (crop == ImageBlock.UNCROPPED) {
        val options = BitmapFactory.Options().apply { inSampleSize = ImageCrop.sampleFor(width, widthPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    val (left, top, right, bottom) = ImageCrop.pixelRegion(crop, width, height)
    val region = android.graphics.Rect(left, top, right, bottom)
    val sample = ImageCrop.sampleFor(right - left, widthPx)
    regionDecoder(bytes)?.let { decoder ->
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            decoder.decodeRegion(region, options)?.let { return it.asImageBitmap() }
        } catch (_: RuntimeException) {
            // Fall through to the whole-picture decode below.
        } finally {
            decoder.recycle()
        }
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // The sampled bitmap is smaller than the picture by however much the decoder chose to sample.
    val sx = full.width.toFloat() / width
    val sy = full.height.toFloat() / height
    val x = (left * sx).toInt().coerceIn(0, full.width - 1)
    val y = (top * sy).toInt().coerceIn(0, full.height - 1)
    val w = (((right - left) * sx).roundToInt()).coerceIn(1, full.width - x)
    val h = (((bottom - top) * sy).roundToInt()).coerceIn(1, full.height - y)
    val cut = Bitmap.createBitmap(full, x, y, w, h)
    if (cut !== full) full.recycle()
    return cut.asImageBitmap()
}

/**
 * [decodePicture] for a page that is printed rather than shown: no wider than [widthPx] exactly,
 * and with any Ultra HDR gain map dropped.
 *
 * The gain map is what a phone camera's HDR photograph carries beside its ordinary pixels, and the
 * platform draws such a bitmap through a shader that mixes the two. A PDF cannot hold that shader,
 * so the PDF backend painted it as a bitmap of the whole page at 72 dpi instead — every photograph
 * on the page blurred and the page around it with them, at any quality setting. A printed page has
 * no HDR to keep, so nothing is lost by drawing only the ordinary pixels, which embed whole.
 */
fun decodePrintedPicture(bytes: ByteArray, crop: Rect, widthPx: Int): ImageBitmap? =
    decodePicture(bytes, crop, widthPx)?.shrunkToWidth(widthPx)?.withoutGainmap()

private fun ImageBitmap.withoutGainmap(): ImageBitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val bitmap = asAndroidBitmap()
        if (bitmap.hasGainmap()) bitmap.gainmap = null
    }
    return this
}

/**
 * This picture at no more than [widthPx] wide, keeping its proportions.
 *
 * [decodePicture] samples by halves, so a picture asked for at 1000 pixels can come back at nearly
 * 2000 — fine on screen, but a PDF stores every pixel it is handed, and a size the user chose to
 * shrink to should be the size the file holds. The receiver is recycled when a smaller copy replaces it.
 */
fun ImageBitmap.shrunkToWidth(widthPx: Int): ImageBitmap {
    if (widthPx <= 0 || width <= widthPx) return this
    val source = asAndroidBitmap()
    val height = (height.toLong() * widthPx / width).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(source, widthPx, height, true)
    if (scaled !== source) source.recycle()
    return scaled.asImageBitmap()
}

/** A region decoder for [bytes], or null for a format it does not read. */
private fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    }
} catch (_: Exception) {
    null
}

/**
 * The [crop] of a whole decoded picture, drawn into [width] × [height] at the origin.
 *
 * For the one picture that is decoded whole: the one being cropped, whose cut-away parts are shown
 * faintly round it and whose crop moves per sample of a drag — too often to decode again each time.
 */
fun DrawScope.drawPictureRegion(
    bitmap: ImageBitmap,
    crop: Rect,
    width: Float,
    height: Float,
    tint: Int?,
    alpha: Float = 1f,
) {
    val left = (crop.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
    val top = (crop.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(left, top),
        srcSize = IntSize(
            (crop.width * bitmap.width).roundToInt().coerceIn(1, bitmap.width - left),
            (crop.height * bitmap.height).roundToInt().coerceIn(1, bitmap.height - top),
        ),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        colorFilter = tint?.let(::imageTintFilter),
    )
}
