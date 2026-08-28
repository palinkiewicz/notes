package pl.dakil.notes.editor.canvas

import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.Rect
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The handles round a selection, and the transforms dragging one produces.
 *
 * Plain Kotlin with no Compose in sight, so the maths that decides where a scaled selection lands
 * is unit-testable on the JVM rather than only observable by dragging a corner on a phone. The
 * chrome that draws the grips is [SelectionChrome]; all it does with this file is map the answers
 * onto the glass.
 *
 * Everything here is in document points, the same space [Rect] and [Affine] are in everywhere else.
 */
enum class SelectionHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, ROTATE }

/** Where the grip for [handle] sits, as (x, y) in document points. */
fun handlePosition(bounds: Rect, handle: SelectionHandle): Pair<Float, Float> = when (handle) {
    SelectionHandle.TOP_LEFT -> bounds.left to bounds.top
    SelectionHandle.TOP_RIGHT -> bounds.right to bounds.top
    SelectionHandle.BOTTOM_LEFT -> bounds.left to bounds.bottom
    SelectionHandle.BOTTOM_RIGHT -> bounds.right to bounds.bottom
    // Centred below the frame; the chrome lifts it clear of the bottom edge in window pixels,
    // where a fixed gap is a fixed gap however far the page is zoomed. Below rather than above
    // because the action bar takes the space over the selection, where it does not cover the work.
    SelectionHandle.ROTATE -> (bounds.left + bounds.right) * 0.5f to bounds.bottom
}

/**
 * The point a drag on [handle] pivots about: for a corner, the corner diagonally opposite.
 *
 * That is what makes a scale feel like dragging the sheet's edge rather than pushing the whole
 * thing across the page — the far corner is nailed down and only the dragged one moves. Rotation
 * pivots about the middle, because there is no other point a rotation could sensibly be about.
 */
fun handleAnchor(bounds: Rect, handle: SelectionHandle): Pair<Float, Float> = when (handle) {
    SelectionHandle.TOP_LEFT -> bounds.right to bounds.bottom
    SelectionHandle.TOP_RIGHT -> bounds.left to bounds.bottom
    SelectionHandle.BOTTOM_LEFT -> bounds.right to bounds.top
    SelectionHandle.BOTTOM_RIGHT -> bounds.left to bounds.top
    SelectionHandle.ROTATE -> (bounds.left + bounds.right) * 0.5f to (bounds.top + bounds.bottom) * 0.5f
}

/**
 * The transform for dragging a corner grip from where it was grabbed to where the finger is now.
 *
 * Uniform on purpose. A non-uniform scale would drop a recognised shape's `ShapeSpec` — see
 * `ShapeSpec.transformedBy`, which answers null for anything that is not a similarity — and leaves
 * a stroke's width undefined, since `Stroke.transformed` can only carry one scalar through. So the
 * factor is the ratio of the two distances from the anchor, and the aspect ratio is preserved.
 *
 * The factor is clamped rather than allowed through: pulling a corner exactly onto its anchor
 * would scale the selection to nothing, at which point its bounds are a point and there is no
 * corner left to drag back out.
 */
fun scaleMatrix(
    bounds: Rect,
    handle: SelectionHandle,
    fromX: Float, fromY: Float,
    toX: Float, toY: Float,
): Affine {
    val (ax, ay) = handleAnchor(bounds, handle)
    val was = hypot(fromX - ax, fromY - ay)
    if (was < MIN_GRAB_DISTANCE) return Affine.IDENTITY
    val now = hypot(toX - ax, toY - ay)
    val factor = (now / was).coerceIn(MIN_SCALE, MAX_SCALE)
    return Affine.scale(factor, factor, ax, ay)
}

/** The rotation for dragging the rotate grip, about the centre of [bounds]. */
fun rotateMatrix(bounds: Rect, fromX: Float, fromY: Float, toX: Float, toY: Float): Affine {
    val (cx, cy) = handleAnchor(bounds, SelectionHandle.ROTATE)
    if (hypot(fromX - cx, fromY - cy) < MIN_GRAB_DISTANCE) return Affine.IDENTITY
    val was = atan2(fromY - cy, fromX - cx)
    val now = atan2(toY - cy, toX - cx)
    return Affine.rotate(now - was, cx, cy)
}

/**
 * How close to the anchor a grab may be and still define a direction, in document points.
 *
 * A grab on the anchor itself has no angle and no length, so both the ratio and the `atan2`
 * difference below it are noise rather than intent.
 */
private const val MIN_GRAB_DISTANCE = 0.5f

/** Small enough to shrink a page of writing to a marginal note, not so small it vanishes. */
const val MIN_SCALE = 0.05f

/** Generous, and only there so a slip near the anchor cannot throw the selection off the sheet. */
const val MAX_SCALE = 20f

/**
 * A little air between the selection's outline and the ink inside it, in document points.
 *
 * Shared by the overlay that draws the outline and the chrome that places grips on it, so the two
 * cannot drift apart and leave the handles sitting off the box they belong to.
 */
const val SELECTION_PAD_PT = 4f
