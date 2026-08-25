package pl.dakil.notes.editor.canvas

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import pl.dakil.notes.ink.InputIntent
import pl.dakil.notes.ink.InputRouter
import pl.dakil.notes.ink.RulerEdge
import pl.dakil.notes.ink.RulerGuide
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.model.Block
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import pl.dakil.notes.ui.sheet.StrokeRenderer
import pl.dakil.notes.ui.sheet.SheetPainter

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
 * whatever tool is selected — that is the whole point of having a pen in your hand — unless the
 * text tool is out, which is the one thing that outranks it. A finger draws only when a drawing
 * tool is active; otherwise the event is declined and falls through to the text underneath, which
 * is what makes one surface work for both writing and typing.
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
 * ### Why the ruler is consulted once
 *
 * [rulerEdgeFor] is asked at pen-down and never again: whether the stroke is being drawn against
 * the straightedge is a fact about how it started, exactly as it is with a real ruler. Re-testing
 * per sample would let the ink step on and off the edge wherever the hand drifted past the band.
 *
 * ### Why auto-shape needs a timer
 *
 * The snap is triggered by holding the pen *still*, and Android sends no move events while a
 * pointer is stationary — the very silence that has to be detected arrives as nothing at all. A
 * short polling job therefore runs between pen-down and pen-up, and only then.
 *
 * [RequestDisallowInterceptTouchEvent] is the documented way out, and it is the exact analogue of
 * the `ViewGroup` call of the same name: once the router says this pointer is drawing, moves are
 * dispatched *and consumed* in the tunnelling pass, so no ancestor can take the stroke away. The
 * claim is dropped the moment the gesture ends or is handed over, so panning is never starved.
 *
 * ### Why it never declines the first pointer down
 *
 * `pointerInteropFilter` reads the return value of exactly one event — `ACTION_DOWN` — and a
 * `false` there latches the whole stream off: no `ACTION_POINTER_DOWN`, no moves, no up, until
 * every finger has left the glass. That is the mechanism behind "resting my hand makes the pen
 * useless". The heel of the hand lands a moment before the nib, is read as an ordinary finger
 * because no stylus is on record yet, and is declined so the page can be panned with it — and the
 * pen's own pointer-down, arriving a few milliseconds later, is then delivered to nobody. The pen
 * moves and the page scrolls under it, because panning is the only claimant left.
 *
 * So the first down is always consumed, whoever it belongs to, and the decision about what it *is*
 * stays with the router. Consuming it costs nothing that was being used: pan and zoom re-seed
 * themselves from the fingers' real positions rather than from the down (`awaitFirstDown` there
 * does not require an unconsumed one), and the taps that have to reach the text underneath only
 * happen while the text tool is out, which this handler has already stepped aside for.
 *
 * That keeps the stream alive long enough for the pen to arrive.
 * [InputRouter.observeStylusProximity] is what usually makes it moot: a pen reports itself while it
 * is still in the air, so by the time the hand lands the palm window is already open and the hand
 * is ignored outright.
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
    /**
     * Whether the text tool is out, in which case the overlay is a spectator.
     *
     * It declines every pointer, the stylus included. That is a deliberate exception to the rule
     * below it: a pen that draws whatever the toolbar says would make the text tool unusable on
     * precisely the devices this app is written for, since the only way to put a caret in a box
     * would be to put the pen down and use a finger. The tool the user picked is the tool they get.
     */
    textToolActive: Boolean = false,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    /** The scale the sheet is placed at, for anything that must stay a fixed size on screen. */
    zoom: () -> Float = { 1f },
    /**
     * The band of the strip currently under the window, in unzoomed strip pixels.
     *
     * Lambdas, and read *inside* the draw lambda, for the reason the whole file is built around: a
     * pan then invalidates the draw phase and nothing recomposes. Ink outside the band is not drawn
     * at all, which is what stops a fifty-page note costing fifty pages of path building on every
     * frame.
     */
    visibleTopPx: () -> Float = { -Float.MAX_VALUE },
    visibleBottomPx: () -> Float = { Float.MAX_VALUE },
    /**
     * Given a pen-down in document points, the ruler edge that stroke should be drawn against, or
     * null for free-hand. Null itself whenever the ruler is off.
     */
    rulerEdgeFor: ((Float, Float) -> RulerEdge?)? = null,
) {
    val renderer = remember { StrokeRenderer() }
    val builder = remember { StrokeBuilder() }
    val router = remember { InputRouter(inputConfig) }
    val lasso = remember { LassoBuffer() }
    val shape = remember { ShapeController() }
    val guide = remember { RulerGuide() }
    val inkVersion = remember { mutableIntStateOf(0) }
    val disallowIntercept = remember { RequestDisallowInterceptTouchEvent() }

    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentTool by rememberUpdatedState(tool)
    val currentRulerEdgeFor by rememberUpdatedState(rulerEdgeFor)
    val format = sheet.format
    val pageCount = sheet.pageCount()

    router.config = inputConfig

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Re-read through rememberUpdatedState so the running ticker always sees the current tool and
    // settings; it was started at pen-down and outlives any recomposition since.
    val onTick by rememberUpdatedState {
        val found = shape.tick(
            nowMs = SystemClock.uptimeMillis(),
            holdMs = inputConfig.autoShapeHoldMs,
            builder = builder,
            toolSpec = currentTool,
        )
        if (found) {
            // The stroke has just changed under the user's hand while they are looking at the pen,
            // not the screen. A tick tells them it happened.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            inkVersion.intValue++
        }
    }

    val paintOrder = remember(sheet, documentVersion) { sheet.blocksInPaintOrder() }

    // Paged view draws one clipped pass per page, and used to re-scan every block and every stroke
    // in the note inside each of them — O(pages x strokes) bound tests before a line was drawn.
    // Grouping them once per edit makes a page cost what that page holds.
    val strokesByPage = remember(sheet, documentVersion, paged, pageCount) {
        if (paged) strokesByPage(paintOrder, pageCount, format.height) else emptyList()
    }

    Canvas(
        modifier = modifier
            .stylusProximity(router)
            .pointerInteropFilter(
                requestDisallowInterceptTouchEvent = disallowIntercept,
            ) { event ->
                // Declining outright while the text tool is out. The router is still cleared on
                // the way past: a pointer it has already accepted — the tool can be switched
                // mid-stroke — would otherwise stay live for ever, because a declined gesture
                // never delivers its UP.
                if (textToolActive) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        router.cancel()
                        shape.reset()
                        guide.release()
                        builder.reset()
                        disallowIntercept(false)
                    }
                    return@pointerInteropFilter false
                }

                val toDoc = { x: Float, y: Float ->
                    documentPointAt(x, y, ptToPx, zoom(), format, paged)
                }
                handleEvent(
                    event = event,
                    router = router,
                    builder = builder,
                    lasso = lasso,
                    shape = shape,
                    guide = guide,
                    rulerEdgeFor = currentRulerEdgeFor,
                    tool = currentTool,
                    config = inputConfig,
                    callbacks = currentCallbacks,
                    toDocument = toDoc,
                    // A resting pen wanders by a roughly fixed distance on the glass whatever
                    // the page is magnified to, so the tolerance is a screen distance divided
                    // back out of the zoom — the same reasoning as the marquee's width below.
                    dwellTolerance = { DWELL_TOLERANCE_PT / zoom().coerceAtLeast(MIN_ZOOM) },
                    startTicking = { shape.startTicking(scope) { onTick() } },
                    invalidate = { inkVersion.intValue++ },
                    claim = disallowIntercept,
                )
            }
    ) {
        @Suppress("UNUSED_EXPRESSION")
        inkVersion.intValue

        val toStripPx = { y: Float -> SheetPainter.documentYToStripPx(y, format, ptToPx, paged) }
        // While a shape is live it *is* the stroke under the pen; the builder still holds the
        // free-hand path it replaced, which must not be drawn underneath it.
        val live = shape.spec
        val wet = live == null && !builder.isEmpty && currentTool.tool.isDrawing

        // A page of slack either side of the window, so a fling never outruns the ink and shows it
        // arriving. Generous on purpose: the saving is against the length of the *note*, and a note
        // is a great deal longer than three screens.
        val padPx = format.height * ptToPx
        val bandTopPx = visibleTopPx() - padPx
        val bandBottomPx = visibleBottomPx() + padPx
        val bandTop = SheetPainter.stripPxToDocumentY(bandTopPx, format, ptToPx, paged)
        val bandBottom = SheetPainter.stripPxToDocumentY(bandBottomPx, format, ptToPx, paged)

        with(renderer) {
            if (!paged) {
                // Continuous view has no gaps, so the pages are one uninterrupted surface and a
                // single pass is both correct and cheapest.
                for (block in paintOrder) {
                    if (block !is InkBlock || !block.visible) continue
                    // Continuous view lays the pages out with no gaps, so document y and strip y
                    // are the same measure and the pass needs no offset.
                    drawStrokes(block.strokes, ptToPx, 0f, 1f, bandTop, bandBottom)
                }
                if (wet) drawWetStroke(builder, ptToPx, toStripPx)
                if (live != null) drawShapePreview(
                    shape.outline, ptToPx, toStripPx,
                    shape.previewWidth, shape.previewColor, shape.previewBlend,
                )
            } else {
                // One clipped pass per page. A stroke drawn across a page break is a single
                // continuous stroke in the document — that is the right model, since the break is
                // presentation — but it must not be *painted* across the join, or the ink appears
                // to run down the desk between two sheets of paper.
                val pageHeightPx = format.height * ptToPx
                val gapPx = SheetPainter.PAGE_GAP_PT * ptToPx
                for (page in 0 until pageCount) {
                    val top = page * (pageHeightPx + gapPx)
                    // Whole pages outside the window are skipped before the clip is even set up:
                    // a clipRect still costs a save and a restore, and there is no point paying it
                    // pageCount times to draw nothing.
                    if (top + pageHeightPx < bandTopPx || top > bandBottomPx) continue
                    val docTop = page * format.height
                    val docBottom = docTop + format.height
                    clipRect(top = top, bottom = top + pageHeightPx) {
                        // Every page before this one has pushed the paper a gap further down the
                        // strip. That is the whole difference between document y and strip y here,
                        // and it is constant across the page — so it is one translation, not a
                        // per-point mapping.
                        drawStrokes(
                            strokesByPage[page], ptToPx, page * gapPx, 1f, docTop, docBottom,
                        )
                        // Culled by the same band the committed strokes are, and for a sharper
                        // reason: the wet stroke is tessellated on every frame, so drawing it once
                        // per page would multiply that by the length of the note.
                        if (wet && builder.maxY >= docTop && builder.minY < docBottom) {
                            drawWetStroke(builder, ptToPx, toStripPx)
                        }
                        if (live != null) drawShapePreview(
                            shape.outline, ptToPx, toStripPx,
                            shape.previewWidth, shape.previewColor, shape.previewBlend,
                        )
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

/**
 * Groups committed strokes by the page they are painted on, in paint order.
 *
 * This is the same question the per-stroke cull in `drawStrokes` answers, asked once per edit
 * instead of once per page per frame. The predicate is deliberately identical: a stroke is on page
 * *k* when its **centreline** overlaps that page's band. Judging by the inflated [Stroke.bounds]
 * would put a stroke that ends exactly on a boundary onto the page below as well, and paint the
 * lower half of its end cap there as a stray dot.
 *
 * A stroke drawn across a page break appears in **both** buckets, which is what lets each clipped
 * pass paint its own side of the join.
 */
internal fun strokesByPage(
    blocks: List<Block>,
    pageCount: Int,
    pageHeight: Float,
): List<List<Stroke>> {
    val pages = List(pageCount) { ArrayList<Stroke>() }
    if (pageCount == 0 || pageHeight <= 0f) return pages

    for (block in blocks) {
        if (block !is InkBlock || !block.visible) continue
        for (stroke in block.strokes) {
            val core = stroke.coreBounds
            var page = floor(core.top / pageHeight).toInt().coerceAtLeast(0)
            // `page * pageHeight < core.bottom` is the surviving half of the cull's test; the other
            // half is what `page` was seeded with.
            while (page < pageCount && page * pageHeight < core.bottom) {
                pages[page].add(stroke)
                page++
            }
        }
    }
    return pages
}

/**
 * Tells [router] whenever the stylus reports itself without touching the glass.
 *
 * Written as a separate pointer-input modifier because `pointerInteropFilter` never sees this:
 * hovering is not a touch, so it arrives through `onGenericMotionEvent` and reaches Compose as an
 * ordinary pointer change that simply is not pressed. Nothing is consumed here — the only job is
 * to put the pen on the record a moment earlier than its nib does, which is what lets the palm
 * that lands next be recognised as a palm.
 */
private fun Modifier.stylusProximity(router: InputRouter): Modifier = pointerInput(router) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
                if (change.pressed) continue
                if (change.type != PointerType.Stylus && change.type != PointerType.Eraser) continue
                router.observeStylusProximity(change.uptimeMillis)
            }
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
    shape: ShapeController,
    guide: RulerGuide,
    rulerEdgeFor: ((Float, Float) -> RulerEdge?)?,
    tool: ToolSpec,
    config: InputConfig,
    callbacks: InkCallbacks,
    toDocument: (Float, Float) -> PointF,
    dwellTolerance: () -> Float,
    startTicking: () -> Unit,
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
            // ACTION_DOWN means no other pointer is on the glass, so the router starts clean.
            //
            // This is not belt-and-braces. Declining a gesture at ACTION_DOWN puts the interop
            // filter into NotDispatching for the rest of the stream, so the matching ACTION_UP
            // never arrives and that pointer would stay live forever. The next touch then sees a
            // finger that is not there, reads as multi-touch, and is sent to pan — which is to say
            // one pan with finger-drawing off would disable finger-drawing until the note was
            // reopened.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                router.cancel()
                // Same reasoning: a gesture whose ACTION_UP never arrived would otherwise leave a
                // shape live and its ticker running into the next stroke.
                shape.reset()
                guide.release()
            }

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
                shape.reset()
                guide.release()
            }

            when (decision.intent) {
                is InputIntent.Draw -> {
                    val landed = sampleAt(index)
                    guide.release()
                    // Erasing and lassoing along a straightedge is not a thing anyone does, and
                    // snapping them would make the ruler feel like it had captured the tool.
                    if (rulerEdgeFor != null && tool.tool.isDrawing) {
                        guide.engage(rulerEdgeFor(landed.x, landed.y))
                    }
                    val sample = guide.snap(landed)
                    beginAction(builder, lasso, tool, config, sample, guide.edge)
                    // A stroke drawn against the edge is already the shape it is meant to be;
                    // pausing at the end of a ruled line should not turn it into a rectangle.
                    if (config.autoShapeEnabled && tool.tool.isDrawing && !guide.isEngaged) {
                        shape.begin(sample.x, sample.y, sample.timeMs)
                        startTicking()
                    }
                    // From here the stroke is ours: ancestors may not intercept the moves.
                    claim(true)
                }
                is InputIntent.Navigate -> {
                    // A second finger means pan/zoom. Release the claim so the gesture detector
                    // beneath can pick the pinch up, and report the revoked stroke gone.
                    //
                    // Not while another pointer is still drawing, though — the same guard the
                    // pointer-up branch below applies, and for a sharper reason. Dropping the claim
                    // lets the transform gestures consume the next move, at which point Compose's
                    // interop filter delivers ACTION_CANCEL and latches NotDispatching for the rest
                    // of the stream. Nothing re-asserts the claim on a move, so the pen would be
                    // dead until every pointer left the glass.
                    if (!router.isDrawing) claim(false)
                    shape.reset()
                    guide.release()
                    if (decision.revoked.isNotEmpty()) invalidate()
                    // Consumed all the same when it is the first pointer down — see the class
                    // comment. Saying no here is what used to hang up on the stylus still to come.
                    return event.actionMasked == MotionEvent.ACTION_DOWN
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
                    val sample = guide.snap(raw.copy(x = doc.x, y = doc.y))
                    router.update(raw)
                    when (tool.tool) {
                        ToolId.ERASER_STROKE, ToolId.ERASER_POINT ->
                            if (eraseStep(builder, sample, tool, callbacks)) redraw = true
                        ToolId.LASSO ->
                            if (lasso.add(sample.x, sample.y)) redraw = true
                        // Once a shape has been recognised the pen is no longer drawing: it is
                        // holding the apex it snapped on, and moving adjusts the shape.
                        else -> if (shape.isLive) {
                            if (shape.drag(sample.x, sample.y)) redraw = true
                        } else {
                            if (builder.add(sample)) redraw = true
                            shape.onSample(
                                sample.x, sample.y, sample.timeMs,
                                builder.pointCount, dwellTolerance(),
                            )
                        }
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
                finishAction(builder, lasso, shape, tool, guide.snap(sampleAt(index)), callbacks)
                guide.release()
                invalidate()
                return true
            }
            // A palm lifting off mid-word is not the end of the pen's stroke, so it must not stop
            // the dwell ticker the pen is still relying on — see the claim guard above.
            if (!router.isDrawing) shape.stopTicking()
            return false
        }

        MotionEvent.ACTION_CANCEL -> {
            router.cancel()
            builder.reset()
            lasso.reset()
            shape.reset()
            guide.release()
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
    /** The straightedge the stroke has taken hold of, which the builder has to honour past its
     *  own smoothing — see [StrokeBuilder]'s guide. */
    guideEdge: RulerEdge? = null,
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
            builder.start(tool, config, sample, guideEdge)
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

@Suppress("LongParameterList")
private fun finishAction(
    builder: StrokeBuilder,
    lasso: LassoBuffer,
    shape: ShapeController,
    tool: ToolSpec,
    sample: PointerSample,
    callbacks: InkCallbacks,
) {
    shape.stopTicking()
    when (tool.tool) {
        ToolId.LASSO -> {
            if (lasso.count >= 3) callbacks.onLassoCommitted(lasso.xs, lasso.ys, lasso.count)
            lasso.reset()
        }
        ToolId.ERASER_STROKE, ToolId.ERASER_POINT -> builder.reset()
        else -> {
            // A snapped shape supersedes the stroke it was recognised from; the builder still
            // holds that stroke, and finishing it too would commit both.
            val stroke = shape.commit() ?: builder.finish(sample)
            builder.reset()
            shape.reset()
            // A tap with a pen in hand is a full stop, not a request for the keyboard: the gesture
            // only reached here because the router already decided this pointer was drawing.
            if (stroke != null) callbacks.onStrokeCommitted(stroke)
        }
    }
}

/**
 * How far the pen may wander and still count as held, in document points at 100% zoom.
 *
 * About two thirds of a millimetre on the glass — enough to absorb the tremor of a hand resting
 * against a tablet, tight enough that slowing down at a corner is not mistaken for stopping.
 */
private const val DWELL_TOLERANCE_PT = 2f
private const val MIN_ZOOM = 0.05f

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
