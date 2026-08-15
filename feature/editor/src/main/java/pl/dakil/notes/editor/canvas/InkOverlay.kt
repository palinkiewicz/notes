package pl.dakil.notes.editor.canvas

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import pl.dakil.notes.ink.InputIntent
import pl.dakil.notes.ink.InputRouter
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import kotlin.math.abs
import kotlin.math.hypot

/** What the overlay reports back when the user finishes an action. */
interface InkCallbacks {
    fun onStrokeCommitted(stroke: Stroke)
    fun onEraseAlong(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, wholeStroke: Boolean)
    fun onLassoCommitted(xs: FloatArray, ys: FloatArray, count: Int)
}

/**
 * The ink layer that sits over the note's text.
 *
 * ### Why it does not recompose while you draw
 *
 * Compose's snapshot system is excellent for screen state and actively harmful at a stylus's
 * 240 Hz sample rate. The wet stroke lives in a plain [StrokeBuilder] — not snapshot state — and
 * the only reactive value the input path touches is an `Int` read *inside* the `Canvas` draw
 * lambda, which invalidates the draw phase alone. A sample costs one redraw, zero recomposition
 * and no allocation.
 *
 * ### Why it lets taps through
 *
 * The overlay is on top of the text, so it decides who gets each event. A stylus always draws,
 * whatever tool is selected — that is the whole point of having a pen in your hand. A finger draws
 * only when a drawing tool is active; otherwise the event is declined and falls through to the text
 * underneath, which is what makes one surface work for both writing and typing.
 *
 * ### Why it has to disallow intercept
 *
 * `pointerInteropFilter` consumes the pointer-down of a gesture it claims, but **not** the moves
 * that follow: it dispatches them during the tunnelling pass unconsumed, precisely so a Compose
 * ancestor still gets the chance to intercept. If any ancestor then takes one — which a scrolling
 * or transform gesture will, on the first sample past touch slop — the filter sends this handler an
 * `ACTION_CANCEL` and stops dispatching for the rest of the stream. The visible result is a stroke
 * that puts down a single dot and dies.
 *
 * [RequestDisallowInterceptTouchEvent] is the documented way out, and it is the exact analogue of
 * the `ViewGroup` call of the same name: once the router says this pointer is drawing, moves are
 * dispatched *and consumed* in the tunnelling pass, so no ancestor can take the stroke away. The
 * claim is dropped the moment the gesture ends or is handed over, so panning is never starved.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InkOverlay(
    sheet: Sheet,
    tool: ToolSpec,
    inputConfig: InputConfig,
    /** Pixels per document point, including zoom. */
    ptToPx: Float,
    paged: Boolean,
    documentVersion: Int,
    callbacks: InkCallbacks,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    /** The scale the sheet is placed at, for anything that must stay a fixed size on screen. */
    zoom: () -> Float = { 1f },
) {
    val renderer = remember { StrokeRenderer() }
    val builder = remember { StrokeBuilder() }
    val router = remember { InputRouter(inputConfig) }
    val lasso = remember { LassoBuffer() }
    val inkVersion = remember { mutableIntStateOf(0) }
    val disallowIntercept = remember { RequestDisallowInterceptTouchEvent() }

    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentTool by rememberUpdatedState(tool)
    val format = sheet.format
    val pageCount = sheet.pageCount()

    router.config = inputConfig

    val paintOrder = remember(sheet, documentVersion) { sheet.blocksInPaintOrder() }

    Canvas(
        modifier = modifier.pointerInteropFilter(
            requestDisallowInterceptTouchEvent = disallowIntercept,
        ) { event ->
            val toDoc = { x: Float, y: Float ->
                documentPointAt(x, y, ptToPx, zoom(), format, paged)
            }
            handleEvent(
                event = event,
                router = router,
                builder = builder,
                lasso = lasso,
                tool = currentTool,
                config = inputConfig,
                callbacks = currentCallbacks,
                toDocument = toDoc,
                invalidate = { inkVersion.intValue++ },
                claim = disallowIntercept,
            )
        }
    ) {
        @Suppress("UNUSED_EXPRESSION")
        inkVersion.intValue

        val toStripPx = { y: Float -> SheetPainter.documentYToStripPx(y, format, ptToPx, paged) }
        val wet = !builder.isEmpty && currentTool.tool.isDrawing

        with(renderer) {
            if (!paged) {
                // Continuous view has no gaps, so the pages are one uninterrupted surface and a
                // single pass is both correct and cheapest.
                for (block in paintOrder) {
                    if (block !is InkBlock || !block.visible) continue
                    drawStrokes(block.strokes, ptToPx, toStripPx)
                }
                if (wet) drawWetStroke(builder, ptToPx, toStripPx)
            } else {
                // One clipped pass per page. A stroke drawn across a page break is a single
                // continuous stroke in the document — that is the right model, since the break is
                // presentation — but it must not be *painted* across the join, or the ink appears
                // to run down the desk between two sheets of paper.
                val pageHeightPx = format.height * ptToPx
                val gapPx = SheetPainter.PAGE_GAP_PT * ptToPx
                for (page in 0 until pageCount) {
                    val top = page * (pageHeightPx + gapPx)
                    val docTop = page * format.height
                    val docBottom = docTop + format.height
                    clipRect(top = top, bottom = top + pageHeightPx) {
                        for (block in paintOrder) {
                            if (block !is InkBlock || !block.visible) continue
                            drawStrokes(block.strokes, ptToPx, toStripPx, 1f, docTop, docBottom)
                        }
                        if (wet) drawWetStroke(builder, ptToPx, toStripPx)
                    }
                }
            }
        }

        if (lasso.count > 1) {
            val color = if (darkTheme) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
            // The marquee is UI, not ink: it should be the same weight however far the page is
            // zoomed, so its width is divided back out of the placement scale.
            val outlineWidth = 2f / zoom()
            for (i in 1 until lasso.count) {
                drawLine(
                    color = color,
                    start = Offset(lasso.xs[i - 1] * ptToPx, toStripPx(lasso.ys[i - 1])),
                    end = Offset(lasso.xs[i] * ptToPx, toStripPx(lasso.ys[i])),
                    strokeWidth = outlineWidth,
                )
            }
            drawLine(
                color = color.copy(alpha = 0.4f),
                start = Offset(lasso.xs[lasso.count - 1] * ptToPx, toStripPx(lasso.ys[lasso.count - 1])),
                end = Offset(lasso.xs[0] * ptToPx, toStripPx(lasso.ys[0])),
                strokeWidth = outlineWidth,
            )
        }
    }
}

/** A point in document coordinates. */
data class PointF(val x: Float, val y: Float)

/**
 * Maps a pointer coordinate as `pointerInteropFilter` reports it into document points.
 *
 * ### Why [zoom] appears here
 *
 * `pointerInteropFilter` does not build a fresh `MotionEvent`; it takes the original window event
 * and calls `offsetLocation(-origin)`, where `origin` is this node's position in root coordinates.
 * That is a **translation only** — it has no notion of a scaled ancestor. Because the overlay sits
 * inside the sheet's placement layer, a local point `L` reaches this function as `L × zoom`, so the
 * scale has to be divided back out before anything else is done with it.
 *
 * Getting this wrong is silent: strokes simply land nearer the top-left corner of the page than the
 * pen did, in exact proportion to the zoom, and nothing logs a complaint.
 */
internal fun documentPointAt(
    x: Float,
    y: Float,
    ptToPx: Float,
    zoom: Float,
    format: PageFormat,
    paged: Boolean,
): PointF {
    val scale = if (zoom > 0f) zoom else 1f
    return PointF(
        x / (ptToPx * scale),
        SheetPainter.stripPxToDocumentY(y / scale, format, ptToPx, paged),
    )
}

/**
 * Routes one `MotionEvent`, returning true if the overlay consumed it.
 *
 * Declining is as important as consuming: a finger tap while the text tool is active has to reach
 * the editor underneath.
 */
@Suppress("LongParameterList")
private fun handleEvent(
    event: MotionEvent,
    router: InputRouter,
    builder: StrokeBuilder,
    lasso: LassoBuffer,
    tool: ToolSpec,
    config: InputConfig,
    callbacks: InkCallbacks,
    toDocument: (Float, Float) -> PointF,
    invalidate: () -> Unit,
    claim: (Boolean) -> Unit,
): Boolean {
    fun sampleAt(index: Int): PointerSample {
        val raw = MotionEventBridge.sampleOf(event, index)
        val doc = toDocument(raw.x, raw.y)
        return raw.copy(x = doc.x, y = doc.y)
    }

    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            val index = event.actionIndex
            val screen = MotionEventBridge.sampleOf(event, index)
            val stylus = screen.toolType == pl.dakil.notes.model.ToolType.STYLUS ||
                screen.toolType == pl.dakil.notes.model.ToolType.ERASER

            // A stylus always draws. A finger only draws when a drawing tool is chosen; otherwise
            // the text underneath should get the tap.
            if (!stylus && !tool.tool.isDrawing && tool.tool != ToolId.LASSO &&
                tool.tool != ToolId.ERASER_POINT && tool.tool != ToolId.ERASER_STROKE
            ) {
                claim(false)
                return false
            }

            val decision = router.begin(screen)
            if (decision.revoked.isNotEmpty()) {
                builder.reset()
                lasso.reset()
            }

            when (decision.intent) {
                is InputIntent.Draw -> {
                    beginAction(builder, lasso, tool, config, sampleAt(index))
                    // From here the stroke is ours: ancestors may not intercept the moves.
                    claim(true)
                }
                is InputIntent.Navigate -> {
                    // A second finger means pan/zoom. Release the claim so the gesture detector
                    // beneath can pick the pinch up, and report the revoked stroke gone.
                    claim(false)
                    if (decision.revoked.isNotEmpty()) invalidate()
                    return false
                }
                // A rejected palm has to be ignored by *everyone*. Claiming it looks odd but is the
                // only way to swallow it: leaving it unclaimed hands it straight to the gesture
                // detector underneath, and the page pans away under the heel of the hand.
                is InputIntent.Ignore -> claim(true)
            }
            invalidate()
            return true
        }

        MotionEvent.ACTION_MOVE -> {
            var redraw = false
            var consumed = false
            for (index in 0 until event.pointerCount) {
                val id = event.getPointerId(index)
                if (router.intentFor(id) !is InputIntent.Draw) continue
                consumed = true
                MotionEventBridge.forEachSample(event, index) { raw ->
                    val doc = toDocument(raw.x, raw.y)
                    val sample = raw.copy(x = doc.x, y = doc.y)
                    router.update(raw)
                    when (tool.tool) {
                        ToolId.ERASER_STROKE, ToolId.ERASER_POINT ->
                            if (eraseStep(builder, sample, tool, callbacks)) redraw = true
                        ToolId.LASSO ->
                            if (lasso.add(sample.x, sample.y)) redraw = true
                        else ->
                            if (builder.add(sample)) redraw = true
                    }
                }
            }
            if (redraw) invalidate()
            return consumed
        }

        MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
            val index = event.actionIndex
            val screen = MotionEventBridge.sampleOf(event, index)
            val intent = router.end(screen)
            // Held while any pointer is still drawing — a stylus stroke must survive the palm
            // resting on the glass lifting off mid-word.
            if (event.actionMasked == MotionEvent.ACTION_UP || !router.isDrawing) claim(false)
            if (intent is InputIntent.Draw) {
                finishAction(builder, lasso, tool, sampleAt(index), callbacks)
                invalidate()
                return true
            }
            return false
        }

        MotionEvent.ACTION_CANCEL -> {
            router.cancel()
            builder.reset()
            lasso.reset()
            claim(false)
            invalidate()
            return false
        }
    }
    return false
}

private fun beginAction(
    builder: StrokeBuilder,
    lasso: LassoBuffer,
    tool: ToolSpec,
    config: InputConfig,
    sample: PointerSample,
) {
    when (tool.tool) {
        ToolId.LASSO -> {
            lasso.reset()
            lasso.add(sample.x, sample.y)
        }
        ToolId.ERASER_STROKE, ToolId.ERASER_POINT -> {
            builder.reset()
            lasso.reset()
            // The builder is reused purely to remember the previous position, so a fast swipe
            // erases along the whole swept segment rather than at sampled points.
            builder.start(tool, config, sample)
        }
        else -> {
            lasso.reset()
            builder.start(tool, config, sample)
        }
    }
}

private fun eraseStep(
    builder: StrokeBuilder,
    sample: PointerSample,
    tool: ToolSpec,
    callbacks: InkCallbacks,
): Boolean {
    val last = builder.pointCount - 1
    if (last < 0) return false
    val x0 = builder.x(last)
    val y0 = builder.y(last)
    if (hypot(sample.x - x0, sample.y - y0) < 0.5f) return false
    callbacks.onEraseAlong(
        x0, y0, sample.x, sample.y,
        tool.eraserRadius,
        wholeStroke = tool.tool == ToolId.ERASER_STROKE,
    )
    builder.add(sample)
    return true
}

private fun finishAction(
    builder: StrokeBuilder,
    lasso: LassoBuffer,
    tool: ToolSpec,
    sample: PointerSample,
    callbacks: InkCallbacks,
) {
    when (tool.tool) {
        ToolId.LASSO -> {
            if (lasso.count >= 3) callbacks.onLassoCommitted(lasso.xs, lasso.ys, lasso.count)
            lasso.reset()
        }
        ToolId.ERASER_STROKE, ToolId.ERASER_POINT -> builder.reset()
        else -> {
            val stroke = builder.finish(sample)
            builder.reset()
            // A tap with a pen in hand is a full stop, not a request for the keyboard: the gesture
            // only reached here because the router already decided this pointer was drawing.
            if (stroke != null) callbacks.onStrokeCommitted(stroke)
        }
    }
}

/** Growable buffer for the lasso outline, in document coordinates. */
class LassoBuffer {
    var xs = FloatArray(256)
        private set
    var ys = FloatArray(256)
        private set
    var count = 0
        private set

    fun reset() {
        count = 0
    }

    fun add(x: Float, y: Float): Boolean {
        if (count > 0 && abs(x - xs[count - 1]) < 1f && abs(y - ys[count - 1]) < 1f) return false
        if (count == xs.size) {
            xs = xs.copyOf(count * 2)
            ys = ys.copyOf(count * 2)
        }
        xs[count] = x
        ys[count] = y
        count++
        return true
    }
}
