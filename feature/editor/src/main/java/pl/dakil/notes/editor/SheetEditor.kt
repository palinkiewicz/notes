package pl.dakil.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.canvas.InkOverlay
import pl.dakil.notes.editor.canvas.PageChrome
import pl.dakil.notes.editor.canvas.RULER_END_MARGIN
import pl.dakil.notes.editor.canvas.RULER_SNAP_BAND
import pl.dakil.notes.editor.canvas.RULER_THICKNESS
import pl.dakil.notes.editor.canvas.RulerOverlay
import pl.dakil.notes.editor.canvas.RulerState
import pl.dakil.notes.ui.sheet.SheetPainter
import pl.dakil.notes.editor.canvas.rulerEdgeAt
import pl.dakil.notes.ui.sheet.SheetPainter.drawSheet
import pl.dakil.notes.editor.canvas.SheetTransform
import pl.dakil.notes.editor.canvas.ZoomChip
import pl.dakil.notes.editor.canvas.sheetTransformGestures
import pl.dakil.notes.model.ViewMode

/**
 * The note surface: one sheet of paper carrying Markdown text with ink drawn over it.
 *
 * There is no text mode and no drawing mode. Three layers are stacked in a single strip — paper,
 * then text boxes, then ink — and the ink overlay on top decides who receives each event: a stylus
 * draws unless the text tool is out, a finger draws only when a drawing tool is selected, and
 * anything the overlay declines falls through to the boxes underneath, or past them to pan and zoom.
 *
 * [ViewMode] changes nothing about the document. Paged view draws page edges and a gap between
 * sheets; continuous view omits them. The pagination, and therefore what will print, is identical.
 *
 * ### Why there is no scroll container
 *
 * The sheet is measured once at its true size and then *placed* through a single scale-and-
 * translate ([SheetTransform]). Two things fall out of that. Text is always measured at 1×, so
 * zooming in to read something can never reflow the document or move a page break; and there is
 * exactly one claimant to a drag, so the pen never has to win a race against a scroll container to
 * finish a stroke.
 */
@Composable
fun SheetEditor(
    state: EditorUiState,
    viewModel: NoteViewModel,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val sheet = state.sheet ?: return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // One typographic point is 1/72", one dp is 1/160", so a point is 160/72 dp. This is the single
    // conversion between the document's coordinates and the screen's, and it is deliberately free
    // of zoom: the zoom lives in the placement transform, never in the geometry.
    val ptToPx = with(density) { (160f / 72f).dp.toPx() }

    val transform = rememberSaveable(saver = SheetTransform.Saver) { SheetTransform() }

    // The straightedge, if the user has one out. It is kept whether or not it is switched on, so
    // turning the tool off to see the page underneath and back on again does not lose the angle
    // that was just lined up.
    val ruler = rememberSaveable(saver = RulerState.Saver) { RulerState() }
    ruler.configure(
        ptToPx = ptToPx,
        thicknessOnGlassPx = with(density) { RULER_THICKNESS.toPx() },
        endMarginOnGlassPx = with(density) { RULER_END_MARGIN.toPx() },
    )

    val format = sheet.format
    val paged = state.view == ViewMode.PAGED
    val stripWidthPx = (format.width * ptToPx).toInt()
    val pageCount = sheet.pageCount()

    // Bring a newly added page into view. The strip's size is worked out arithmetically rather than
    // read from the last layout: the page was added in the composition this effect belongs to, so a
    // measured height would still be one page short and the scroll would stop just above the thing
    // it was asked to reveal.
    LaunchedEffect(state.scrollRequest) {
        val request = state.scrollRequest ?: return@LaunchedEffect
        transform.setContent(
            stripWidthPx.toFloat(),
            SheetPainter.stripHeightPx(format, pageCount, ptToPx, paged),
        )
        val tops = SheetPainter.pageTops(format, pageCount, ptToPx, paged)
        transform.animateToContentY(tops.getOrNull(request.page) ?: return@LaunchedEffect)
    }

    // Where the caret is in the strip, and the keyboard's height. The sheet is the only thing that
    // scrolls, so bringing the caret out from under the keyboard is its job — and the inset is part
    // of the key because the keyboard arriving is itself a reason to scroll, with the caret still
    // exactly where it was.
    var caretBand by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(caretBand, imeBottom) {
        val (top, bottom) = caretBand ?: return@LaunchedEffect
        if (state.editingTextBlock == null) return@LaunchedEffect
        transform.revealContentBand(top, bottom)
    }

    // Bring a ruler that was left behind on another page back under the reader's eye. A ruler
    // already on screen is left exactly where it was set down — that is where the user put it.
    LaunchedEffect(state.rulerEnabled) {
        if (state.rulerEnabled) {
            ruler.ensureVisible(
                left = transform.visibleLeft(),
                top = transform.visibleTop(),
                right = transform.visibleRight(),
                bottom = transform.visibleBottom(),
            )
        }
    }

    // Room above the first page for its header, and below the last for the add button. Continuous
    // view draws no headers, so it asks for no room above the paper — reserving it there would
    // leave a band of empty background the view can scroll to and nothing ever occupies.
    val insetTop = with(density) { if (paged) CHROME_TOP.toPx() else 0f }
    val insetBottom = with(density) { CHROME_BOTTOM.toPx() }
    transform.setContentInsets(insetTop, insetBottom)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .sheetTransformGestures(transform, scope, ruler = if (state.rulerEnabled) ruler else null),
    ) {
        Layout(
            content = {
                SheetLayers(
                    state = state,
                    viewModel = viewModel,
                    ptToPx = ptToPx,
                    paged = paged,
                    darkTheme = darkTheme,
                    transform = transform,
                    ruler = ruler,
                )
            },
        ) { measurables, constraints ->
            // The viewport is taken from the incoming constraints rather than from onSizeChanged,
            // which does not fire until after this measure has already run: on the first frame the
            // fit-to-width below would otherwise have no window size to fit to. Unbounded
            // constraints would report a viewport of Int.MAX and convince the clamp that any
            // document fits on screen, so they are ignored rather than believed.
            if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                transform.setViewport(
                    constraints.maxWidth.toFloat(),
                    constraints.maxHeight.toFloat(),
                )
            }

            // Measured at the sheet's true width whatever the zoom, and with no height limit: the
            // strip is as tall as the document, not as tall as the window.
            val placeable = measurables.first().measure(
                Constraints(minWidth = stripWidthPx, maxWidth = stripWidthPx)
            )
            transform.setContent(placeable.width.toFloat(), placeable.height.toFloat())
            transform.fitWidthIfUnset()

            // The first place a ruler goes is across the middle of what is on screen. Done here
            // rather than at the toggle because this is the first moment the viewport is known —
            // the same reason fit-to-width is settled here.
            if (state.rulerEnabled && constraints.hasBoundedHeight) {
                ruler.placeIfUnset(
                    x = placeable.width * 0.5f,
                    y = transform.screenToContentY(constraints.maxHeight * 0.5f),
                )
            }

            // This node stays window-sized; the sheet overflows it and is clipped by the parent.
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeWithLayer(0, 0) {
                    val zoom = transform.zoom
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                }
            }
        }

        // The active box's handles. Above the sheet and outside its transform for the same reason
        // the page headers are: a handle scaled to a quarter of itself cannot be hit.
        if (sheet.textBlocks().isNotEmpty()) {
            TextBoxChrome(
                sheet = sheet,
                state = state,
                viewModel = viewModel,
                transform = transform,
                ptToPx = ptToPx,
                paged = paged,
                onCaretBand = { top, bottom -> caretBand = top to bottom },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Above the sheet and outside its transform, so it stays legible and tappable at any zoom.
        PageChrome(
            format = format,
            pageCount = pageCount,
            paged = paged,
            ptToPx = ptToPx,
            transform = transform,
            canEditPage = sheet::canEditPage,
            canMoveUp = sheet::canMovePageUp,
            canMoveDown = sheet::canMovePageDown,
            onDuplicatePage = viewModel::duplicatePage,
            onRemovePage = viewModel::removePage,
            onMovePage = { from, to ->
                // Scroll with the page, in the same frame the swap is committed, so the page the
                // user is moving stays exactly where they are looking and its neighbours are what
                // appear to move. Without this, walking a page up through a long note means
                // chasing it down the screen between every tap.
                if (viewModel.movePage(from, to)) {
                    val stride = SheetPainter.pageStridePx(format, ptToPx, paged)
                    transform.scrollByContent((to - from) * stride)
                }
            },
            onAddPage = viewModel::addPage,
            modifier = Modifier.fillMaxSize(),
        )

        // Over the top of the page, in the window's coordinates like the rest of the chrome. It is
        // absent from the layout almost all of the time — see ZoomChip for when it appears.
        ZoomChip(
            transform = transform,
            presets = state.zoomPresets,
            pageHeightPx = format.height * ptToPx,
            onAddPreset = viewModel::addZoomPreset,
            onRemovePreset = viewModel::removeZoomPreset,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
        )
    }
}

/** Tall enough for the header's 48dp touch targets plus the gap above the paper. */
private val CHROME_TOP = 60.dp
private val CHROME_BOTTOM = 72.dp

/**
 * Paper, text and ink, stacked in that order and all in the same coordinate space.
 *
 * Everything here is in unzoomed document pixels. Because the whole stack is scaled at placement,
 * the ink overlay's pointer coordinates arrive already mapped back through the transform, so no
 * layer below has to know the zoom exists.
 */
@Composable
private fun SheetLayers(
    state: EditorUiState,
    viewModel: NoteViewModel,
    ptToPx: Float,
    paged: Boolean,
    darkTheme: Boolean,
    transform: SheetTransform,
    ruler: RulerState,
) {
    val sheet = state.sheet ?: return
    val density = LocalDensity.current
    val format = sheet.format
    val pageCount = sheet.pageCount()

    // Asked at pen-down only, so it reads the zoom and the ruler's pose when the pen lands rather
    // than when this composed.
    val snapBandPx = with(density) { RULER_SNAP_BAND.toPx() }
    val nibWidth = state.tool.width
    val rulerEdgeFor = remember(ruler, ptToPx, format, paged, snapBandPx, nibWidth, transform) {
        { x: Float, y: Float ->
            val zoom = transform.zoom
            rulerEdgeAt(
                pose = ruler.pose(zoom),
                docX = x,
                docY = y,
                band = snapBandPx / zoom.coerceAtLeast(0.05f),
                ptToPx = ptToPx,
                format = format,
                paged = paged,
                outward = nibWidth * 0.5f,
            )
        }
    }

    // The strip's height, worked out from the paper rather than measured from its contents. The
    // flow used to settle it — it was the one child tall enough to matter — and with the text in
    // boxes there is nothing left that has to be laid out before the page count is known.
    val stripHeight = with(density) {
        SheetPainter.stripHeightPx(format, pageCount, ptToPx, paged).toDp()
    }

    Box(Modifier.fillMaxWidth().height(stripHeight)) {

        // 1. Paper: the pages, their rule pattern, and the page edges when paged.
        Canvas(Modifier.matchParentSize()) {
            drawSheet(
                format = format,
                pageCount = pageCount,
                ptToPx = ptToPx,
                paged = paged,
                zoom = transform.zoom,
                // Only the band under the window is emitted, so a fifty-page note costs what a
                // one-page note costs. Read inside the draw lambda, so panning invalidates the
                // draw phase alone.
                visibleTopPx = transform.visibleTop(),
                visibleBottomPx = transform.visibleBottom(),
            )
        }

        // 2. Text: the boxes, each standing where it was put and clipped to its own page.
        TextBoxLayer(
            sheet = sheet,
            state = state,
            viewModel = viewModel,
            ptToPx = ptToPx,
            paged = paged,
            // A lambda for the same reason the lasso outline takes one: the reach around a box is a
            // size on the screen, and reading the zoom at the tap keeps it one.
            zoom = transform::zoom,
            modifier = Modifier.matchParentSize(),
        )

        // 3. Ink: over everything, and the arbiter of who gets each pointer event.
        InkOverlay(
            sheet = sheet,
            tool = state.tool,
            inputConfig = state.inputConfig,
            ptToPx = ptToPx,
            paged = paged,
            documentVersion = state.documentVersion,
            callbacks = viewModel.inkCallbacks,
            textToolActive = state.textToolActive,
            darkTheme = darkTheme,
            // A lambda, not a value: the lasso outline needs the scale to stay a constant width on
            // screen, and reading it here instead of at the call site keeps a pinch off the
            // recomposition path entirely.
            zoom = transform::zoom,
            // Lambdas for the same reason, and read inside the overlay's draw lambda: ink outside
            // the window is not drawn, so a long note costs a screenful rather than a document.
            visibleTopPx = transform::visibleTop,
            visibleBottomPx = transform::visibleBottom,
            rulerEdgeFor = if (state.rulerEnabled && ruler.isPlaced) rulerEdgeFor else null,
            modifier = Modifier.matchParentSize(),
        )

        // 4. The ruler, over the ink it is there to straighten. It takes no pointer input at all —
        // two fingers on it are handled by the one gesture authority, in sheetTransformGestures.
        if (state.rulerEnabled) {
            RulerOverlay(
                ruler = ruler,
                transform = transform,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
