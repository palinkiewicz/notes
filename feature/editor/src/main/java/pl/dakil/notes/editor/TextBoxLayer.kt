package pl.dakil.notes.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.editor.markdown.MarkdownStaticText
import pl.dakil.notes.editor.markdown.MarkdownStyles
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every text box on the sheet that nobody is typing in, in the paper's own coordinates.
 *
 * Each is a [MarkdownStaticText] drawn from the same plan the editor renders, so "entering" a box
 * changes which component is mounted and nothing about how the text looks. The box that *is* being
 * typed in is drawn by [TextBoxChrome] instead, in the window's own pixels — see the note there on
 * why a live field cannot sit inside a scale transform.
 *
 * ### Why a box is clipped at the foot of its page
 *
 * A box grows downwards as it is typed into, and paper does not. Painting on past the bottom edge
 * would put words in the gap between two sheets and on the page below, neither of which will print
 * — the printer gets a slice of the strip, and everything past a page boundary is in the next
 * slice. So the box stops where the paper does and says so, and the user moves or resizes it from
 * there. What is on the screen is what comes out of the printer.
 *
 * The box's *frame* is not here: handles have to stay a finger wide at any zoom, so they live in
 * the window's coordinate space with the rest of the chrome. See [TextBoxChrome].
 */
@Composable
fun TextBoxLayer(
    sheet: Sheet,
    state: EditorUiState,
    viewModel: NoteViewModel,
    styles: MarkdownStyles,
    ptToPx: Float,
    paged: Boolean,
    /** Read at the moment of a tap, so the reach around a box is a constant size on screen. */
    zoom: () -> Float,
    modifier: Modifier = Modifier,
) {
    // The one being edited is left out: it is drawn out in the window with its handles, and a
    // second copy here would show the text as it stood before the last keystroke, underneath.
    val boxes = sheet.textBlocks().filter { it.id != state.editingTextBlock }
    val format = sheet.format

    Layout(
        modifier = modifier.textBoxTaps(sheet, state, viewModel, ptToPx, paged, zoom),
        content = {
            for (box in boxes) {
                TextBoxContent(
                    box = box,
                    state = state,
                    viewModel = viewModel,
                    styles = styles,
                    ptToPx = ptToPx,
                    pageBottomPt = (sheet.pageOf(box) + 1) * format.height,
                    modifier = Modifier.layoutId(box.id),
                )
            }
        },
    ) { measurables, constraints ->
        val placed = measurables.map { measurable ->
            val box = boxes.first { it.id == measurable.layoutId }
            val width = (box.rect.width * ptToPx).roundToInt().coerceAtLeast(1)
            measurable.measure(Constraints(minWidth = width, maxWidth = width)) to box
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            for ((placeable, box) in placed) {
                placeable.place(
                    x = (box.rect.left * ptToPx).roundToInt(),
                    y = SheetPainter.documentYToStripPx(box.rect.top, format, ptToPx, paged).roundToInt(),
                )
            }
        }
    }
}

/** One box's text, measured at its natural height and shown only as far as the paper reaches. */
@Composable
private fun TextBoxContent(
    box: TextBlock,
    state: EditorUiState,
    viewModel: NoteViewModel,
    styles: MarkdownStyles,
    ptToPx: Float,
    pageBottomPt: Float,
    modifier: Modifier = Modifier,
) {
    val active = state.activeTextBlock == box.id
    // How far the box may grow before it runs off the paper, in this layer's own pixels.
    val roomPx = ((pageBottomPt - box.rect.top) * ptToPx).coerceAtLeast(1f)
    var hidden by remember(box.id) { mutableStateOf(0f) }

    Layout(
        modifier = modifier
            .clipToBounds()
            .drawBehind { if (hidden > 0f) drawOverflowMarker() },
        content = {
            MarkdownStaticText(
                markdown = box.markdown,
                styles = styles,
                modifier = Modifier.fillMaxWidth(),
                placeholder = if (active) "Type here…" else "",
                onToggleTask = if (state.isReadOnly) {
                    null
                } else {
                    { mark, checked -> viewModel.toggleTask(box.id, mark, checked) }
                },
            )
        },
    ) { measurables, constraints ->
        val natural = measurables.first()
            .measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val height = min(natural.height.toFloat(), roomPx).roundToInt()
        hidden = natural.height - height.toFloat()

        // The measured height goes back into the document so the box's own rect says how tall it
        // is: that is what page operations, hit-testing and the next session's layout all read.
        viewModel.reportTextHeight(box.id, natural.height / ptToPx)

        layout(constraints.maxWidth, height) { natural.place(0, 0) }
    }
}

/**
 * The bar across the foot of a box whose text has run off the page.
 *
 * Drawn rather than written, and inside the sheet where it is scaled with the paper, because it
 * marks a place on the *page* — the line the printer will cut at. What is above it prints and what
 * is below it does not, and that is a fact about the document rather than about this screen.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOverflowMarker() {
    val height = (size.height * 0.02f).coerceIn(1.5f, 6f)
    drawRect(
        color = Color(0xFFB3261E),
        topLeft = Offset(0f, size.height - height),
        size = Size(size.width, height),
    )
}

/**
 * Taps on the paper, while the text tool is out.
 *
 * Nothing here is consumed, deliberately. The pan-and-zoom authority sits above this in the tree and
 * re-seeds itself off any frame it did not get, so a tap that is really the start of a drag has to
 * stay visible to it — and the field inside a box being edited has to keep getting the taps that
 * place its caret. What is left is the one thing this layer knows and nobody else does: which box,
 * if any, is under the finger.
 */
private fun Modifier.textBoxTaps(
    sheet: Sheet,
    state: EditorUiState,
    viewModel: NoteViewModel,
    ptToPx: Float,
    paged: Boolean,
    zoom: () -> Float,
): Modifier = if (!state.textToolActive || state.isReadOnly) {
    this
} else {
    pointerInput(sheet, state.editingTextBlock, state.activeTextBlock, ptToPx, paged) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var travel = 0f
            var pointers = 1
            while (true) {
                val event = awaitPointerEvent()
                pointers = maxOf(pointers, event.changes.count { it.pressed })
                travel += event.changes.sumOf { it.positionChange().getDistance().toDouble() }.toFloat()
                if (event.changes.none { it.pressed }) break
            }
            // A drag was a pan and a second finger was a pinch; neither was a tap on the paper.
            if (travel > slop || pointers > 1) return@awaitEachGesture

            val x = down.position.x / ptToPx
            val y = SheetPainter.stripPxToDocumentY(down.position.y, sheet.format, ptToPx, paged)
            val hit = boxNear(sheet, x, y, pad = TapPadDp.toPx() / zoom() / ptToPx)
            when {
                hit != null -> viewModel.beginTextEditing(hit.id)
                // One tap on bare paper puts the caret away, and the next makes a box. Making one
                // straight away would scatter boxes across the page every time somebody tapped
                // outside the one they were typing in to stop typing in it.
                state.editingTextBlock != null || state.activeTextBlock != null ->
                    viewModel.clearTextSelection()

                else -> viewModel.createTextBlock(x, y)
            }
        }
    }
}

/**
 * The box [x], [y] is in or nearest to, within [pad] of it, or null.
 *
 * A finger is about seven millimetres across and a caret is one pixel, so a tap a hair outside a box
 * is a tap on it: aiming at a line of text and being given a *new, empty* box a few points below it
 * is the worst possible reading of a near miss. The reach is measured on the screen rather than on
 * the paper — divided back out through the zoom by the caller — because it is the finger's size that
 * decides it, not the document's.
 *
 * Ties go to the box latest in reading order, the way an exact hit did before there was any reach at
 * all: boxes are drawn in that order, so the last one is the one on top.
 */
private fun boxNear(sheet: Sheet, x: Float, y: Float, pad: Float): TextBlock? {
    var best: TextBlock? = null
    var bestDistance = Float.MAX_VALUE
    for (box in sheet.textBlocks()) {
        val rect = box.worldBounds()
        val dx = maxOf(rect.left - x, x - rect.right, 0f)
        val dy = maxOf(rect.top - y, y - rect.bottom, 0f)
        val distance = maxOf(dx, dy)
        if (distance <= pad && distance <= bestDistance) {
            best = box
            bestDistance = distance
        }
    }
    return best
}

/** How far outside a box still counts as a tap on it. Roughly half a fingertip. */
private val TapPadDp = 10.dp
