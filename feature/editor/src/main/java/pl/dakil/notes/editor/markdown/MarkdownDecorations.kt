package pl.dakil.notes.editor.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours a block's drawn geometry is made of, resolved from the theme once.
 *
 * The placeholder is pre-laid-out rather than measured in the draw phase: it is the same two
 * syllables every frame, and measuring text while drawing is the one thing in here that would
 * actually cost something.
 */
@Immutable
class MarkdownDecorationPalette(
    val codeBackground: Color,
    val codeHeader: Color,
    val chip: Color,
    val border: Color,
    val tableHeader: Color,
    val rule: Color,
    val quoteBar: Color,
    val placeholder: TextLayoutResult,
)

@Composable
fun rememberMarkdownDecorationPalette(): MarkdownDecorationPalette {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    return remember(colors, measurer) {
        MarkdownDecorationPalette(
            codeBackground = colors.surfaceContainer,
            codeHeader = colors.surfaceContainerHigh,
            chip = colors.surface,
            border = colors.outlineVariant,
            tableHeader = colors.surfaceContainerHigh,
            rule = colors.outlineVariant,
            quoteBar = colors.primary.copy(alpha = 0.5f),
            placeholder = measurer.measure(
                text = "Code",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ),
        )
    }
}

/**
 * How far a drawn block reaches above its first line and below its last.
 *
 * Its internal padding, in other words. The lines themselves are packed tight — a code block is
 * meant to look dense — so the air has to come from the box being drawn slightly larger than the
 * text it holds. Kept small deliberately: there is no gap between one line box and the next, so
 * every pixel of this is drawn over the line above, and a block written directly under a paragraph
 * would otherwise clip its last row of glyphs. The header strip is measured against this too, so
 * the button floated into it knows where the strip's top edge is.
 */
val BlockPadding = 3.dp

/**
 * Draws the shapes a Markdown document asks for, behind its text.
 *
 * Everything here is measured from the field's own [TextLayoutResult], so a box lands on the lines
 * it belongs to no matter what the text did — wrapped, resized, scrolled past. Nothing is drawn from
 * character counts, which is the whole reason this replaced a rectangle spelled out in spaces.
 *
 * Every offset is clamped before use. The layout can be a frame behind the text it is being asked
 * about, and `getLineForOffset` past the end of a layout throws; a missing border for one frame is
 * a far better outcome than a crash while somebody is typing.
 */
fun DrawScope.drawMarkdownDecorations(
    decorations: List<MdDecoration>,
    layout: TextLayoutResult,
    palette: MarkdownDecorationPalette,
) {
    for (decoration in decorations) {
        when (decoration) {
            is MdCodeBlock -> drawCodeBlock(decoration, layout, palette)
            is MdTable -> drawTable(decoration, layout, palette)
            is MdRule -> drawRule(decoration, layout, palette)
            is MdQuote -> drawQuote(decoration, layout, palette)
        }
    }
}

private fun DrawScope.drawCodeBlock(
    block: MdCodeBlock,
    layout: TextLayoutResult,
    palette: MarkdownDecorationPalette,
) {
    val first = layout.lineOf(block.start)
    val last = layout.lineOf(block.end)
    val top = layout.getLineTop(first) - BlockPadding.toPx()
    val bottom = layout.getLineBottom(last) + BlockPadding.toPx()
    val headerBottom = layout.getLineBottom(first)
    val radius = CornerRadius(10.dp.toPx())
    val outline = Size(size.width, bottom - top)

    drawRoundRect(palette.codeBackground, Offset(0f, top), outline, radius)
    // The header takes the block's own rounded corners by being the same shape, drawn again and
    // clipped to the top band — cheaper and more exact than a path with two corners rounded.
    clipRect(top = top, bottom = headerBottom) {
        drawRoundRect(palette.codeHeader, Offset(0f, top), outline, radius)
    }

    if (block.headerEnd > block.headerStart) {
        val left = layout.getHorizontalPosition(block.headerStart.coerceIn(0, layout.length), true)
        val right = layout.getHorizontalPosition(block.headerEnd.coerceIn(0, layout.length), true)
        val inset = 2.dp.toPx()
        drawRoundRect(
            color = palette.chip,
            topLeft = Offset(left - 6.dp.toPx(), top + inset),
            size = Size(right - left + 12.dp.toPx(), headerBottom - top - inset),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
    } else {
        // No language: the header line is empty and the caret can sit in it, so what goes there is
        // a hint rather than text — type over it and the fence gains a language.
        val x = layout.getHorizontalPosition(block.headerStart.coerceIn(0, layout.length), true)
        val y = top + (headerBottom - top - palette.placeholder.size.height) / 2f
        drawText(palette.placeholder, topLeft = Offset(x, y))
    }

    drawLine(
        color = palette.border,
        start = Offset(0f, headerBottom),
        end = Offset(size.width, headerBottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawRoundRect(palette.border, Offset(0f, top), outline, radius, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawTable(
    table: MdTable,
    layout: TextLayoutResult,
    palette: MarkdownDecorationPalette,
) {
    if (table.rows.isEmpty()) return
    val first = layout.lineOf(table.start)
    val last = layout.lineOf(table.end)
    val top = layout.getLineTop(first) - BlockPadding.toPx()
    val bottom = layout.getLineBottom(last) + BlockPadding.toPx()
    val stroke = 1.dp.toPx()
    val radius = CornerRadius(6.dp.toPx())

    val header = table.rows.first()
    // The outer border follows the table's own pipes where it has them: a `| a | b |` row already
    // says where its edges are, and a box drawn to the page margin instead would leave the last
    // column's rule floating short of it.
    val left = if (table.columnStops.firstOrNull() == header.first) {
        layout.centerX(header.first)
    } else {
        layout.getLineLeft(first)
    }
    val right = if (table.columnStops.lastOrNull() == header.last - 1) {
        layout.centerX(header.last - 1)
    } else {
        layout.getLineRight(first)
    }
    if (right <= left) return

    val outline = Size(right - left, bottom - top)
    drawRoundRect(palette.codeBackground, Offset(left, top), outline, radius)
    clipRect(top = top, bottom = layout.getLineBottom(layout.lineOf(table.headerEnd))) {
        drawRoundRect(palette.tableHeader, Offset(left, top), outline, radius)
    }

    for (row in table.rows.dropLast(1)) {
        val y = layout.getLineBottom(layout.lineOf(row.last))
        drawLine(palette.border, Offset(left, y), Offset(right, y), stroke)
    }

    // Column rules only when every row fits on one line. A table too wide for the page wraps, and a
    // vertical drawn through wrapped text would cut across cells it has nothing to do with — the
    // outer box and the row rules still read as a table, so that is what a wide one degrades to.
    val wrapped = table.rows.any { layout.lineOf(it.first) != layout.lineOf(it.last) }
    if (!wrapped) {
        for (stop in table.columnStops) {
            val x = layout.centerX(stop)
            if (x <= left + stroke || x >= right - stroke) continue
            drawLine(palette.border, Offset(x, top), Offset(x, bottom), stroke)
        }
    }

    drawRoundRect(palette.border, Offset(left, top), outline, radius, style = Stroke(stroke))
}

private fun DrawScope.drawRule(
    rule: MdRule,
    layout: TextLayoutResult,
    palette: MarkdownDecorationPalette,
) {
    val line = layout.lineOf(rule.offset)
    val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
    drawLine(palette.rule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

private fun DrawScope.drawQuote(
    quote: MdQuote,
    layout: TextLayoutResult,
    palette: MarkdownDecorationPalette,
) {
    val first = layout.lineOf(quote.start)
    val last = layout.lineOf(quote.end)
    val top = layout.getLineTop(first) + 1.dp.toPx()
    val bottom = layout.getLineBottom(last) - 1.dp.toPx()
    if (bottom <= top) return
    drawRoundRect(
        color = palette.quoteBar,
        topLeft = Offset(0f, top),
        size = Size(3.dp.toPx(), bottom - top),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
}

private val TextLayoutResult.length: Int get() = layoutInput.text.length

private fun TextLayoutResult.lineOf(offset: Int): Int =
    getLineForOffset(offset.coerceIn(0, length))

/** The middle of the character at [offset] — where a rule replacing it should be drawn. */
private fun TextLayoutResult.centerX(offset: Int): Float {
    val at = offset.coerceIn(0, length)
    if (at >= length) return getHorizontalPosition(at, true)
    val box = getBoundingBox(at)
    return (box.left + box.right) / 2f
}
