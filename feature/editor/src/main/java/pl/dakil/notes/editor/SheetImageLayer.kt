package pl.dakil.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import pl.dakil.notes.editor.canvas.SelectionController
import pl.dakil.notes.editor.markdown.ImageLoader
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.placedBy
import pl.dakil.notes.model.frame
import pl.dakil.notes.ui.sheet.imageTintFilter
import pl.dakil.notes.ui.sheet.inBlockFrame
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
 */
@Composable
fun SheetImageLayer(
    sheet: Sheet,
    ptToPx: Float,
    paged: Boolean,
    selected: Set<BlockId>,
    controller: SelectionController,
    modifier: Modifier = Modifier,
) {
    val images = remember(sheet) { sheet.blocksInPaintOrder().filterIsInstance<ImageBlock>() }
    if (images.isEmpty()) return
    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }

    for (image in images) {
        // Decoded at twice the size it is laid out at, so it stays sharp under a moderate zoom
        // without holding a full camera frame per picture.
        val width = (image.rect.width * ptToPx * 2f).roundToInt().coerceIn(64, 2048)
        key(image.src) {
            LaunchedEffect(image.src, width) {
                ImageLoader.bitmap("sheet:${image.src}", width) { image.bytes }?.let { bitmaps[image.src] = it }
            }
        }
    }

    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val format = sheet.format
    Canvas(modifier) {
        // Read for its side effect: a drag in progress repaints the pictures it carries.
        @Suppress("UNUSED_EXPRESSION")
        controller.version
        val live = controller.live

        for (image in images) {
            val drawn = if (image.id in selected && !live.isIdentity) image.placedBy(live) else image
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

            clipRect(top = pageTop, bottom = pageFoot) {
                inBlockFrame(at, left, top) {
                    val bitmap = bitmaps[image.src]
                    if (bitmap == null) {
                        drawRect(placeholder, Offset.Zero, Size(width, height))
                    } else {
                        drawImage(
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
