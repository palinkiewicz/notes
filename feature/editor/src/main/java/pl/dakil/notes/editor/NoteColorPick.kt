package pl.dakil.notes.editor

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ColorSampling
import pl.dakil.notes.ui.color.ColorPickerSheet
import pl.dakil.notes.ui.color.SavedPalette
import pl.dakil.notes.ui.designsystem.appTopBarColors
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The eyedropper: one colour being taken from the note, by whoever asked for it.
 *
 * Held above both the toolbar and the sheet, because the things that ask (the toolbar, the colour
 * sheets, the selection's sheet) and the thing that answers (the overlay on the paper) have no
 * parent in common below the editor screen.
 */
@Stable
class NoteColorPicker {

    private class Request(
        val current: Int,
        val onPicked: (Int) -> Unit,
        val onCancelled: () -> Unit,
    )

    private var request by mutableStateOf<Request?>(null)

    val active: Boolean get() = request != null

    /** Starts a pick replacing [current], whose opacity the picked colour will keep. */
    fun start(current: Int, onCancelled: () -> Unit = {}, onPicked: (Int) -> Unit) {
        request = Request(current, onPicked, onCancelled)
    }

    internal fun deliver(sampled: Int) {
        val finished = request ?: return
        request = null
        finished.onPicked(ColorSampling.picked(sampled, finished.current))
    }

    fun cancel() {
        val finished = request ?: return
        request = null
        finished.onCancelled()
    }
}

/**
 * A [ColorPickerSheet] that can step aside while a colour is taken from the note.
 *
 * The sheet is a window over the paper and has to go for the paper to be touched, so it leaves
 * composition and comes back holding the sample. What must survive the trip is kept here: above all
 * the colour it first opened with, or Cancel afterwards would only undo the sample.
 *
 * [picker] is null when no sheet is on screen to pick from, and then no eyedropper is offered.
 */
@Composable
internal fun NoteColorSheet(
    picker: NoteColorPicker?,
    title: String,
    initial: Int,
    onColorChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onCommit: (Int) -> Unit,
    presets: IntArray,
    recents: List<Int>,
    palette: SavedPalette?,
    showAlpha: Boolean = true,
) {
    val original = remember { initial }
    var seed by remember { mutableIntStateOf(initial) }
    var away by remember { mutableStateOf(false) }
    if (away) return

    ColorPickerSheet(
        title = title,
        initial = seed,
        cancelColor = original,
        onColorChange = onColorChange,
        onDismiss = onDismiss,
        onCommit = onCommit,
        showAlpha = showAlpha,
        presets = presets,
        recents = recents,
        palette = palette,
        onPickFromNote = picker?.let {
            { current ->
                away = true
                it.start(
                    current = current,
                    onCancelled = {
                        seed = current
                        away = false
                    },
                    onPicked = { picked ->
                        seed = picked
                        onColorChange(picked)
                        away = false
                    },
                )
            }
        },
    )
}

/** Stands in for the app bar during a pick: says what to do, and is the way out of doing it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorPickBar(onCancel: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.editor_pick_colour_hint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onCancel, shapes = IconButtonDefaults.shapes()) {
                Icon(NotesIcons.Close, contentDescription = stringResource(R.string.editor_cancel))
            }
        },
        colors = appTopBarColors(),
    )
}

/**
 * Greys out [content] and takes its touches during a pick.
 *
 * A sibling on top rather than consuming on the way down: a covered control is never hit at all,
 * whereas a consumed press still depends on every control underneath choosing to respect it.
 */
@Composable
internal fun InertWhilePicking(picker: NoteColorPicker, content: @Composable () -> Unit) {
    val picking = picker.active
    Box(if (picking) Modifier.alpha(DISABLED_ALPHA) else Modifier) {
        content()
        if (picking) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}

/**
 * Takes the touches on the sheet during a pick and shows what is under them.
 *
 * ### Why it reads the screen
 *
 * What the user is pointing at may be ink, a blend of two highlighters, text, a photograph or a PDF,
 * each drawn by a different layer. The window's pixels are the one place all of them already exist,
 * and they are by definition the colour being looked at. The bottom sheet is a window of its own, so
 * it is not in the copy.
 *
 * The copy is taken at each touch-down rather than when the pick starts. The note may have been
 * moved since, and between touches nothing has to be kept in memory or kept up to date. The
 * magnifier only appears once the copy is in, so it can never be in the copy itself.
 *
 * ### Two fingers still move the page
 *
 * One pointer samples. A second one drops the sample and stops consuming, and
 * `sheetTransformGestures` picks the pinch up from there, so the colour wanted can be brought into
 * view without leaving the pick.
 */
@Composable
internal fun ColorPickOverlay(picker: NoteColorPicker, modifier: Modifier = Modifier) {
    val window = LocalActivity.current?.window
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val loupe = remember { Loupe() }
    val density = LocalDensity.current
    val loupeWidth = with(density) { LoupeSize.toPx() }
    val loupeHeight = with(density) { (LoupeSize + PillGap + PillHeight).toPx() }
    val fingerGap = with(density) { FingerGap.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { loupe.coordinates = it }
            .pointerInput(picker, window) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    loupe.moveTo(down.position)
                    val capture = scope.async {
                        val area = loupe.coordinates?.windowArea(window)
                        val shot = if (window != null && area != null) window.copyPixels(area) else null
                        shot?.also(loupe::show)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            capture.cancel()
                            loupe.hide()
                            return@awaitEachGesture
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        change.consume()
                        if (!change.pressed) break
                        loupe.moveTo(change.position)
                    }

                    // A quick tap can lift before the copy has come back, so the answer waits for it.
                    scope.launch {
                        val sampled = capture.await()?.let { loupe.color }
                        loupe.hide()
                        if (sampled != null) {
                            haptics.confirm()
                            picker.deliver(sampled)
                        } else {
                            haptics.reject()
                            picker.cancel()
                        }
                    }
                }
            },
    ) {
        if (loupe.visible) {
            LoupeView(
                loupe = loupe,
                modifier = Modifier.offset {
                    val view = loupe.coordinates?.size
                    val (x, y) = ColorSampling.loupeOrigin(
                        touchX = loupe.touch.x,
                        touchY = loupe.touch.y,
                        width = loupeWidth,
                        height = loupeHeight,
                        gap = fingerGap,
                        viewWidth = view?.width?.toFloat() ?: 0f,
                        viewHeight = view?.height?.toFloat() ?: 0f,
                    )
                    IntOffset(x.roundToInt(), y.roundToInt())
                },
            )
        }
    }
}

/**
 * The sampled pixels and where the touch is.
 *
 * Changes on every move, so like the wet stroke it stays out of `EditorUiState`, and [frame] is read
 * only inside the magnifier's draw lambda.
 */
@Stable
private class Loupe {
    var coordinates: LayoutCoordinates? = null
    val cells = IntArray(LOUPE_CELLS * LOUPE_CELLS)
    private var shot: Bitmap? = null

    var touch by mutableStateOf(Offset.Zero)
        private set
    var color by mutableIntStateOf(0)
        private set
    var frame by mutableIntStateOf(0)
        private set
    var visible by mutableStateOf(false)
        private set

    fun show(bitmap: Bitmap) {
        shot = bitmap
        refresh()
        visible = true
    }

    fun moveTo(position: Offset) {
        touch = position
        refresh()
    }

    fun hide() {
        shot = null
        visible = false
    }

    private fun refresh() {
        val bitmap = shot ?: return
        ColorSampling.readAround(
            out = cells,
            side = LOUPE_CELLS,
            centerX = ColorSampling.pixelAt(touch.x, bitmap.width),
            centerY = ColorSampling.pixelAt(touch.y, bitmap.height),
            width = bitmap.width,
            height = bitmap.height,
            read = bitmap::getPixels,
        )
        color = cells[LOUPE_CELLS * LOUPE_CELLS / 2]
        frame++
    }
}

/** The magnifier, with the colour's hex underneath. */
@Composable
private fun LoupeView(loupe: Loupe, modifier: Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier = modifier.width(LoupeSize),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PillGap),
    ) {
        Canvas(Modifier.size(LoupeSize).shadow(6.dp, CircleShape)) {
            // Read here and only here, so a move repaints this and recomposes nothing.
            loupe.frame
            drawRect(empty)
            drawCells(loupe.cells)
            drawRing(Color(ColorCodec.withAlpha(loupe.color, 255)))
        }
        Surface(
            modifier = Modifier.height(PillHeight),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = ColorCodec.toHex(loupe.color, includeAlpha = false),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

private fun DrawScope.drawCells(cells: IntArray) {
    // Whole pixels: two anti-aliased edges meeting on a fraction would let the backdrop show through
    // as a grid that is not in the note.
    fun edge(index: Int) = (index * size.width / LOUPE_CELLS).roundToInt().toFloat()

    for (row in 0 until LOUPE_CELLS) {
        for (column in 0 until LOUPE_CELLS) {
            val argb = cells[row * LOUPE_CELLS + column]
            if (argb == 0) continue
            val left = edge(column)
            val top = edge(row)
            drawRect(
                color = Color(argb),
                topLeft = Offset(left, top),
                size = Size(edge(column + 1) - left, edge(row + 1) - top),
            )
        }
    }

    val middle = LOUPE_CELLS / 2
    val topLeft = Offset(edge(middle), edge(middle))
    val cell = Size(edge(middle + 1) - topLeft.x, edge(middle + 1) - topLeft.y)
    // Black and white together, so the marked pixel can be seen whatever colour it is.
    drawRect(Color.Black, topLeft, cell, style = Stroke(3.dp.toPx()))
    drawRect(Color.White, topLeft, cell, style = Stroke(1.5f.dp.toPx()))
}

private fun DrawScope.drawRing(color: Color) {
    val width = 7.dp.toPx()
    val hairline = 1.dp.toPx()
    val outer = size.minDimension / 2f
    drawCircle(color, outer - width / 2f, style = Stroke(width))
    drawCircle(Color.Black.copy(alpha = 0.35f), outer - hairline / 2f, style = Stroke(hairline))
    drawCircle(Color.White.copy(alpha = 0.7f), outer - width, style = Stroke(hairline))
}

/** This layout's rectangle in the window, cut to the window, or null if nothing of it is left. */
private fun LayoutCoordinates.windowArea(window: Window?): Rect? {
    val decor = window?.decorView ?: return null
    val origin = positionInWindow()
    val left = origin.x.roundToInt()
    val top = origin.y.roundToInt()
    val right = min(left + size.width, decor.width)
    val bottom = min(top + size.height, decor.height)
    return if (left < right && top < bottom) Rect(left, top, right, bottom) else null
}

/** The window's pixels in [area], or null if the system would not give them up. */
private suspend fun Window.copyPixels(area: Rect): Bitmap? = suspendCancellableCoroutine { result ->
    try {
        val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            this,
            area,
            bitmap,
            { status -> result.resume(bitmap.takeIf { status == PixelCopy.SUCCESS }) },
            Handler(Looper.getMainLooper()),
        )
    } catch (_: IllegalArgumentException) {
        // A window whose surface has just gone away.
        result.resume(null)
    }
}

/** Odd, so that one cell is the middle. */
private const val LOUPE_CELLS = 11
private const val DISABLED_ALPHA = 0.38f
private val LoupeSize = 128.dp
private val PillHeight = 28.dp
private val PillGap = 6.dp

/** Clears a fingertip, so the magnifier is not under the hand that is moving it. */
private val FingerGap = 44.dp
