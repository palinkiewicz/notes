package pl.dakil.notes.editor.canvas

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Pan and zoom for the sheet, as the only navigation authority in the editor.
 *
 * Written out rather than composed from `detectTransformGestures` + `verticalScroll` because those
 * two disagree about who owns a drag, and the pen has to win that argument deterministically. Here
 * the rule is a single line: **an event that has already been consumed belongs to the ink**, and
 * navigation re-seeds itself rather than fighting for it.
 *
 * Re-seeding is also what makes a handover mid-gesture work. Rest a palm, start a stroke, then put
 * a second finger down: the router revokes the stroke, the overlay stops consuming, and this picks
 * the pinch up from wherever the fingers now are instead of snapping the page by the accumulated
 * difference.
 *
 * ### Why the ruler is moved from here
 *
 * Two fingers on the ruler carry the ruler; two fingers anywhere else pan and zoom the page. That
 * has to be decided in one place, by whoever already owns multi-touch — a second gesture detector
 * layered over the slab would be a second claimant to every pinch, which is the arrangement this
 * file exists to avoid. [ruler] is null whenever the tool is off, so nothing here changes shape
 * for the users who never turn it on.
 */
fun Modifier.sheetTransformGestures(
    transform: SheetTransform,
    scope: CoroutineScope,
    ruler: RulerState? = null,
): Modifier = pointerInput(transform, ruler) {
    val decay: DecayAnimationSpec<Offset> =
        androidx.compose.animation.splineBasedDecay(this)
    var flingJob: Job? = null
    val grabMargin = RULER_GRAB_MARGIN.toPx()

    awaitEachGesture {
        // A touch always stops a coasting page — the universal expectation for scrolling surfaces.
        flingJob?.cancel()

        val tracker = VelocityTracker()
        var pastSlop = false
        var travel = 0f
        var lastCentroid = Offset.Unspecified
        var lastSpan = 0f
        var lastCount = 0
        var lastAngle = Float.NaN
        var movingRuler = false
        var decided = false

        awaitFirstDown(requireUnconsumed = false)

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            // Consumed means the ink overlay claimed this gesture (see InkOverlay's use of
            // RequestDisallowInterceptTouchEvent). Drop the accumulated frame of reference so that
            // if it ever hands back, we resume from the fingers' real position.
            if (event.changes.any { it.isConsumed }) {
                lastCentroid = Offset.Unspecified
                lastCount = 0
                tracker.resetTracking()
                continue
            }

            val centroid = centroidOf(pressed)
            val span = spanOf(pressed, centroid)
            val angle = angleOf(pressed)
            val time = event.changes[0].uptimeMillis

            // Decided once, on the first frame that has two fingers on the glass, and then held:
            // a pinch that happens to drift across the slab must not turn into a ruler drag
            // halfway through, and a ruler being carried must not become a pinch when the fingers
            // slide off it.
            //
            // *Every* finger has to be on the slab, not their midpoint. A ruler lying across the
            // middle of the screen is exactly where a pinch is centred, so testing the centroid
            // would hand the ruler most of the zoom gestures the user meant for the page — while
            // asking for both fingertips on a thing you can see is what anyone reaching for a
            // ruler on a desk does anyway.
            if (!decided && ruler != null && pressed.size >= 2) {
                decided = true
                val zoom = transform.zoom
                val pose = ruler.pose(zoom)
                val margin = grabMargin / zoom
                movingRuler = ruler.isPlaced && pressed.all { change ->
                    pose.grabbedAt(
                        x = transform.screenToContentX(change.position.x),
                        y = transform.screenToContentY(change.position.y),
                        margin = margin,
                    )
                }
                ruler.dragging = movingRuler
            }

            // A pointer arriving or leaving moves the centroid discontinuously. Re-seed on that
            // frame instead of translating the page by a jump the user did not make.
            if (lastCentroid.isUnspecified || pressed.size != lastCount) {
                lastCentroid = centroid
                lastSpan = span
                lastCount = pressed.size
                lastAngle = angle
                tracker.resetTracking()
                tracker.addPosition(time, centroid)
                continue
            }

            val pan = centroid - lastCentroid

            if (movingRuler && ruler != null) {
                // The page does not move at all while the ruler is being placed: a straightedge
                // that slid the paper as it was lined up would never line up with anything. The
                // spread that would have zoomed the page sizes the ruler instead — the same
                // gesture, applied to whichever of the two the fingers are holding.
                val zoom = transform.zoom
                ruler.transformBy(
                    dx = pan.x / zoom,
                    dy = pan.y / zoom,
                    dAngle = angleDelta(lastAngle, angle),
                    lengthFactor = if (span > 0f && lastSpan > 0f) span / lastSpan else 1f,
                    pivotX = transform.screenToContentX(centroid.x),
                    pivotY = transform.screenToContentY(centroid.y),
                )
                for (change in event.changes) if (change.positionChanged()) change.consume()
            } else {
                if (span > 0f && lastSpan > 0f) {
                    transform.zoomAround(span / lastSpan, centroid.x, centroid.y)
                    // A pinch is unambiguous; it never needs to clear slop first.
                    pastSlop = true
                }

                travel += pan.getDistance()
                if (!pastSlop && travel > viewConfiguration.touchSlop) pastSlop = true

                if (pastSlop) {
                    transform.panBy(pan.x, pan.y)
                    // Claim the movement so a tap-to-edit underneath does not also fire.
                    for (change in event.changes) if (change.positionChanged()) change.consume()
                }
            }

            tracker.addPosition(time, centroid)
            lastCentroid = centroid
            lastSpan = span
            lastCount = pressed.size
            lastAngle = angle
        }

        ruler?.dragging = false

        // A ruler is put down where it is let go of; only the page coasts.
        if (pastSlop && !movingRuler) {
            val velocity = tracker.calculateVelocity()
            val v = Offset(velocity.x, velocity.y)
            if (v.getDistance() > MIN_FLING_VELOCITY) {
                flingJob = scope.launch { transform.coastTo(v, decay) }
            }
        }
    }
}

private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var sum = Offset.Zero
    for (change in changes) sum += change.position
    return sum / changes.size.toFloat()
}

/** Mean distance from the centroid — the pinch scale reference. Zero for a single pointer. */
private fun spanOf(changes: List<PointerInputChange>, centroid: Offset): Float {
    if (changes.size < 2) return 0f
    var sum = 0f
    for (change in changes) sum += (change.position - centroid).getDistance()
    return sum / changes.size
}

/**
 * The heading of the line between the first two pointers, or NaN with fewer than two down.
 *
 * Sorted by pointer id rather than taken in arrival order, so the two fingers cannot swap places
 * between frames and hand the ruler a 180° turn nobody made.
 */
private fun angleOf(changes: List<PointerInputChange>): Float {
    if (changes.size < 2) return Float.NaN
    val sorted = changes.sortedBy { it.id.value }
    val delta = sorted[1].position - sorted[0].position
    return atan2(delta.y, delta.x)
}

/** The turn from one heading to the next, folded into ±180° so the wrap-around is not a spin. */
private fun angleDelta(from: Float, to: Float): Float {
    if (from.isNaN() || to.isNaN()) return 0f
    var delta = to - from
    val full = (2.0 * PI).toFloat()
    while (delta > PI) delta -= full
    while (delta < -PI) delta += full
    return delta
}

private suspend fun SheetTransform.coastTo(velocity: Offset, decay: DecayAnimationSpec<Offset>) {
    AnimationState(
        typeConverter = Offset.VectorConverter,
        initialValue = Offset(offsetX, offsetY),
        initialVelocity = velocity,
    ).animateDecay(decay) {
        if (!setOffset(value.x, value.y)) cancelAnimation()
    }
}

/** Below this the residual velocity of a lifting finger reads as jitter, not as a throw. */
private const val MIN_FLING_VELOCITY = 80f

/**
 * How far outside the slab two fingers may land and still be taking hold of the ruler.
 *
 * Generous on purpose: the ruler is thin, the target is a moving object, and a grab that misses
 * pans the page out from under it — which is much more annoying than a grab that catches one
 * finger-width early.
 */
private val RULER_GRAB_MARGIN = 20.dp
