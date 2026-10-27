package pl.dakil.notes.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import pl.dakil.notes.editor.canvas.turnAngle
import pl.dakil.notes.ink.handleCount
import pl.dakil.notes.ink.handleInto
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.ImageCrop
import pl.dakil.notes.model.MIN_IMAGE_SIDE
import pl.dakil.notes.model.croppedTo
import pl.dakil.notes.model.pictureDelta
import pl.dakil.notes.model.uncroppedRect
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
 *
 * ### Why a picture being cropped gets grips on its own edges
 *
 * A crop is taken along the picture's edges, not the page's, so on a turned picture the frame's
 * upright box would put the grips at corners the picture does not have. Each drag commits as it is
 * let go, one undo step like any grip's, which is why leaving crop mode has nothing to apply.
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
    // Which colour the picker is open on, if it is: the outline (every stroke's one colour) or the
    // inside of the selected marker ink.
    var pickingColor by remember { mutableStateOf<InkTarget?>(null) }

    // The first selected marker stroke's inside colour, or null when the selection has no marker
    // ink — in which case there is no inside to offer a button for.
    val insideColor = remember(sheet, state.documentVersion, selection) { selectionInside(sheet, selection) }

    // The recognised shape under the selection, if a lone one is what is selected. Asked once per
    // edit rather than per frame: the answer can only change when the document does.
    val shape = remember(sheet, state.documentVersion, selection) { loneShape(sheet, selection) }

    // Whether the fill button empties the selection or fills it. Everything-or-nothing, because a
    // button that flips each stroke separately cannot say in advance what it is about to do.
    val allFilled = remember(sheet, state.documentVersion, selection) { allFilled(sheet, selection) }

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

    // How far below the frame's bottom edge the rotate grip stands, from the edge to the grip's
    // centre, in window pixels: half the bottom edge grip's touch area, a gap, and half of itself —
    // clear of that grip rather than overlapping it.
    val rotateDropPx = handlePx + HANDLE_GAP

    // Composition, not placement, decides what is shown while the selection turns — see
    // SelectionController.grip for why that flips only twice a gesture.
    val rotating = controller.isRotating

    // The picture being cropped, when the selection is in crop mode. Its grips crop it in place of
    // scaling it, and stand on its own edges — turned with it — rather than on the box round it.
    val cropImage = remember(sheet, state.documentVersion, selection) {
        selection.takeIf { it.cropping }?.loneImage?.let { sheet.block(it) as? ImageBlock }
    }

    /** The picture as the crop grip in hand has it right now. Read at place and draw time only. */
    fun liveCropped(image: ImageBlock): ImageBlock = controller.liveCrop?.let(image::croppedTo) ?: image

    /** Where [handle] stands on [image]'s own edges, on the glass. */
    fun cropGripAt(image: ImageBlock, handle: SelectionHandle): Pair<Float, Float> {
        val (x, y) = handlePosition(image.rect, handle)
        return screenX(image.transform.mapX(x, y)) to screenY(image.transform.mapY(x, y))
    }

    Layout(
        modifier = modifier,
        content = {
            if (cropImage != null) {
                val full = cropImage.uncroppedRect()
                // The least a crop can leave, in fractions of the picture: the size a scale stops at.
                val minU = (MIN_IMAGE_SIDE / full.width).coerceAtMost(1f)
                val minV = (MIN_IMAGE_SIDE / full.height).coerceAtMost(1f)
                val delta = remember { FloatArray(2) }
                for (handle in FRAME_GRIPS) {
                    FrameGrip(
                        description = stringResource(R.string.editor_selection_crop_edge),
                        modifier = Modifier.layoutId(CropGrip(handle)),
                        onStart = {
                            // Where the grip is, in fractions of the picture: the finger is followed
                            // from there, so the edge under it does not jump to the finger's centre.
                            val (u, v) = handlePosition(cropImage.crop, handle)
                            cursor[0] = u
                            cursor[1] = v
                            controller.beginCrop(cropImage.crop)
                        },
                        onEnd = { controller.endCrop()?.let(viewModel::cropSelectedImage) },
                    ) { dx, dy ->
                        cropImage.pictureDelta(toDocument(dx), toDocument(dy), delta)
                        cursor[0] += delta[0]
                        cursor[1] += delta[1]
                        controller.dragCrop(
                            ImageCrop.resize(
                                cropImage.crop,
                                left = handle.movesLeft, top = handle.movesTop,
                                right = handle.movesRight, bottom = handle.movesBottom,
                                u = cursor[0], v = cursor[1], minU = minU, minV = minV,
                            )
                        )
                    }
                }
            } else if (shape == null) {
                // Everything but the rotate grip steps aside while the selection turns: the frame
                // is drawn turned with the ink then, and grips standing on the box round it would
                // mark corners that are not the selection's.
                if (!rotating) {
                    for (handle in FRAME_GRIPS) {
                        val (hx, hy) = handlePosition(padded, handle)
                        FrameGrip(
                            description = stringResource(
                                if (handle.isEdge) R.string.editor_selection_stretch
                                else R.string.editor_selection_scale
                            ),
                            modifier = Modifier.layoutId(handle),
                            onStart = {
                                cursor[0] = hx
                                cursor[1] = hy
                                controller.beginTransform(handle)
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
                }

                val (rx, ry) = handlePosition(padded, SelectionHandle.ROTATE)
                Grip(
                    icon = NotesIcons.Rotate,
                    description = stringResource(R.string.editor_selection_rotate),
                    modifier = Modifier.layoutId(SelectionHandle.ROTATE),
                    // Turned with the selection, so the arrow keeps pointing round the way it goes.
                    rotation = {
                        @Suppress("UNUSED_EXPRESSION")
                        controller.version
                        Math.toDegrees(controller.live.turnAngle().toDouble()).toFloat()
                    },
                    onStart = {
                        cursor[0] = rx
                        // Where the grip actually stands, not where the frame's edge is. The angle
                        // is measured from the finger, so seeding it from the edge would jerk the
                        // selection round by the height of the gap on the very first sample.
                        cursor[1] = ry + toDocument(rotateDropPx)
                        controller.beginTransform(SelectionHandle.ROTATE)
                    },
                    onEnd = { controller.endTransform()?.let(viewModel::transformSelection) },
                ) { dx, dy ->
                    val fromY = ry + toDocument(rotateDropPx)
                    cursor[0] += toDocument(dx)
                    cursor[1] += toDocument(dy)
                    controller.setLive(
                        rotateMatrix(padded, rx, fromY, cursor[0], cursor[1])
                    )
                }

                if (controller.grip != null) {
                    TransformReadout(controller, Modifier.layoutId(Readout))
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

            if (cropImage != null) {
                Surface(
                    modifier = Modifier.layoutId(ActionBar),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 3.dp,
                ) {
                    Row {
                        // Nothing to apply: every drag of a crop grip was committed as it was let go.
                        BarButton(NotesIcons.Check, R.string.editor_selection_crop_done, viewModel::finishCrop)
                        if (cropImage.isCropped) {
                            BarButton(NotesIcons.Image, R.string.editor_selection_crop_reset) {
                                viewModel.cropSelectedImage(ImageBlock.UNCROPPED)
                            }
                        }
                    }
                }
            } else if (!rotating) {
                Surface(
                    modifier = Modifier.layoutId(ActionBar),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 3.dp,
                ) {
                    Row {
                        BarButton(NotesIcons.Copy, R.string.editor_selection_duplicate, viewModel::duplicateSelection)
                        BarButton(NotesIcons.Palette, R.string.editor_selection_colour) {
                            pickingColor = InkTarget.OUTLINE
                        }
                        if (insideColor != null) {
                            InsideColorButton(insideColor) { pickingColor = InkTarget.INSIDE }
                        }
                        // The way back from the colour button for a picture: its own colours again.
                        if (selection.images.any { (sheet.block(it) as? ImageBlock)?.tint != null }) {
                            BarButton(NotesIcons.Close, R.string.editor_selection_remove_tint, viewModel::clearImageTint)
                        }
                        // Only for a picture on its own: a crop of ink, or of a picture and the
                        // words beside it, is not something one window onto one picture can be.
                        if (selection.loneImage != null) {
                            BarButton(NotesIcons.Crop, R.string.editor_selection_crop, viewModel::startCrop)
                        }
                        // Offered only where it means something: a text box has no interior to
                        // paint, and a selection of nothing but boxes would get a button that does
                        // nothing.
                        if (selection.strokeCount > 0) {
                            BarButton(
                                icon = if (allFilled) NotesIcons.FillOff else NotesIcons.Fill,
                                description = if (allFilled) R.string.editor_selection_unfill
                                else R.string.editor_selection_fill,
                            ) { viewModel.setSelectionFilled(!allFilled) }
                        }
                        BarButton(NotesIcons.FlipHorizontal, R.string.editor_selection_mirror_horizontal) {
                            viewModel.mirrorSelection(horizontal = true)
                        }
                        BarButton(NotesIcons.FlipVertical, R.string.editor_selection_mirror_vertical) {
                            viewModel.mirrorSelection(horizontal = false)
                        }
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
            }
        },
        measurePolicy = { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            val placeables = measurables.map { it.layoutId to it.measure(loose) }
            val touchPx = HandleSize.toPx()
            val barHeight = placeables.firstOrNull { it.first == ActionBar }?.second?.height ?: 0

            layout(constraints.maxWidth, constraints.maxHeight) {
                // Read here rather than at composition, so a pan, a pinch or a drag in progress
                // re-places the grips without recomposing a single one of them.
                @Suppress("UNUSED_EXPRESSION")
                controller.version
                // While cropping, the frame is the picture as the crop has it, so the bar rides
                // above the edge being dragged rather than above where the edge was.
                val cropped = cropImage?.let(::liveCropped)
                val f = cropped?.let {
                    val box = it.worldBounds().inflate(SELECTION_PAD_PT)
                    Rect(screenX(box.left), screenY(box.top), screenX(box.right), screenY(box.bottom))
                } ?: frame()
                // The same "no room, no edge grip" rule as the frame's, measured along the picture's
                // own edges, which is what the crop grips stand on.
                val cropRoom = cropped?.let {
                    val (lx, ly) = cropGripAt(it, SelectionHandle.TOP_LEFT)
                    val (rx, ry) = cropGripAt(it, SelectionHandle.TOP_RIGHT)
                    val (bx, by) = cropGripAt(it, SelectionHandle.BOTTOM_LEFT)
                    kotlin.math.hypot(rx - lx, ry - ly) to kotlin.math.hypot(bx - lx, by - ly)
                }
                val live = controller.liveShape ?: shape
                val out = FloatArray(2)
                val midX = (f.left + f.right) / 2f
                val midY = (f.top + f.bottom) / 2f
                // An edge grip needs its edge to be long enough to stand clear of both corners'
                // touch areas; on a selection round one short line of writing it would sit on top
                // of them, and a touch there would go to whichever grip Compose tried first. Left
                // unplaced, it is not hit-tested either, and zooming in brings it back.
                val roomAcross = f.right - f.left >= touchPx * 2f
                val roomDown = f.bottom - f.top >= touchPx * 2f
                val barCentre = f.top - touchPx / 2f - HANDLE_GAP - barHeight / 2f

                for ((id, placeable) in placeables) {
                    val halfH = placeable.height / 2f
                    val (cx, cy) = when (id) {
                        // Exactly on the frame, as in any drawing program: the frame is what is
                        // being resized, and the grip is where on it to take hold.
                        SelectionHandle.TOP_LEFT -> f.left to f.top
                        SelectionHandle.TOP_RIGHT -> f.right to f.top
                        SelectionHandle.BOTTOM_LEFT -> f.left to f.bottom
                        SelectionHandle.BOTTOM_RIGHT -> f.right to f.bottom
                        SelectionHandle.TOP -> if (roomAcross) midX to f.top else continue
                        SelectionHandle.BOTTOM -> if (roomAcross) midX to f.bottom else continue
                        SelectionHandle.LEFT -> if (roomDown) f.left to midY else continue
                        SelectionHandle.RIGHT -> if (roomDown) f.right to midY else continue
                        SelectionHandle.ROTATE -> if (controller.isRotating) {
                            // Carried round with the selection, so it stays under the finger that
                            // is turning it instead of sliding along the box round the turned ink.
                            val (rx, ry) = handlePosition(padded, SelectionHandle.ROTATE)
                            val gy = ry + toDocument(rotateDropPx)
                            val m = controller.live
                            screenX(m.mapX(rx, gy)) to screenY(m.mapY(rx, gy))
                        } else {
                            midX to f.bottom + rotateDropPx
                        }
                        // A full grip clear of the top edge, where nothing but the edge grips are.
                        ActionBar -> midX to barCentre
                        // Over the bar while it is there, and in its place while the selection
                        // turns, so it is never under the finger that is driving it.
                        Readout -> midX to if (controller.isRotating) {
                            f.top - HANDLE_GAP - halfH
                        } else {
                            barCentre - barHeight / 2f - HANDLE_GAP - halfH
                        }
                        is ApexId -> {
                            // The apex handles follow the shape as it is being pulled about, which
                            // is why the live spec is preferred over the committed one.
                            val spec = live
                            if (spec == null || id.index >= spec.handleCount()) continue
                            spec.handleInto(id.index, out)
                            screenX(out[0]) to screenY(out[1])
                        }
                        is CropGrip -> {
                            if (cropped == null || cropRoom == null) continue
                            val across = id.handle == SelectionHandle.TOP || id.handle == SelectionHandle.BOTTOM
                            val down = id.handle == SelectionHandle.LEFT || id.handle == SelectionHandle.RIGHT
                            if (across && cropRoom.first < touchPx * 2f) continue
                            if (down && cropRoom.second < touchPx * 2f) continue
                            cropGripAt(cropped, id.handle)
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
    pickingColor?.let { target ->
        val inside = target == InkTarget.INSIDE
        ColorPickerSheet(
            title = stringResource(
                if (inside) R.string.editor_selection_inside_colour else R.string.editor_selection_colour,
            ),
            initial = if (inside) insideColor ?: selectionColor(sheet, selection) else selectionColor(sheet, selection),
            presets = ColorCodec.INK_PRESETS,
            recents = state.recentColors,
            palette = rememberSavedPalette(state.savedColors, viewModel),
            onColorChange = {},
            onCommit = {
                if (inside) viewModel.recolorSelectionInside(it) else viewModel.recolorSelection(it)
                viewModel.rememberColor(it)
            },
            onDismiss = { pickingColor = null },
        )
    }
}

/**
 * The selection bar's way to the inside colour of marker ink: the colour itself in a ring, the same
 * face the toolbar's inside button wears, so the two read as one control in two places.
 */
@Composable
private fun InsideColorButton(color: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.editor_selection_inside_colour)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = Color(color),
            border = BorderStroke(2.5.dp, LocalContentColor.current),
            content = {},
        )
    }
}

/** The inside colour of the first selected two-tone stroke, or null when none has one. */
private fun selectionInside(sheet: Sheet, selection: Selection): Int? {
    for ((blockId, indices) in selection.strokesByBlock) {
        val block = sheet.block(blockId) as? InkBlock ?: continue
        for (i in indices) block.strokes.getOrNull(i)?.core?.let { return it.color }
    }
    return null
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

/**
 * Whether every selected stroke is already filled.
 *
 * False for an empty stroke selection, so the button on a selection of text boxes alone — which is
 * not offered at all — could only ever read "fill", never "remove fill".
 */
private fun allFilled(sheet: Sheet, selection: Selection): Boolean {
    var any = false
    for ((blockId, indices) in selection.strokesByBlock) {
        val block = sheet.block(blockId) as? InkBlock ?: continue
        for (i in indices) {
            val stroke = block.strokes.getOrNull(i) ?: continue
            if (!stroke.filled) return false
            any = true
        }
    }
    return any
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

/** The frame's eight scale grips: corners scale evenly, edges stretch along one axis. */
private val FRAME_GRIPS = listOf(
    SelectionHandle.TOP_LEFT,
    SelectionHandle.TOP,
    SelectionHandle.TOP_RIGHT,
    SelectionHandle.RIGHT,
    SelectionHandle.BOTTOM_RIGHT,
    SelectionHandle.BOTTOM,
    SelectionHandle.BOTTOM_LEFT,
    SelectionHandle.LEFT,
)

/** One of the action bar's buttons, which all sit on the bar's own colour. */
@Composable
private fun BarButton(icon: ImageVector, @StringRes description: Int, onClick: () -> Unit) {
    HandleButton(
        icon = icon,
        description = stringResource(description),
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        elevation = 0.dp,
        onClick = onClick,
    )
}

/**
 * How far the selection has turned or been scaled, while a grip is held.
 *
 * Reads the controller's per-sample counter itself, so this chip is the one thing that recomposes
 * as the finger moves; everything round it is only re-placed.
 */
@Composable
private fun TransformReadout(controller: SelectionController, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_EXPRESSION")
    controller.version
    val grip = controller.grip ?: return
    val live = controller.live
    val text = if (grip == SelectionHandle.ROTATE) {
        stringResource(
            R.string.editor_selection_angle,
            Math.toDegrees(live.turnAngle().toDouble()).roundToInt(),
        )
    } else {
        // The factor along the grip's own axis: a corner scales both alike, so either will do.
        val factor = if (grip.scalesAcross) live.a else live.d
        stringResource(R.string.editor_zoom_percent_x, (factor * 100f).roundToInt())
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Layout id for the grip on apex [index] of a selected shape. */
private data class ApexId(val index: Int)

/** Layout id for the crop grip at [handle] of a picture being cropped. */
private data class CropGrip(val handle: SelectionHandle)

// Which of a crop's edges each grip takes hold of: a corner two, an edge one.
private val SelectionHandle.movesLeft: Boolean
    get() = this == SelectionHandle.TOP_LEFT || this == SelectionHandle.BOTTOM_LEFT || this == SelectionHandle.LEFT
private val SelectionHandle.movesTop: Boolean
    get() = this == SelectionHandle.TOP_LEFT || this == SelectionHandle.TOP_RIGHT || this == SelectionHandle.TOP
private val SelectionHandle.movesRight: Boolean
    get() = this == SelectionHandle.TOP_RIGHT || this == SelectionHandle.BOTTOM_RIGHT || this == SelectionHandle.RIGHT
private val SelectionHandle.movesBottom: Boolean
    get() = this == SelectionHandle.BOTTOM_LEFT || this == SelectionHandle.BOTTOM_RIGHT || this == SelectionHandle.BOTTOM

/** Layout id for the duplicate/colour/delete bar. */
private object ActionBar

/** Layout id for the angle or scale shown while a grip is held. */
private object Readout

/**
 * Smaller than the frame's grips.
 *
 * A triangle's three apexes can be a centimetre apart on the page, and full-sized grips on them
 * would overlap into one blob that pins whichever corner Compose happened to hit-test first.
 */
private val ApexHandleSize = 24.dp
