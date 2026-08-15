package pl.dakil.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.canvas.InkOverlay
import pl.dakil.notes.editor.canvas.PageChrome
import pl.dakil.notes.editor.canvas.SheetPainter
import pl.dakil.notes.editor.canvas.SheetPainter.drawSheet
import pl.dakil.notes.editor.canvas.SheetTransform
import pl.dakil.notes.editor.canvas.sheetTransformGestures
import pl.dakil.notes.editor.markdown.MarkdownBlock
import pl.dakil.notes.editor.markdown.PaginatedFlow
import pl.dakil.notes.model.ViewMode

/**
 * The note surface: one sheet of paper carrying Markdown text with ink drawn over it.
 *
 * There is no text mode and no drawing mode. Three layers are stacked in a single strip — paper,
 * then text, then ink — and the ink overlay on top decides who receives each event: a stylus always
 * draws, a finger draws only when a drawing tool is selected, and anything the overlay declines
 * falls through to the text underneath, or past it to pan and zoom.
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

    // Room above the first page for its header, and below the last for the add button.
    val insetTop = with(density) { CHROME_TOP.toPx() }
    val insetBottom = with(density) { CHROME_BOTTOM.toPx() }
    transform.setContentInsets(insetTop, insetBottom)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .sheetTransformGestures(transform, scope),
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
) {
    val sheet = state.sheet ?: return
    val density = LocalDensity.current
    val format = sheet.format
    val pageCount = sheet.pageCount()

    Box(Modifier.fillMaxWidth()) {

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

        // 2. Text: flows down the strip inside the margins, never breaking through a page boundary.
        PaginatedFlow(
            pageHeightPx = format.height * ptToPx,
            topMarginPx = format.margins.top * ptToPx,
            bottomMarginPx = format.margins.bottom * ptToPx,
            pageGapPx = if (paged) SheetPainter.PAGE_GAP_PT * ptToPx else 0f,
            minPageCount = pageCount,
            onHeightMeasured = { heightPx -> viewModel.reportContentHeight(heightPx / ptToPx) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = with(density) { (format.margins.left * ptToPx).toDp() },
                    end = with(density) { (format.margins.right * ptToPx).toDp() },
                ),
        ) {
            MarkdownBlock(
                markdown = sheet.markdown,
                focused = state.editingText,
                readOnly = state.isReadOnly,
                onMarkdownChange = viewModel::updateText,
                onFocusRequested = viewModel::beginTextEditing,
                onFocusLost = viewModel::endTextEditing,
                placeholderText = "Write, or just start drawing…",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 3. Ink: over everything, and the arbiter of who gets each pointer event.
        InkOverlay(
            sheet = sheet,
            tool = state.tool,
            inputConfig = state.inputConfig,
            ptToPx = ptToPx,
            paged = paged,
            documentVersion = state.documentVersion,
            callbacks = viewModel.inkCallbacks,
            darkTheme = darkTheme,
            // A lambda, not a value: the lasso outline needs the scale to stay a constant width on
            // screen, and reading it here instead of at the call site keeps a pinch off the
            // recomposition path entirely.
            zoom = transform::zoom,
            modifier = Modifier.matchParentSize(),
        )
    }
}
