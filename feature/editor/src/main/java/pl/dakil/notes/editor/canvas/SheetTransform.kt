package pl.dakil.notes.editor.canvas

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * Where the sheet sits on screen: one scale and one translation, and nothing else.
 *
 * This replaces the scroll containers the editor used to sit in. Two reasons it has to be ours:
 *
 * 1. **Zoom has to happen about the fingers.** A scroll container's offset is only corrected after
 *    the next layout pass, so its `maxValue` is still the pre-zoom one at the moment the correction
 *    is applied — the anchor drifts, and zooming appears to pull towards the top of the page.
 *    Owning the offset makes the anchor exact arithmetic: the document point under the centroid is
 *    computed before the scale changes and put back underneath it afterwards.
 * 2. **There can only be one input authority.** A `verticalScroll` is a competing claimant to every
 *    drag, and the pen has to win those without an arbitration race. See [InkOverlay].
 *
 * The offset is stored raw and clamped on read, so the sheet re-settles by itself when the document
 * grows a page or the window changes size, with no callback to keep in sync.
 */
@Stable
class SheetTransform {

    private var rawZoom by mutableFloatStateOf(1f)
    private var rawOffsetX by mutableFloatStateOf(Float.NaN)
    private var rawOffsetY by mutableFloatStateOf(0f)

    /** Viewport size in pixels. Plain fields: they change at window pace, not at input pace. */
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    /** Unzoomed content size in pixels. */
    private var contentWidth = 0f
    private var contentHeight = 0f

    /** True once the view has a scale the user (or [fitWidthIfUnset]) chose. */
    private var settled = false

    private var insetTop = 0f
    private var insetBottom = 0f

    val zoom: Float get() = rawZoom

    /**
     * While set, a pinch cannot change the scale — panning is unaffected.
     *
     * The case it exists for is drawing at a chosen scale: resting a hand on the glass mid-stroke
     * routinely registers as a second pointer, and a stroke that ends with the page half a size
     * bigger is one the user then has to undo *and* re-find. An explicit choice from the zoom menu
     * still applies, and the lock stays on around it: the lock guards against the gesture, not
     * against the user asking for a scale by name.
     */
    var zoomLocked by mutableStateOf(false)

    /**
     * Bumped on every scale change the user asked for — a pinch, or a pick from the zoom menu — and
     * never by [fitWidthIfUnset].
     *
     * It exists so the zoom indicator can time its own fade from a `snapshotFlow` without anything
     * having to observe [zoom] during composition. Opening a note is not a zoom, so the initial
     * fit-to-width deliberately leaves this alone and the indicator stays away.
     */
    private var zoomEpochState by mutableIntStateOf(0)
    val zoomEpoch: Int get() = zoomEpochState

    /**
     * Screen x of the sheet's left edge. Clamped on read, so a document that gains or loses pages
     * under a settled view corrects itself without anyone having to notice.
     */
    val offsetX: Float get() = clampX(if (rawOffsetX.isNaN()) restX() else rawOffsetX)
    val offsetY: Float get() = clampY(rawOffsetY)

    fun setViewport(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
    }

    fun setContent(width: Float, height: Float) {
        contentWidth = width
        contentHeight = height
    }

    /**
     * Extra room to scroll beyond the paper, in window pixels.
     *
     * The page chrome lives outside the sheet — a header above each page, an add button below the
     * last one — so the scrollable region has to be slightly larger than the document, or the first
     * header and the add button sit in space the view can never reach.
     */
    fun setContentInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
    }

    /**
     * Opens the note showing the full width of the page.
     *
     * A point is a real 1/72", so an A4 sheet at 1:1 is about 8.3 inches across — roughly three and
     * a half times the width of a phone screen. Starting at 1:1 would open every note onto a
     * fragment of the top-left corner of the paper, with no indication that the rest exists. The
     * page width is the one dimension a reader needs whole, so that is what the first frame shows.
     */
    fun fitWidthIfUnset() {
        if (settled || viewportWidth <= 0f || contentWidth <= 0f) return
        settled = true
        rawZoom = ((viewportWidth - EDGE_PAD * 2f) / contentWidth).coerceIn(MIN_ZOOM, MAX_ZOOM)
        rawOffsetX = Float.NaN
        rawOffsetY = EDGE_PAD + insetTop
    }

    /** Scales by [factor] about the screen point ([focusX], [focusY]), which stays put. */
    fun zoomAround(factor: Float, focusX: Float, focusY: Float) {
        if (zoomLocked) return
        if (applyZoom(rawZoom * factor, focusX, focusY)) zoomEpochState++
    }

    /**
     * Scales to [target] about the middle of the window.
     *
     * The centre is the anchor rather than the top of the page because this is reached from the
     * zoom menu, which the user opens while looking at something: jumping to the top of the
     * document to change scale would mean scrolling back to whatever they were reading.
     */
    fun zoomTo(target: Float) {
        applyZoom(target, viewportWidth * 0.5f, viewportHeight * 0.5f)
        settled = true
        // Bumped even when the scale did not move, so that picking the value you are already at
        // still holds the indicator on screen rather than dismissing it.
        zoomEpochState++
    }

    /** The scale at which the page spans the width of the window — what a note opens at. */
    fun fitWidthZoom(): Float =
        if (viewportWidth <= 0f || contentWidth <= 0f) rawZoom
        else ((viewportWidth - EDGE_PAD * 2f) / contentWidth).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * The scale at which *one* page spans the height of the window.
     *
     * One page, not the strip: "fill height" asks to see a page whole, and fitting a four-page
     * document into the window would answer with four unreadable thumbnails. The top inset comes
     * out of the budget because that band belongs to the page header, so a page sized to the raw
     * window height would open with its own controls sitting over its first line.
     */
    fun fitHeightZoom(pageHeightPx: Float): Float =
        if (viewportHeight <= 0f || pageHeightPx <= 0f) rawZoom
        else ((viewportHeight - insetTop - EDGE_PAD * 2f) / pageHeightPx).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Returns true when the scale actually moved. */
    private fun applyZoom(target: Float, focusX: Float, focusY: Float): Boolean {
        val clamped = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped == rawZoom) return false

        // The content point under the focus, in unzoomed pixels — the invariant of the gesture.
        val contentX = screenToContentX(focusX)
        val contentY = screenToContentY(focusY)

        rawZoom = clamped
        rawOffsetX = clampX(focusX - contentX * clamped)
        rawOffsetY = clampY(focusY - contentY * clamped)
        return true
    }

    fun panBy(dx: Float, dy: Float) {
        rawOffsetX = clampX(offsetX + dx)
        rawOffsetY = clampY(offsetY + dy)
    }

    /**
     * Absolute placement, for the fling animation. Returns false once *both* axes are pinned, which
     * is the signal to stop coasting — either alone would end a vertical fling the moment the sheet
     * happened to be centred horizontally.
     */
    fun setOffset(x: Float, y: Float): Boolean {
        val cx = clampX(x)
        val cy = clampY(y)
        rawOffsetX = cx
        rawOffsetY = cy
        return cx == x || cy == y
    }

    /**
     * Shifts the view by [dy] content pixels, immediately and without animation.
     *
     * Used to keep a page under the reader's eye while it changes position in the note. It must not
     * animate: the page itself jumps to its new index in one frame, and a scroll that eased into
     * place behind it would show exactly the movement it exists to hide.
     */
    fun scrollByContent(dy: Float) {
        rawOffsetY = clampY(offsetY - dy * rawZoom)
    }

    /**
     * Brings content pixel [y] to the top of the window.
     *
     * Animated rather than snapped on purpose. Jumping the view is indistinguishable from the page
     * having been there all along, whereas a short travel says "a page was added, and it is down
     * here" without a toast or a dialog to dismiss.
     */
    suspend fun animateToContentY(y: Float) {
        val from = offsetY
        val to = clampY(EDGE_PAD - y * rawZoom)
        if (from == to) return
        animate(from, to, animationSpec = tween(SCROLL_MS)) { value, _ -> rawOffsetY = clampY(value) }
    }

    /**
     * Scrolls the least it can to bring a band of the strip clear of the keyboard.
     *
     * The least it can, deliberately. Centring what the user is typing on would move the page under
     * their eyes on every keystroke that crossed a line; this does nothing at all while the caret
     * is already in view, and when the caret does go under the keyboard it lifts it just past the
     * edge with a little air to spare.
     *
     * [top] and [bottom] are unzoomed content pixels. There is no keyboard inset to pass: the
     * editor applies `imePadding` to the whole scaffold, so the viewport this works within has
     * already given the keyboard its half of the screen.
     */
    suspend fun revealContentBand(top: Float, bottom: Float) {
        val visibleBottom = viewportHeight
        if (visibleBottom <= 0f) return
        val screenTop = contentToScreenY(top)
        val screenBottom = contentToScreenY(bottom)

        val shift = when {
            // Below the fold: lift it until its foot clears the keyboard.
            screenBottom > visibleBottom - REVEAL_MARGIN -> visibleBottom - REVEAL_MARGIN - screenBottom
            // Above the top of the window: drop it back down to just under the app bar.
            screenTop < REVEAL_MARGIN -> REVEAL_MARGIN - screenTop
            else -> return
        }
        // Never so far that the top of the band goes off the top: for a band taller than what is
        // left of the window, seeing where you are typing beats seeing where it ends.
        val wanted = clampY(offsetY + shift)
        val limited = if (contentToScreenY(top) + (wanted - offsetY) < REVEAL_MARGIN) {
            clampY(offsetY + REVEAL_MARGIN - screenTop)
        } else {
            wanted
        }
        if (limited == offsetY) return
        animate(offsetY, limited, animationSpec = tween(SCROLL_MS)) { value, _ ->
            rawOffsetY = clampY(value)
        }
    }

    /** Back to a full page width, as if the note had just been opened. */
    fun resetZoom() {
        settled = false
        fitWidthIfUnset()
    }

    // ---- Coordinates -----------------------------------------------------------------------------

    // The whole mapping, in two lines. Everything else in this class is a constraint on it.
    fun contentToScreenX(x: Float): Float = offsetX + x * rawZoom
    fun contentToScreenY(y: Float): Float = offsetY + y * rawZoom
    fun screenToContentX(x: Float): Float = (x - offsetX) / rawZoom
    fun screenToContentY(y: Float): Float = (y - offsetY) / rawZoom

    /** Top of the visible band, in unzoomed content pixels. */
    fun visibleTop(): Float = screenToContentY(0f)

    /** Bottom of the visible band, in unzoomed content pixels. */
    fun visibleBottom(): Float = screenToContentY(viewportHeight)

    /** Left of the visible band, in unzoomed content pixels. Negative when the page is centred. */
    fun visibleLeft(): Float = screenToContentX(0f)

    /** Right of the visible band, in unzoomed content pixels. */
    fun visibleRight(): Float = screenToContentX(viewportWidth)

    // ---- Clamping --------------------------------------------------------------------------------

    /**
     * Horizontal rest position: centred when the page is narrower than the window, which is the
     * common case on a phone in portrait and the only placement that does not look like a mistake.
     */
    private fun restX(): Float = ((viewportWidth - contentWidth * rawZoom) * 0.5f)

    private fun clampX(value: Float): Float {
        val scaled = contentWidth * rawZoom
        if (scaled <= viewportWidth) return restX()
        return value.coerceIn(viewportWidth - scaled - EDGE_PAD, EDGE_PAD)
    }

    private fun clampY(value: Float): Float {
        val scaled = contentHeight * rawZoom
        val top = EDGE_PAD + insetTop
        val bottom = viewportHeight - scaled - EDGE_PAD - insetBottom
        if (bottom >= top) return top
        return value.coerceIn(bottom, top)
    }

    companion object {
        /**
         * Low enough that fitting a page's width is always reachable: on a dense phone screen that
         * ratio is around 0.26, so a conventional 0.5 or 0.25 floor would quietly clip it.
         */
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 8f

        /** A little air around the sheet, so it never sits flush against the app bar. */
        const val EDGE_PAD = 24f

        /** How much room to leave between what is being revealed and the edge that hid it. */
        private const val REVEAL_MARGIN = 32f

        /** Long enough to read as travel, short enough not to be a wait. */
        private const val SCROLL_MS = 320

        val Saver: Saver<SheetTransform, List<Float>> = Saver(
            save = { listOf(it.rawZoom, it.rawOffsetX, it.rawOffsetY, if (it.zoomLocked) 1f else 0f) },
            restore = { saved ->
                SheetTransform().apply {
                    rawZoom = saved[0]
                    rawOffsetX = saved[1]
                    rawOffsetY = saved[2]
                    zoomLocked = saved[3] != 0f
                    // A restored view already has a scale the user was looking at; re-fitting it
                    // on the way back from a rotation would throw that away.
                    settled = true
                }
            },
        )
    }
}
