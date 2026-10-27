package pl.dakil.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.editor.canvas.SelectionController
import pl.dakil.notes.editor.markdown.ImageLoader
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.croppedTo
import pl.dakil.notes.model.placedBy
import pl.dakil.notes.model.frame
import pl.dakil.notes.model.uncroppedRect
import pl.dakil.notes.ui.sheet.decodePicture
import pl.dakil.notes.ui.sheet.drawPictureRegion
import pl.dakil.notes.ui.sheet.imageTintFilter
import pl.dakil.notes.ui.sheet.inBlockFrame
import pl.dakil.notes.ui.sheet.pictureKey
import pl.dakil.notes.ui.sheet.SheetPainter
import kotlin.math.roundToInt

/**
 * The sheet's pictures, under its text and its ink.
 *
 * Under both because that is where a picture on paper is: written on, never written under. Each is
 * clipped at the foot of its own page, the rule a text box follows, so what is painted matches what
 * prints. A selected picture being dragged follows [controller]'s live transform from inside the
 * draw lambda, so a drag repaints this layer without recomposing it — the same arrangement the ink
 * and the text boxes use.
 *
 * A cropped picture is decoded only as far as its crop — see [decodePicture] — so what is held here
 * is what is shown. The one exception is the picture [cropping] names: it is decoded whole, once,
 * for as long as it is being cropped, because the parts cut away are shown faintly round it and the
 * crop moves with every sample of a drag, far too often to decode again each time.
 */
@Composable
fun SheetImageLayer(
    sheet: Sheet,
    ptToPx: Float,
    paged: Boolean,
    selected: Set<BlockId>,
    cropping: BlockId?,
    controller: SelectionController,
    modifier: Modifier = Modifier,
) {
    val images = remember(sheet) { sheet.blocksInPaintOrder().filterIsInstance<ImageBlock>() }
    if (images.isEmpty()) return
    // By source and crop, since a bitmap holds only the part its crop shows.
    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }

    for (image in images) {
        // Decoded at twice the size it is laid out at, so it stays sharp under a moderate zoom
        // without holding a full camera frame per picture.
        val width = (image.rect.width * ptToPx * 2f).roundToInt().coerceIn(64, 2048)
        val name = pictureKey(image)
        key(name) {
            LaunchedEffect(name, width) {
                ImageLoader.bitmap("sheet:${image.src}", width, image.crop) { image.bytes }
                    ?.let { bitmaps[name] = it }
            }
        }
    }
    // A crop that has been changed leaves its old bitmap behind; let go of what no block shows.
    LaunchedEffect(images) {
        val shown = images.mapTo(HashSet()) { pictureKey(it) }
        bitmaps.keys.retainAll(shown)
    }

    val croppingImage = cropping?.let { id -> images.firstOrNull { it.id == id } }
    var whole by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    // Keyed by the source alone: a committed crop changes the block, not the picture under it.
    LaunchedEffect(croppingImage?.src) {
        whole = null
        val image = croppingImage ?: return@LaunchedEffect
        val width = (image.uncroppedRect().width * ptToPx * 2f).roundToInt().coerceIn(64, 2048)
        whole = withContext(Dispatchers.Default) { decodePicture(image.bytes, ImageBlock.UNCROPPED, width) }
            ?.let { image.src to it }
    }

    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val format = sheet.format
    Canvas(modifier) {
        // Read for its side effect: a drag in progress repaints the pictures it carries.
        @Suppress("UNUSED_EXPRESSION")
        controller.version
        val live = controller.live

        for (image in images) {
            var drawn = if (image.id in selected && !live.isIdentity) image.placedBy(live) else image
            val wholeBitmap = whole?.takeIf { image.id == cropping && it.first == image.src }?.second
            if (image.id == cropping) controller.liveCrop?.let { drawn = drawn.croppedTo(it) }
            val r = drawn.rect
            val at = drawn.frame()
            val left = at.left * ptToPx
            val top = SheetPainter.documentYToStripPx(at.top, format, ptToPx, paged)
            val width = r.width * ptToPx
            val height = r.height * ptToPx
            val page = sheet.pageAt(drawn.worldBounds().top)
            val pageTop = SheetPainter.documentYToStripPx(page * format.height, format, ptToPx, paged)
            // Just short of the next page, which in paged mode starts after the gap between the two.
            val pageFoot = SheetPainter.documentYToStripPx((page + 1) * format.height - 0.01f, format, ptToPx, paged)

            // What the crop has cut away, faint and not held to the page, so the user can see what
            // there is to bring back — including past the foot of the page, where it could be
            // dragged to. Held to the strip's sides all the same, as the picture itself is.
            if (wholeBitmap != null) {
                clipRect(right = size.width, bottom = Float.MAX_VALUE) {
                    drawWhole(drawn, wholeBitmap, format, ptToPx, paged)
                }
            }

            clipRect(top = pageTop, bottom = pageFoot) {
                inBlockFrame(at, left, top) {
                    val bitmap = bitmaps[pictureKey(drawn)]
                    when {
                        wholeBitmap != null -> drawPictureRegion(wholeBitmap, drawn.crop, width, height, drawn.tint)
                        bitmap == null -> drawRect(placeholder, Offset.Zero, Size(width, height))
                        else -> drawImage(
                            image = bitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
                            colorFilter = drawn.tint?.let(::imageTintFilter),
                        )
                    }
                }
            }
        }
    }
}

/** The whole of [image], cut-away parts and all, drawn faintly where it would be if uncropped. */
private fun DrawScope.drawWhole(image: ImageBlock, bitmap: ImageBitmap, format: PageFormat, ptToPx: Float, paged: Boolean) {
    val full = image.croppedTo(ImageBlock.UNCROPPED)
    val at = full.frame()
    inBlockFrame(at, at.left * ptToPx, SheetPainter.documentYToStripPx(at.top, format, ptToPx, paged)) {
        drawPictureRegion(
            bitmap, ImageBlock.UNCROPPED, full.rect.width * ptToPx, full.rect.height * ptToPx, image.tint,
            alpha = CUT_AWAY_ALPHA,
        )
    }
}

/** Faint enough to read as "not part of the picture", plain enough to see what is there. */
private const val CUT_AWAY_ALPHA = 0.35f
