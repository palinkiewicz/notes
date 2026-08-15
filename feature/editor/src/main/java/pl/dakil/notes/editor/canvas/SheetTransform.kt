package pl.dakil.notes.editor.canvas

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
        val target = (rawZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (target == rawZoom) return

        // The content point under the focus, in unzoomed pixels — the invariant of the gesture.
        val contentX = screenToContentX(focusX)
        val contentY = screenToContentY(focusY)

        rawZoom = target
        rawOffsetX = clampX(focusX - contentX * target)
        rawOffsetY = clampY(focusY - contentY * target)
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

        /** Long enough to read as travel, short enough not to be a wait. */
        private const val SCROLL_MS = 320

        val Saver: Saver<SheetTransform, List<Float>> = Saver(
            save = { listOf(it.rawZoom, it.rawOffsetX, it.rawOffsetY) },
            restore = { saved ->
                SheetTransform().apply {
                    rawZoom = saved[0]
                    rawOffsetX = saved[1]
                    rawOffsetY = saved[2]
                    // A restored view already has a scale the user was looking at; re-fitting it
                    // on the way back from a rotation would throw that away.
                    settled = true
                }
            },
        )
    }
}
