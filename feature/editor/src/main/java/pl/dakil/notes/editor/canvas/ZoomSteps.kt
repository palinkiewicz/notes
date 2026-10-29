package pl.dakil.notes.editor.canvas

/**
 * Where zooming in or out from the keyboard goes next: the nearest stop past [current], as a
 * user zoom (1 = 100 %), or null when there is none in that direction.
 *
 * The stops are a fixed ladder plus the user's own pinned presets, so the keyboard passes through
 * the same scales the zoom menu offers rather than inventing a parallel set. [current] is skipped
 * when it sits within a hair of a stop, or a press at exactly 100 % would "step" to 100 %.
 */
internal fun zoomStep(current: Float, presets: List<Int>, zoomIn: Boolean): Float? {
    val stops = (LADDER + presets + 100).distinct().sorted().map { it / 100f }
    val tolerance = 0.005f
    return if (zoomIn) stops.firstOrNull { it > current + tolerance }
    else stops.lastOrNull { it < current - tolerance }
}

private val LADDER = listOf(10, 25, 50, 75, 100, 125, 150, 200, 300, 400, 600, 800)
