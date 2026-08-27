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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import pl.dakil.notes.editor.R

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
    val focus: Color,
    val placeholder: TextLayoutResult,
)

@Composable
fun rememberMarkdownDecorationPalette(): MarkdownDecorationPalette {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    // Read outside the `remember`, which is not a composable scope, and keyed into it so a locale
    // change re-measures the label rather than leaving the previous language's metrics behind.
    val codeLabel = stringResource(R.string.markdown_code)
    return remember(colors, measurer, codeLabel) {
        MarkdownDecorationPalette(
            codeBackground = colors.surfaceContainer,
            codeHeader = colors.surfaceContainerHigh,
            chip = colors.surface,
            border = colors.outlineVariant,
            tableHeader = colors.surfaceContainerHigh,
            rule = colors.outlineVariant,
            quoteBar = colors.primary.copy(alpha = 0.5f),
            focus = colors.primary,
            placeholder = measurer.measure(
                text = codeLabel,
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
 * How far a code block reaches above its first line and below its last.
 *
 * Its vertical padding, drawn rather than typed: the lines of a block are packed tight, so the air
 * round the code has to come from the box being larger than the text it holds. There is no gap
 * between one line box and the next, so every pixel of this is painted over the line above — which
 * is why a block always has a blank line of its own above and below it. The header strip is
 * measured against this too, so the button floated into it knows where its top edge is.
 */
val BlockPadding = 4.dp

/**
 * How far drawn block geometry is allowed to reach past the text column.
 *
 * A border's own stroke width and nothing more. Every block — a code block, a table — is drawn
 * flush with the column the document is set in, which is what puts them all at one indent, and the
 * air inside each comes from its contents being held a column off its own border. `TextNoteScreen`
 * clips the decoration layer to this much overhang, which is what keeps a one-dp outline from
 * being shaved in half at the margins.
 */
val BlockBleed = 1.dp

/**
 * How far a table's rules are dropped past the line boxes they divide.
 *
 * A cell's height is its line's height, and a line's spare room lands above the text rather than
 * below it — that is where font metrics put it, and a text field offers no say in the matter. Left
 * alone, every cell would carry all eight dp of its padding on top and none at all underneath.
 * Moving every rule down by half of it hands that surplus to the row above, and each cell ends up
 * with the same air over its text as under it.
 *
 * Two rather than the four the padding is: a line's surplus is not all of it above the text, and
 * this is half of what is actually there — measured on screen, because the split follows the
 * ascent and descent of whatever face the cell is set in.
 */
val CellDrop = 2.dp

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
    focused: MdBorder? = null,
) {
    for (decoration in decorations) {
        when (decoration) {
            is MdCodeBlock -> drawCodeBlock(decoration, layout, palette)
            is MdTable -> drawTable(decoration, layout, palette, focused)
            is MdRule -> drawRule(decoration, layout, palette)
            is MdQuote -> drawQuote(decoration, layout, palette)
            // A checkbox is a control, not a shape: `TextNoteScreen` puts a real one over the blank
            // the plan left for it, so there is nothing to draw here.
            is MdTask -> Unit
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
    val corner = Offset(0f, top)
    val outline = Size(size.width, bottom - top)

    drawRoundRect(palette.codeBackground, corner, outline, radius)
    // The header takes the block's own rounded corners by being the same shape, drawn again and
    // clipped to the top band — cheaper and more exact than a path with two corners rounded.
    clipRect(top = top, bottom = headerBottom) {
        drawRoundRect(palette.codeHeader, corner, outline, radius)
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
    drawRoundRect(palette.border, corner, outline, radius, style = Stroke(1.dp.toPx()))
}

/**
 * One border of one table, picked out by a tap.
 *
 * [table] is the table's offset in the **source**, which is what survives a redraw: the decoration
 * itself is rebuilt from scratch every time the document changes. [index] counts borders, not
 * columns, so border 0 is the table's own left or top edge and border *n* is its right or bottom.
 */
data class MdBorder(val table: Int, val vertical: Boolean, val index: Int)

/**
 * Where a table's lines fall on screen.
 *
 * Measured once and used three times over — to draw the grid, to place the invisible strips that
 * make a border tappable, and to park the `+` button on the one that was tapped. Three separate
 * calculations of the same geometry would drift apart by a pixel and look like a bug.
 */
class MdTableGrid(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val columns: List<Float>,
    val rows: List<Float>,
)

/**
 * The grid this table wants drawn, or null when the layout cannot say yet.
 *
 * [drop] is how far every horizontal rule is moved down past the line box above it — [CellDrop] in
 * pixels — passed in rather than resolved here so the composables that place controls can ask the
 * same question a draw scope does without being one.
 */
fun MdTable.gridIn(layout: TextLayoutResult, drop: Float): MdTableGrid? {
    if (rows.isEmpty()) return null
    val first = layout.lineOf(start)
    val last = layout.lineOf(end)
    val top = layout.getLineTop(first) + drop
    val bottom = layout.getLineBottom(last) + drop

    val header = rows.first()
    // The outer border follows the table's own pipes where it has them: a `| a | b |` row already
    // says where its edges are, and the blank each of those pipes left behind is what holds the
    // cells beside it a column clear of the border.
    val left = if (columnStops.firstOrNull() == header.first) {
        layout.leftX(header.first)
    } else {
        layout.getLineLeft(first)
    }
    val right = if (columnStops.lastOrNull() == header.last - 1) {
        layout.leftX(header.last - 1)
    } else {
        layout.getLineRight(first)
    }
    if (right <= left) return null

    // Column rules only when every row fits on one line. A table too wide for the page wraps, and a
    // vertical drawn through wrapped text would cut across cells it has nothing to do with — the
    // outer box and the row rules still read as a table, so that is what a wide one degrades to.
    val wrapped = rows.any { layout.lineOf(it.first) != layout.lineOf(it.last) }
    val inner = if (wrapped) {
        emptyList()
    } else {
        columnStops.map { layout.leftX(it) }.filter { it > left + 2f && it < right - 2f }
    }

    return MdTableGrid(
        left = left,
        right = right,
        top = top,
        bottom = bottom,
        columns = listOf(left) + inner + listOf(right),
        rows = listOf(top) +
            rows.dropLast(1).map { layout.getLineBottom(layout.lineOf(it.last)) + drop } +
            bottom,
    )
}

/** The middle of [border] — the one point on it that is far from every other border. */
fun MdTableGrid.centreOf(border: MdBorder): Offset? =
    if (border.vertical) {
        columns.getOrNull(border.index)?.let { Offset(it, (top + bottom) / 2f) }
    } else {
        rows.getOrNull(border.index)?.let { Offset((left + right) / 2f, it) }
    }

/** Where the button on [border] stands, or null when the table it belonged to has moved on. */
fun List<MdDecoration>.buttonCentre(
    border: MdBorder,
    layout: TextLayoutResult,
    drop: Float,
): Offset? {
    val table = firstOrNull { it is MdTable && it.sourceStart == border.table } as? MdTable ?: return null
    return table.gridIn(layout, drop)?.centreOf(border)
}

/**
 * The border under [at], if a tap there was aiming at one.
 *
 * Hit-tested against the same grid the borders are drawn from rather than laid out as strips over
 * the table: a strip wide enough to hit reliably is also wide enough to cover most of a two-row
 * table's cells, and putting the caret in a cell has to keep working.
 */
fun List<MdDecoration>.borderAt(
    layout: TextLayoutResult,
    drop: Float,
    tolerance: Float,
    at: Offset,
): MdBorder? {
    for (decoration in this) {
        if (decoration !is MdTable) continue
        val grid = decoration.gridIn(layout, drop) ?: continue
        if (at.y < grid.top - tolerance || at.y > grid.bottom + tolerance) continue
        if (at.x < grid.left - tolerance || at.x > grid.right + tolerance) continue

        // Columns before rows, so a tap on a corner takes the vertical: it is the narrower target
        // of the two and therefore the one that was more likely aimed at.
        grid.columns.forEachIndexed { index, x ->
            if (abs(at.x - x) <= tolerance) return MdBorder(decoration.sourceStart, true, index)
        }
        grid.rows.forEachIndexed { index, y ->
            if (abs(at.y - y) <= tolerance) return MdBorder(decoration.sourceStart, false, index)
        }
    }
    return null
}

private fun DrawScope.drawTable(
    table: MdTable,
    layout: TextLayoutResult,
    palette: MarkdownDecorationPalette,
    focused: MdBorder?,
) {
    val grid = table.gridIn(layout, CellDrop.toPx()) ?: return
    val stroke = 1.dp.toPx()
    val radius = CornerRadius(6.dp.toPx())
    val outline = Size(grid.right - grid.left, grid.bottom - grid.top)

    drawRoundRect(palette.codeBackground, Offset(grid.left, grid.top), outline, radius)
    clipRect(top = grid.top, bottom = layout.getLineBottom(layout.lineOf(table.headerEnd))) {
        drawRoundRect(palette.tableHeader, Offset(grid.left, grid.top), outline, radius)
    }

    // The outer four are the box itself, drawn last so its rounded corners survive.
    for (y in grid.rows.drop(1).dropLast(1)) {
        drawLine(palette.border, Offset(grid.left, y), Offset(grid.right, y), stroke)
    }
    for (x in grid.columns.drop(1).dropLast(1)) {
        drawLine(palette.border, Offset(x, grid.top), Offset(x, grid.bottom), stroke)
    }
    drawRoundRect(palette.border, Offset(grid.left, grid.top), outline, radius, style = Stroke(stroke))

    if (focused != null && focused.table == table.sourceStart) {
        val wide = 2.dp.toPx()
        if (focused.vertical) {
            grid.columns.getOrNull(focused.index)?.let { x ->
                drawLine(palette.focus, Offset(x, grid.top), Offset(x, grid.bottom), wide)
            }
        } else {
            grid.rows.getOrNull(focused.index)?.let { y ->
                drawLine(palette.focus, Offset(grid.left, y), Offset(grid.right, y), wide)
            }
        }
    }
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

/**
 * How far left of the text column the bar beside a quotation stands.
 *
 * In the margin rather than in the text, because a margin is the one place an indent costs nothing
 * to keep: a run of spaces written into the line only ever indents the visual line it is on, so a
 * quoted sentence that wrapped had a first line clear of the bar and continuations against it. The
 * editor's own horizontal padding is 20 dp, so a 3 dp bar ten dp out sits in the middle of it with
 * clearance on both sides, and the quoted text stays in the same column as the rest of the page.
 */
private val QuoteGutter = 10.dp

private val QuoteBarWidth = 3.dp

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
        topLeft = Offset(-QuoteGutter.toPx(), top),
        size = Size(QuoteBarWidth.toPx(), bottom - top),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
}

internal val TextLayoutResult.length: Int get() = layoutInput.text.length

internal fun TextLayoutResult.lineOf(offset: Int): Int =
    getLineForOffset(offset.coerceIn(0, length))

/**
 * The leading edge of the character at [offset] — where a rule replacing it should be drawn.
 *
 * The leading edge rather than the middle, so that the blank a pipe became falls entirely on one
 * side of the rule. That blank is the padding of the cell to the right of it; the blank column
 * kept past the end of every cell's text is the padding of the cell to the left. Drawn down the
 * middle, each side would get half a column and a cell would look like it began with a space.
 */
internal fun TextLayoutResult.leftX(offset: Int): Float {
    val at = offset.coerceIn(0, length)
    if (at >= length) return getHorizontalPosition(at, true)
    return getBoundingBox(at).left
}
