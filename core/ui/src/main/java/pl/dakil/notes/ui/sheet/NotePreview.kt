package pl.dakil.notes.ui.sheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.frame
import kotlin.math.roundToInt

/**
 * A thumbnail of the first page of a sheet.
 *
 * The top of the page at its true width, not the whole page shrunk to fit: an A4 squeezed into a
 * card is handwriting at a tenth scale, which reads as grey fuzz. Cropping keeps the ink the size
 * it would be if the card were a window onto the paper, which is what makes one note distinguishable
 * from another at a glance.
 *
 * Drawn through [SheetPainter] and [StrokeRenderer] — the same painters the editor uses — so a
 * preview can never quietly disagree with the note it stands for. The paper *pattern* is the one
 * thing left out: at this scale [SheetPainter.ruleCoverage] fades the rules to almost nothing
 * anyway, and what does survive is a grey wash over the ink rather than a legible ruled page.
 */
@Composable
fun InkNotePreview(
    sheet: Sheet,
    modifier: Modifier = Modifier,
    /**
     * The decoded pictures, by [ImageBlock.src]. Decoding is the caller's: it is too slow for a
     * draw pass, and the caller knows how small a thumbnail can afford to decode them. A picture
     * with no bitmap yet is drawn as a plain tile in its place.
     */
    images: Map<String, ImageBitmap> = emptyMap(),
) {
    val format = sheet.format
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val ptToPx = if (format.width > 0f) widthPx / format.width else 1f

        // A text box lays out in the sheet's own pixels at the screen's density, so a 16 sp body is
        // 16 screen pixels tall and a point is 160/72 of them — that is, the type on the paper is
        // 7.2 pt, not 16. Scaling the measurer's density by the same ratio is what makes the words
        // in the thumbnail the size they are on the page; measuring at face value drew them more
        // than twice too large. The system font scale is deliberately left out: this is a picture
        // of the page, and the page does not resize when the user changes it.
        val pageDensity = Density(density = ptToPx * PT_PER_DP, fontScale = 1f)
        // Built by hand rather than with `rememberTextMeasurer`, which captures the composition's
        // own density — the one measured in screen pixels, not in points of paper.
        val resolver = LocalFontFamilyResolver.current
        val direction = LocalLayoutDirection.current
        val measurer = remember(resolver, pageDensity, direction) {
            TextMeasurer(resolver, pageDensity, direction)
        }
        // The same face and size a text box uses, so a note reads as a smaller copy of itself.
        val textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = Color(TEXT_COLOR),
            lineHeight = TextUnit.Unspecified,
        )
        val renderer = remember { StrokeRenderer() }

        // The page is taller than the crop and the ink runs to the foot of it, so without this the
        // strokes paint straight over the title and date below the thumbnail and out of the card.
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            drawRect(Color(format.background.color))
            // Page 0 only: everything below the first page break belongs to a page this card is
            // not showing, and the strip is one continuous coordinate space.
            clipRect(bottom = format.height * ptToPx) {
                // First, as on the sheet: a picture is under everything written on it.
                for (block in sheet.blocks) {
                    if (block !is ImageBlock || block.rect.top >= format.height) continue
                    val r = block.rect
                    val at = block.frame()
                    val bitmap = images[block.src]
                    inBlockFrame(at, at.left * ptToPx, at.top * ptToPx) {
                        if (bitmap == null) {
                            drawRect(Color(IMAGE_TILE), Offset.Zero, Size(r.width * ptToPx, r.height * ptToPx))
                        } else {
                            drawImage(
                                image = bitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize((r.width * ptToPx).roundToInt().coerceAtLeast(1), (r.height * ptToPx).roundToInt().coerceAtLeast(1)),
                                colorFilter = block.tint?.let(::imageTintFilter),
                            )
                        }
                    }
                }
                for (block in sheet.blocksInPaintOrder()) {
                    when (block) {
                        is InkBlock -> if (block.visible) {
                            with(renderer) {
                                drawStrokes(
                                    strokes = block.strokes,
                                    ptToPx = ptToPx,
                                    stripOffsetPx = 0f,
                                    docTop = 0f,
                                    docBottom = format.height,
                                )
                            }
                        }

                        is TextBlock -> drawTextBlock(block, measurer, textStyle, ptToPx, format.height)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Paints one text box as the words it holds, with the Markdown left in.
 *
 * Not a second Markdown renderer, and not the beginning of one: `MarkdownRenderer.plan()` stays the
 * only thing in the app that decides what Markdown *means*. Running it here would mean laying out
 * decorations and hidden-character edits for text a few pixels tall, to produce a picture nobody
 * reads word by word.
 */
private fun DrawScope.drawTextBlock(
    block: TextBlock,
    measurer: TextMeasurer,
    style: TextStyle,
    ptToPx: Float,
    pageHeight: Float,
) {
    if (block.markdown.isBlank()) return
    val bounds = block.worldBounds()
    if (bounds.top >= pageHeight) return
    val widthPx = (block.rect.width * ptToPx).roundToInt()
    if (widthPx <= 0) return

    val laid = measurer.measure(
        text = previewText(block.markdown),
        style = style,
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = widthPx),
    )
    val at = block.frame()
    // Through its own frame, as on the sheet; the clip is the box's own, carried with it.
    inBlockFrame(at, at.left * ptToPx, at.top * ptToPx) {
        clipRect(
            left = 0f,
            top = 0f,
            right = block.rect.width * ptToPx,
            bottom = block.rect.height.coerceAtLeast(laid.size.height / ptToPx) * ptToPx,
        ) {
            drawText(laid, topLeft = Offset.Zero)
        }
    }
}

/**
 * Points per density-independent pixel: 72/160.
 *
 * The conversion the sheet editor makes in the other direction when it derives its `ptToPx`.
 */
private const val PT_PER_DP = 72f / 160f

/** Where a picture is still being decoded: a light neutral, which reads as "something is here". */
private const val IMAGE_TILE = 0xFFE3E3E8.toInt()

/**
 * Paints one text box as the words it holds, with the Markdown left in.
 *
 * A box's lines are pruned before measuring: a payload of several megabytes written into a box —
 * an image pasted as a data URI, base64 dumped as text — costs the main thread minutes of
 * line-breaking, and the box shows a fragment no bigger than its own height. Nothing past the cap
 * is ever visible in a preview, so the tail is replaced with an ellipsis instead of measured.
 */
private fun previewText(markdown: String): String {
    if (markdown.length <= MAX_PREVIEW_LINE) return markdown
    if (markdown.length <= MAX_PREVIEW_TOTAL) {
        var long = false
        var at = 0
        while (at <= markdown.lastIndex) {
            val end = markdown.indexOf('\n', at).let { if (it < 0) markdown.length else it }
            if (end - at > MAX_PREVIEW_LINE) {
                long = true
                break
            }
            at = end + 1
        }
        if (!long) return markdown
    }
    return buildString(minOf(markdown.length, MAX_PREVIEW_TOTAL + 1)) {
        var start = 0
        while (start <= markdown.lastIndex) {
            if (length > MAX_PREVIEW_TOTAL) break
            val end = markdown.indexOf('\n', start).let { if (it < 0) markdown.length else it }
            val kept = if (end - start > MAX_PREVIEW_LINE) {
                append(markdown, start, start + MAX_PREVIEW_LINE)
                append('…')
                start + MAX_PREVIEW_LINE
            } else {
                append(markdown, start, end)
                end
            }
            start = kept + 1
            if (start <= markdown.lastIndex) append('\n')
        }
    }
}

/** The longest line a preview measures of a text box. */
private const val MAX_PREVIEW_LINE = 512

/** The longest text a preview measures of a text box. */
private const val MAX_PREVIEW_TOTAL = 16_384

/**
 * Near-black rather than the theme's `onSurface`.
 *
 * The preview is a picture of paper, and the paper is whatever colour the note says it is — so the
 * ink on it has to be readable against that, not against the card behind it.
 */
private const val TEXT_COLOR = 0xFF1A1A1AL.toInt()
