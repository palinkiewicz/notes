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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 */
fun Modifier.sheetTransformGestures(
    transform: SheetTransform,
    scope: CoroutineScope,
): Modifier = pointerInput(transform) {
    val decay: DecayAnimationSpec<Offset> =
        androidx.compose.animation.splineBasedDecay(this)
    var flingJob: Job? = null

    awaitEachGesture {
        // A touch always stops a coasting page — the universal expectation for scrolling surfaces.
        flingJob?.cancel()

        val tracker = VelocityTracker()
        var pastSlop = false
        var travel = 0f
        var lastCentroid = Offset.Unspecified
        var lastSpan = 0f
        var lastCount = 0

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
            val time = event.changes[0].uptimeMillis

            // A pointer arriving or leaving moves the centroid discontinuously. Re-seed on that
            // frame instead of translating the page by a jump the user did not make.
            if (lastCentroid.isUnspecified || pressed.size != lastCount) {
                lastCentroid = centroid
                lastSpan = span
                lastCount = pressed.size
                tracker.resetTracking()
                tracker.addPosition(time, centroid)
                continue
            }

            if (span > 0f && lastSpan > 0f) {
                transform.zoomAround(span / lastSpan, centroid.x, centroid.y)
                // A pinch is unambiguous; it never needs to clear slop first.
                pastSlop = true
            }

            val pan = centroid - lastCentroid
            travel += pan.getDistance()
            if (!pastSlop && travel > viewConfiguration.touchSlop) pastSlop = true

            if (pastSlop) {
                transform.panBy(pan.x, pan.y)
                // Claim the movement so a tap-to-edit underneath does not also fire.
                for (change in event.changes) if (change.positionChanged()) change.consume()
            }

            tracker.addPosition(time, centroid)
            lastCentroid = centroid
            lastSpan = span
            lastCount = pressed.size
        }

        if (pastSlop) {
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
