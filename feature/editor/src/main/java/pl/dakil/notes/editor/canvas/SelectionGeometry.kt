package pl.dakil.notes.editor.canvas

import pl.dakil.notes.ink.snapRulerAngle
import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.Rect
import kotlin.math.abs
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
enum class SelectionHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT,
    ROTATE;

    /** A grip in the middle of an edge, which stretches along one axis instead of scaling evenly. */
    val isEdge: Boolean get() = this == TOP || this == BOTTOM || this == LEFT || this == RIGHT

    /** Whether this grip's scale is read across the page — the left and right edges, and corners. */
    val scalesAcross: Boolean get() = this != TOP && this != BOTTOM
}

/** Where the grip for [handle] sits, as (x, y) in document points. */
fun handlePosition(bounds: Rect, handle: SelectionHandle): Pair<Float, Float> = when (handle) {
    SelectionHandle.TOP_LEFT -> bounds.left to bounds.top
    SelectionHandle.TOP_RIGHT -> bounds.right to bounds.top
    SelectionHandle.BOTTOM_LEFT -> bounds.left to bounds.bottom
    SelectionHandle.BOTTOM_RIGHT -> bounds.right to bounds.bottom
    SelectionHandle.TOP -> (bounds.left + bounds.right) * 0.5f to bounds.top
    SelectionHandle.BOTTOM -> (bounds.left + bounds.right) * 0.5f to bounds.bottom
    SelectionHandle.LEFT -> bounds.left to (bounds.top + bounds.bottom) * 0.5f
    SelectionHandle.RIGHT -> bounds.right to (bounds.top + bounds.bottom) * 0.5f
    // Centred below the frame; the chrome lifts it clear of the bottom edge in window pixels,
    // where a fixed gap is a fixed gap however far the page is zoomed. Below rather than above
    // because the action bar takes the space over the selection, where it does not cover the work.
    SelectionHandle.ROTATE -> (bounds.left + bounds.right) * 0.5f to bounds.bottom
}

/**
 * The point a drag on [handle] pivots about: for a corner, the corner diagonally opposite; for an
 * edge, the middle of the opposite edge.
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
    SelectionHandle.TOP -> handlePosition(bounds, SelectionHandle.BOTTOM)
    SelectionHandle.BOTTOM -> handlePosition(bounds, SelectionHandle.TOP)
    SelectionHandle.LEFT -> handlePosition(bounds, SelectionHandle.RIGHT)
    SelectionHandle.RIGHT -> handlePosition(bounds, SelectionHandle.LEFT)
    SelectionHandle.ROTATE -> (bounds.left + bounds.right) * 0.5f to (bounds.top + bounds.bottom) * 0.5f
}

/**
 * The transform for dragging a scale grip from where it was grabbed to where the finger is now.
 *
 * A corner is uniform on purpose: the factor is the ratio of the two distances from the anchor, so
 * the aspect ratio is kept and a recognised shape stays the shape it was. An edge is the grip for
 * changing the aspect, and stretches along its one axis only — see `ShapeSpec.transformedBy` for
 * which shapes survive that.
 *
 * The factor is clamped rather than allowed through: pulling a grip exactly onto its anchor would
 * scale the selection to nothing, at which point its bounds are a point and there is nothing left to
 * drag back out. For an edge the ratio is signed, so dragging it through the opposite edge stops at
 * the clamp rather than turning the selection inside out; the mirror buttons are for that.
 */
fun scaleMatrix(
    bounds: Rect,
    handle: SelectionHandle,
    fromX: Float, fromY: Float,
    toX: Float, toY: Float,
): Affine {
    val (ax, ay) = handleAnchor(bounds, handle)
    if (handle.isEdge) {
        val (was, now) = if (handle.scalesAcross) fromX - ax to toX - ax else fromY - ay to toY - ay
        if (abs(was) < MIN_GRAB_DISTANCE) return Affine.IDENTITY
        val factor = (now / was).coerceIn(MIN_SCALE, MAX_SCALE)
        return if (handle.scalesAcross) Affine.scale(factor, 1f, ax, ay) else Affine.scale(1f, factor, ax, ay)
    }
    val was = hypot(fromX - ax, fromY - ay)
    if (was < MIN_GRAB_DISTANCE) return Affine.IDENTITY
    val now = hypot(toX - ax, toY - ay)
    val factor = (now / was).coerceIn(MIN_SCALE, MAX_SCALE)
    return Affine.scale(factor, factor, ax, ay)
}

/**
 * The rotation for dragging the rotate grip, about the centre of [bounds].
 *
 * The turn *made* is what snaps to [ROTATION_STEP], not the angle the ink ends up at: a selection of
 * handwriting has no angle of its own to square up, and one already turned 7° should still turn in
 * clean steps from there.
 */
fun rotateMatrix(bounds: Rect, fromX: Float, fromY: Float, toX: Float, toY: Float): Affine {
    val (cx, cy) = handleAnchor(bounds, SelectionHandle.ROTATE)
    if (hypot(fromX - cx, fromY - cy) < MIN_GRAB_DISTANCE) return Affine.IDENTITY
    val was = atan2(fromY - cy, fromX - cx)
    val now = atan2(toY - cy, toX - cx)
    val turn = snapRulerAngle(normalizedTurn(now - was), ROTATION_STEP, ROTATION_SNAP)
    return Affine.rotate(turn, cx, cy)
}

/** [radians] brought into (−π, π], so a turn past the back of the circle snaps like any other. */
private fun normalizedTurn(radians: Float): Float {
    val half = Math.PI.toFloat()
    val full = 2f * half
    var a = radians
    while (a > half) a -= full
    while (a <= -half) a += full
    return a
}

/** The turn a selection transform makes, in radians — what the rotation readout shows. */
fun Affine.turnAngle(): Float = atan2(b, a)

/** Flips [bounds] over about its own middle: left to right when [horizontal], else top to bottom. */
fun mirrorMatrix(bounds: Rect, horizontal: Boolean): Affine {
    val cx = (bounds.left + bounds.right) * 0.5f
    val cy = (bounds.top + bounds.bottom) * 0.5f
    return if (horizontal) Affine.scale(-1f, 1f, cx, cy) else Affine.scale(1f, -1f, cx, cy)
}

/** Forty-five degrees: the right angles and the diagonals, and nothing between to fight over. */
val ROTATION_STEP = Math.toRadians(45.0).toFloat()

/** How near a step a turn has to come to be pulled onto it: about three degrees. */
val ROTATION_SNAP = Math.toRadians(3.0).toFloat()

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
