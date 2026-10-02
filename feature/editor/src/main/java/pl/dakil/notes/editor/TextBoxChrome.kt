package pl.dakil.notes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.R
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.editor.canvas.SheetTransform
import pl.dakil.notes.editor.markdown.MarkdownEditor
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.ui.icons.NotesIcons
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The box the user is working on: its frame, its handles, and — while there is a caret in it — the
 * text field itself.
 *
 * ### Why this is not inside the sheet
 *
 * The same reason the page headers are not: the sheet is drawn through a scale transform, and a
 * note opens at roughly a quarter scale on a phone. A handle laid out inside it would be a quarter
 * of a handle — a 40 dp grip becomes 10 dp of glass, which nobody can hit — and at four times scale
 * it would be a saucer covering the words it is meant to be adjusting. So the chrome lives in the
 * window's coordinate space at its natural size and is *positioned* from the transform, which also
 * means a handle can hang outside the box it belongs to without being clipped by it.
 *
 * ### Why the live field is out here too
 *
 * The text *is* scaled with the paper, so at first sight it belongs in the sheet with the rest of
 * the document — and that is where every box that nobody is typing in is drawn. The one being typed
 * in cannot be. Compose puts the selection handles and the magnifier in windows of their own,
 * positioned by taking the field's place on the glass and adding the caret's offset *inside* the
 * field to it. The first of those two goes through the scale transform and the second does not, so
 * inside a sheet at a quarter scale the drag handle sits four times too far along the line — at the
 * end of a sentence it is most of a page away from the caret it belongs to.
 *
 * So the field is laid out here instead, in real screen pixels, where that sum is right. It is
 * still the paper's text and still the paper's size: [LocalDensity] is scaled by the zoom for the
 * subtree, so 16 sp of body text measures 16 sp *of scaled paper*, and every dp inside the editor —
 * a checkbox, the padding round a code block — scales with it. What the reader sees is what the
 * sheet would have drawn; what Compose measures is honest screen pixels.
 *
 * ### Why only the width can be dragged
 *
 * A box is as tall as what is written in it. Offering a height handle would be offering a number
 * the very next keystroke overwrites — the box has to grow to hold the text, so a height the user
 * set could not survive. Width is the measurement that is genuinely theirs: it decides where the
 * lines break, and nothing else in the document has an opinion about it.
 */
@Composable
fun TextBoxChrome(
    sheet: Sheet,
    state: EditorUiState,
    viewModel: NoteViewModel,
    transform: SheetTransform,
    ptToPx: Float,
    paged: Boolean,
    /** Where the caret is in the strip, so whoever owns the scrolling can bring it into view. */
    onCaretBand: (top: Float, bottom: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = state.activeTextBlock ?: return
    val box = sheet.block(id) as? TextBlock ?: return
    if (state.isReadOnly) return

    val format = sheet.format
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.primary
    val dash = with(density) { 4.dp.toPx() }

    /** Where the box is on the glass right now. Read at draw and place time, never at composition. */
    fun frame(): Rect {
        val top = SheetPainter.documentYToStripPx(box.rect.top, format, ptToPx, paged)
        return Rect(
            left = transform.contentToScreenX(box.rect.left * ptToPx),
            top = transform.contentToScreenY(top),
            right = transform.contentToScreenX(box.rect.right * ptToPx),
            bottom = transform.contentToScreenY(top + box.rect.height * ptToPx),
        )
    }

    /** One pixel of finger travel, in document points. */
    fun toDocument(px: Float): Float = px / (transform.zoom.coerceAtLeast(0.01f) * ptToPx)

    Box(modifier) {
        if (state.editingTextBlock == id) {
            EditableTextBox(
                box = box,
                sheet = sheet,
                viewModel = viewModel,
                zoom = transform.zoom,
                ptToPx = ptToPx,
                paged = paged,
                onCaretBand = onCaretBand,
                // Read at placement, so a pan moves the field without measuring a line of text again.
                offset = { frame().let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) } },
            )
        }

        Layout(
            modifier = Modifier.fillMaxSize().drawBehind {
                val f = frame()
                drawRect(
                    color = outline,
                    topLeft = Offset(f.left, f.top),
                    size = Size(f.width.coerceAtLeast(1f), f.height.coerceAtLeast(1f)),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                    ),
                )
            },
            content = {
                Grip(
                    icon = NotesIcons.Move,
                    description = stringResource(R.string.editor_text_box_move),
                    modifier = Modifier.layoutId(Handle.MOVE),
                    onStart = { viewModel.beginTextBlockDrag(id) },
                    onEnd = viewModel::endTextBlockDrag,
                ) { dx, dy ->
                    val ddx = toDocument(dx)
                    val ddy = toDocument(dy)
                    viewModel.dragTextBlockTo(
                        id,
                        Rect(
                            box.rect.left + ddx, box.rect.top + ddy,
                            box.rect.right + ddx, box.rect.bottom + ddy,
                        ),
                    )
                }
                Grip(
                    icon = NotesIcons.ResizeWidth,
                    description = stringResource(R.string.editor_text_box_width_left),
                    modifier = Modifier.layoutId(Handle.LEFT),
                    onStart = { viewModel.beginTextBlockDrag(id) },
                    onEnd = viewModel::endTextBlockDrag,
                ) { dx, _ ->
                    viewModel.dragTextBlockTo(id, box.rect.copy(left = box.rect.left + toDocument(dx)))
                }
                Grip(
                    icon = NotesIcons.ResizeWidth,
                    description = stringResource(R.string.editor_text_box_width_right),
                    modifier = Modifier.layoutId(Handle.RIGHT),
                    onStart = { viewModel.beginTextBlockDrag(id) },
                    onEnd = viewModel::endTextBlockDrag,
                ) { dx, _ ->
                    viewModel.dragTextBlockTo(id, box.rect.copy(right = box.rect.right + toDocument(dx)))
                }
                HandleButton(
                    icon = NotesIcons.Delete,
                    description = stringResource(R.string.editor_text_box_delete),
                    modifier = Modifier.layoutId(Handle.DELETE),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { viewModel.deleteTextBlock(id) },
                )
            },
            measurePolicy = { measurables, constraints ->
                val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
                val placeables = measurables.map { it.layoutId to it.measure(loose) }

                layout(constraints.maxWidth, constraints.maxHeight) {
                    val f = frame()
                    for ((handle, placeable) in placeables) {
                        val half = placeable.width / 2f
                        // Every handle stands *outside* the box it belongs to. Centring the width grips
                        // on the edges would be the obvious thing and is wrong: a box is often a single
                        // line, which at a page-width zoom is thinner than a fingertip, so a handle on
                        // the edge covers the first word of what the user came here to read.
                        val middle = (f.top + f.bottom) / 2f
                        val centre = when (handle) {
                            Handle.MOVE -> Offset(f.left + half, f.top - half - HANDLE_GAP)
                            Handle.DELETE -> Offset(f.right - half, f.top - half - HANDLE_GAP)
                            Handle.LEFT -> Offset(f.left - half - HANDLE_GAP, middle)
                            else -> Offset(f.right + half + HANDLE_GAP, middle)
                        }
                        placeable.place(
                            x = (centre.x - placeable.width / 2f).roundToInt(),
                            y = (centre.y - placeable.height / 2f).roundToInt(),
                        )
                    }
                }
            },
        )
    }
}

/**
 * The field, in screen pixels, standing exactly where the box is.
 *
 * Measured against the box's width *on the glass* and given a density scaled by the zoom, so the
 * text comes out the size the sheet would have drawn it at. The height it needs goes back into the
 * document through `reportTextHeight`, which is what makes the box grow as it is typed into — and
 * what the dashed frame around it is drawn from.
 *
 * Clipped at the foot of its page for the reason every box is: paper does not grow, and what would
 * be painted past the bottom edge is in the printer's next slice. See [TextBoxLayer].
 */
@Composable
private fun EditableTextBox(
    box: TextBlock,
    sheet: Sheet,
    viewModel: NoteViewModel,
    zoom: Float,
    ptToPx: Float,
    paged: Boolean,
    onCaretBand: (top: Float, bottom: Float) -> Unit,
    offset: Density.() -> IntOffset,
) {
    val density = LocalDensity.current
    val scale = zoom.coerceAtLeast(0.01f)
    val format = sheet.format
    val widthPx = (box.rect.width * ptToPx * scale).roundToInt().coerceAtLeast(1)
    val roomPx = (((sheet.pageOf(box) + 1) * format.height - box.rect.top) * ptToPx * scale)
        .coerceAtLeast(1f)
    val stripTop = SheetPainter.documentYToStripPx(box.rect.top, format, ptToPx, paged)
    var hidden by remember(box.id) { mutableStateOf(0f) }

    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
    ) {
        Layout(
            modifier = Modifier
                // Measured with no width or height limit of its own, and only then cut down to the
                // window. A box zoomed past the width of the screen measures wider than the
                // constraints it was handed, and Compose answers an over-sized child by reporting
                // the constrained size and *centring* the real layout on it — half the overflow to
                // the left, growing with every further zoom, until the words walk off the glass.
                // The paper is allowed to be wider than the window; the node standing in for it
                // here is not.
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(offset)
                .clipToBounds()
                .drawBehind { if (hidden > 0f) drawOverflowMarker() },
            content = {
                MarkdownEditor(
                    state = viewModel.textField,
                    sourceMode = false,
                    // The same one the formatting bar arms. See [PendingStyles].
                    pending = viewModel.pendingStyles,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.editor_type_here),
                    attributes = true,
                    images = viewModel,
                    // Back into the strip's own coordinates, because the thing that has to move to
                    // reveal the caret is the whole sheet.
                    onCaretBounds = { top, bottom ->
                        onCaretBand(stripTop + top / scale, stripTop + bottom / scale)
                    },
                )
            },
        ) { measurables, _ ->
            val natural = measurables.first().measure(
                Constraints(minWidth = widthPx, maxWidth = widthPx, maxHeight = Constraints.Infinity),
            )
            val height = min(natural.height.toFloat(), roomPx).roundToInt()
            hidden = natural.height - height.toFloat()
            viewModel.reportTextHeight(box.id, natural.height / scale / ptToPx)
            layout(widthPx, height) { natural.place(0, 0) }
        }
    }
}

private enum class Handle { MOVE, LEFT, RIGHT, DELETE }
