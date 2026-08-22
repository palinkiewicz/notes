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
import androidx.compose.ui.graphics.Color
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
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock
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
fun InkNotePreview(sheet: Sheet, modifier: Modifier = Modifier) {
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
                for (block in sheet.blocksInPaintOrder()) {
                    when (block) {
                        is InkBlock -> if (block.visible) {
                            with(renderer) {
                                drawStrokes(
                                    strokes = block.strokes,
                                    ptToPx = ptToPx,
                                    toStripPx = { it * ptToPx },
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
    val widthPx = (bounds.width * ptToPx).roundToInt()
    if (widthPx <= 0) return

    val laid = measurer.measure(
        text = block.markdown,
        style = style,
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = widthPx),
    )
    clipRect(
        left = bounds.left * ptToPx,
        top = bounds.top * ptToPx,
        right = bounds.right * ptToPx,
        bottom = minOf(bounds.bottom, pageHeight) * ptToPx,
    ) {
        drawText(laid, topLeft = Offset(bounds.left * ptToPx, bounds.top * ptToPx))
    }
}

/**
 * Points per density-independent pixel: 72/160.
 *
 * The conversion the sheet editor makes in the other direction when it derives its `ptToPx`.
 */
private const val PT_PER_DP = 72f / 160f

/**
 * Near-black rather than the theme's `onSurface`.
 *
 * The preview is a picture of paper, and the paper is whatever colour the note says it is — so the
 * ink on it has to be readable against that, not against the card behind it.
 */
private const val TEXT_COLOR = 0xFF1A1A1AL.toInt()
