package pl.dakil.notes.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.canvas.SELECTION_PAD_PT
import pl.dakil.notes.editor.canvas.SelectionController
import pl.dakil.notes.editor.canvas.SelectionHandle
import pl.dakil.notes.editor.canvas.SheetTransform
import pl.dakil.notes.editor.canvas.handlePosition
import pl.dakil.notes.editor.canvas.rotateMatrix
import pl.dakil.notes.editor.canvas.scaleMatrix
import pl.dakil.notes.ink.handleCount
import pl.dakil.notes.ink.handleInto
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.ShapeSpec
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.ui.color.ColorPickerSheet
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.sheet.SheetPainter
import kotlin.math.roundToInt

/**
 * The handles and actions round a selection.
 *
 * ### Why this is not inside the sheet
 *
 * The same reason [TextBoxChrome] is not, and the reason the page headers are not: a grip laid out
 * inside the sheet's scale transform is a quarter of a grip on a phone and a saucer at four times
 * zoom. So the controls live in window pixels at their natural size, and are merely *positioned*
 * from [SheetTransform] — read at placement time, so a pan re-places them without recomposing.
 *
 * ### Why the move gesture is not here
 *
 * Dragging from inside the selection is routed by the ink overlay instead, because the overlay is
 * the editor's one input authority and the only thing a stylus talks to. A move offered only by a
 * grip would be a move a pen could not make, on the devices this app is written for. Both drive the
 * same [SelectionController]; see it for why nothing here touches the document until pointer-up.
 *
 * ### Why a lone shape gets different handles
 *
 * A stroke that came from the auto-shape recogniser carries the [ShapeSpec] it was fitted to, and
 * that spec can be edited by its apexes — which is a far more useful thing to offer for a single
 * rectangle or arc than a box to scale it in. So when the selection is exactly one such stroke the
 * frame gives way to a grip per apex. Anything else — several strokes, or one that was never
 * recognised — gets the frame.
 */
@Composable
fun SelectionChrome(
    sheet: Sheet,
    state: EditorUiState,
    viewModel: NoteViewModel,
    controller: SelectionController,
    transform: SheetTransform,
    ptToPx: Float,
    paged: Boolean,
    modifier: Modifier = Modifier,
) {
    val selection = state.selection ?: return
    if (state.isReadOnly) return

    val format = sheet.format
    val density = LocalDensity.current
    val handlePx = with(density) { HandleSize.toPx() }
    var pickingColor by remember { mutableStateOf(false) }

    // The recognised shape under the selection, if a lone one is what is selected. Asked once per
    // edit rather than per frame: the answer can only change when the document does.
    val shape = remember(sheet, state.documentVersion, selection) { loneShape(sheet, selection) }

    fun screenX(x: Float): Float = transform.contentToScreenX(x * ptToPx)

    fun screenY(y: Float): Float =
        transform.contentToScreenY(SheetPainter.documentYToStripPx(y, format, ptToPx, paged))

    /** One pixel of finger travel, in document points. */
    fun toDocument(px: Float): Float = px / (transform.zoom.coerceAtLeast(0.01f) * ptToPx)

    /** Where the selection is on the glass right now. Read at place time, never at composition. */
    fun frame(): Rect {
        val box = controller.live.mapBounds(selection.bounds.inflate(SELECTION_PAD_PT))
        return Rect(screenX(box.left), screenY(box.top), screenX(box.right), screenY(box.bottom))
    }

    // Where the finger is, in document points, accumulated from the drag deltas. One array serves
    // every grip because only one of them can be under a finger at a time, and it is a plain field
    // rather than snapshot state for the reason the whole input path is: it changes per sample.
    val cursor = remember { FloatArray(2) }

    // The outline the grips stand on, which is the ink's bounds with a little air round it. Every
    // pivot is taken from this rather than from the bare ink: the corner the user sees nailed down
    // while they drag has to be the corner of the box they are looking at.
    val padded = selection.bounds.inflate(SELECTION_PAD_PT)

    Layout(
        modifier = modifier,
        content = {
            if (shape == null) {
                for (handle in CORNERS) {
                    val (hx, hy) = handlePosition(padded, handle)
                    Grip(
                        icon = NotesIcons.Resize,
                        description = stringResource(R.string.editor_selection_scale),
                        modifier = Modifier.layoutId(handle),
                        // The glyph is a double arrow drawn along the ↗↙ diagonal, which is the
                        // way these two corners actually travel. The other pair moves along ↖↘,
                        // so the same icon is turned a quarter rather than a second one drawn —
                        // every vector added here is bytes against the size budget.
                        rotation = if (handle in TILTED_CORNERS) 90f else 0f,
                        onStart = {
                            cursor[0] = hx
                            cursor[1] = hy
                            controller.beginTransform()
                        },
                        onEnd = { controller.endTransform()?.let(viewModel::transformSelection) },
                    ) { dx, dy ->
                        cursor[0] += toDocument(dx)
                        cursor[1] += toDocument(dy)
                        controller.setLive(
                            scaleMatrix(padded, handle, hx, hy, cursor[0], cursor[1])
                        )
                    }
                }

                val (rx, ry) = handlePosition(padded, SelectionHandle.ROTATE)
                Grip(
                    icon = NotesIcons.Rotate,
                    description = stringResource(R.string.editor_selection_rotate),
                    modifier = Modifier.layoutId(SelectionHandle.ROTATE),
                    onStart = {
                        cursor[0] = rx
                        // Where the grip actually stands, not where the frame's edge is. The angle
                        // is measured from the finger, so seeding it from the edge would jerk the
                        // selection round by the height of the gap on the very first sample.
                        cursor[1] = ry + toDocument(HANDLE_GAP + handlePx * 0.5f)
                        controller.beginTransform()
                    },
                    onEnd = { controller.endTransform()?.let(viewModel::transformSelection) },
                ) { dx, dy ->
                    val fromY = ry + toDocument(HANDLE_GAP + handlePx * 0.5f)
                    cursor[0] += toDocument(dx)
                    cursor[1] += toDocument(dy)
                    controller.setLive(
                        rotateMatrix(padded, rx, fromY, cursor[0], cursor[1])
                    )
                }
            } else {
                val out = FloatArray(2)
                for (i in 0 until shape.handleCount()) {
                    shape.handleInto(i, out)
                    val hx = out[0]
                    val hy = out[1]
                    Grip(
                        icon = NotesIcons.Move,
                        description = stringResource(R.string.editor_selection_apex),
                        modifier = Modifier.layoutId(ApexId(i)),
                        size = ApexHandleSize,
                        onStart = {
                            cursor[0] = hx
                            cursor[1] = hy
                            controller.beginShape(shape, i)
                        },
                        onEnd = { controller.endShape()?.let(viewModel::replaceSelectedShape) },
                    ) { dx, dy ->
                        cursor[0] += toDocument(dx)
                        cursor[1] += toDocument(dy)
                        controller.dragShape(cursor[0], cursor[1])
                    }
                }
            }

            Surface(
                modifier = Modifier.layoutId(ActionBar),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 3.dp,
            ) {
                Row {
                    HandleButton(
                        icon = NotesIcons.Copy,
                        description = stringResource(R.string.editor_selection_duplicate),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        elevation = 0.dp,
                        onClick = viewModel::duplicateSelection,
                    )
                    HandleButton(
                        icon = NotesIcons.Palette,
                        description = stringResource(R.string.editor_selection_colour),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        elevation = 0.dp,
                        onClick = { pickingColor = true },
                    )
                    HandleButton(
                        icon = NotesIcons.Delete,
                        description = stringResource(R.string.editor_selection_delete),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.error,
                        elevation = 0.dp,
                        onClick = viewModel::deleteSelection,
                    )
                }
            }
        },
        measurePolicy = { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            val placeables = measurables.map { it.layoutId to it.measure(loose) }
            val gripPx = HandleSize.toPx()

            layout(constraints.maxWidth, constraints.maxHeight) {
                // Read here rather than at composition, so a pan, a pinch or a drag in progress
                // re-places the grips without recomposing a single one of them.
                @Suppress("UNUSED_EXPRESSION")
                controller.version
                val f = frame()
                val live = controller.liveShape ?: shape
                val out = FloatArray(2)

                for ((id, placeable) in placeables) {
                    val half = placeable.width / 2f
                    val halfH = placeable.height / 2f
                    val midX = (f.left + f.right) / 2f
                    val (cx, cy) = when (id) {
                        // Corners stand outside the frame, so they never cover the ink they are
                        // there to resize — the same rule the text box's grips follow.
                        SelectionHandle.TOP_LEFT -> f.left - half - HANDLE_GAP to f.top - halfH - HANDLE_GAP
                        SelectionHandle.TOP_RIGHT -> f.right + half + HANDLE_GAP to f.top - halfH - HANDLE_GAP
                        SelectionHandle.BOTTOM_LEFT -> f.left - half - HANDLE_GAP to f.bottom + halfH + HANDLE_GAP
                        SelectionHandle.BOTTOM_RIGHT -> f.right + half + HANDLE_GAP to f.bottom + halfH + HANDLE_GAP
                        SelectionHandle.ROTATE -> midX to f.bottom + halfH + HANDLE_GAP
                        // A full grip's height clear of the top corners rather than level with
                        // them. A selection round a single line of writing is thinner than a
                        // fingertip, and at that size the bar and the corner grips end up in the
                        // same band of glass, jostling for the same taps.
                        ActionBar -> midX to f.top - gripPx - HANDLE_GAP * 2f - halfH
                        is ApexId -> {
                            // The apex handles follow the shape as it is being pulled about, which
                            // is why the live spec is preferred over the committed one.
                            val spec = live
                            if (spec == null || id.index >= spec.handleCount()) continue
                            spec.handleInto(id.index, out)
                            screenX(out[0]) to screenY(out[1])
                        }
                        else -> continue
                    }
                    placeable.place(
                        x = (cx - placeable.width / 2f).roundToInt(),
                        y = (cy - placeable.height / 2f).roundToInt(),
                    )
                }
            }
        },
    )

    // Applied when the sheet closes rather than as the picker is dragged. Recolouring a selection
    // rewrites every stroke in it, and a multi-layer selection commits as an Edit.Batch, which
    // does not merge — so a live preview would push one unmergeable edit per frame of the drag and
    // bury the rest of the history under it.
    if (pickingColor) {
        ColorPickerSheet(
            title = stringResource(R.string.editor_selection_colour),
            initial = selectionColor(sheet, selection),
            presets = ColorCodec.INK_PRESETS,
            recents = state.recentColors,
            onColorChange = {},
            onCommit = {
                viewModel.recolorSelection(it)
                viewModel.rememberColor(it)
            },
            onDismiss = { pickingColor = false },
        )
    }
}

/**
 * The [ShapeSpec] of the one selected stroke, or null when the selection is anything else.
 *
 * A shape is one stroke by construction — the recogniser commits its outline as a single ordinary
 * stroke so it falls under the existing lasso and undo machinery with no grouping to maintain — so
 * "exactly one stroke, and it carries a spec" is the whole test.
 */
private fun loneShape(sheet: Sheet, selection: Selection): ShapeSpec? {
    if (selection.textBlocks.isNotEmpty()) return null
    val entry = selection.strokesByBlock.entries.singleOrNull() ?: return null
    val index = entry.value.singleOrNull() ?: return null
    val block = sheet.block(entry.key) as? InkBlock ?: return null
    return block.strokes.getOrNull(index)?.shape
}

/** What to open the colour picker on: the first selected stroke's colour. */
private fun selectionColor(sheet: Sheet, selection: Selection): Int {
    for ((blockId, indices) in selection.strokesByBlock) {
        val block = sheet.block(blockId) as? InkBlock ?: continue
        val stroke = indices.firstNotNullOfOrNull { block.strokes.getOrNull(it) } ?: continue
        return stroke.color
    }
    return ColorCodec.INK_PRESETS.first()
}

private val CORNERS = listOf(
    SelectionHandle.TOP_LEFT,
    SelectionHandle.TOP_RIGHT,
    SelectionHandle.BOTTOM_LEFT,
    SelectionHandle.BOTTOM_RIGHT,
)

/** The corners that lie on the other diagonal, whose arrow has to point the other way. */
private val TILTED_CORNERS = setOf(SelectionHandle.TOP_LEFT, SelectionHandle.BOTTOM_RIGHT)

/** Layout id for the grip on apex [index] of a selected shape. */
private data class ApexId(val index: Int)

/** Layout id for the duplicate/colour/delete bar. */
private object ActionBar

/**
 * Smaller than the frame's grips.
 *
 * A triangle's three apexes can be a centimetre apart on the page, and full-sized grips on them
 * would overlap into one blob that pins whichever corner Compose happened to hit-test first.
 */
private val ApexHandleSize = 24.dp
