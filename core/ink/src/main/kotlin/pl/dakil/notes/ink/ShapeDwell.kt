package pl.dakil.notes.ink

import kotlin.math.abs

/**
 * Watches for the pen being held still at the end of a stroke.
 *
 * The dwell is the whole user-facing contract of auto-shape: lift normally and nothing happens,
 * pause and the stroke snaps. Getting the *end* of the dwell right matters as much as the start —
 * the samples deposited while holding still are a tight little knot at one point, and feeding those
 * to the recogniser drags a corner towards the middle of the shape. [anchorPointIndex] records how
 * much of the stroke existed when the pen stopped, so the knot can be trimmed off before fitting.
 *
 * Time is a parameter rather than a clock read, so the whole thing tests on the JVM.
 */
class DwellTracker {

    private var active = false

    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set
    var anchorTimeMs: Long = 0L
        private set

    /** How many points the stroke had when the pen came to rest. */
    var anchorPointIndex: Int = 0
        private set

    /**
     * Set once recognition has run for the current dwell, so a stroke that is not a shape is not
     * re-examined forty times a second while the user thinks about it. Cleared when the pen moves.
     */
    var attempted: Boolean = false

    fun start(x: Float, y: Float, timeMs: Long, pointIndex: Int) {
        active = true
        anchor(x, y, timeMs, pointIndex)
    }

    /** Feeds one sample. Moving beyond [tolerance] restarts the clock from here. */
    fun onSample(x: Float, y: Float, timeMs: Long, pointIndex: Int, tolerance: Float) {
        if (!active) return
        // Chebyshev rather than Euclidean: this runs per input sample at up to 240 Hz, and the
        // difference between a square and a round tolerance region is imperceptible.
        if (abs(x - anchorX) > tolerance || abs(y - anchorY) > tolerance) {
            anchor(x, y, timeMs, pointIndex)
        }
    }

    /** Milliseconds the pen has been within tolerance of the anchor, or zero when not tracking. */
    fun heldFor(nowMs: Long): Long = if (!active) 0L else (nowMs - anchorTimeMs).coerceAtLeast(0L)

    fun reset() {
        active = false
        attempted = false
        anchorPointIndex = 0
    }

    private fun anchor(x: Float, y: Float, timeMs: Long, pointIndex: Int) {
        anchorX = x
        anchorY = y
        anchorTimeMs = timeMs
        anchorPointIndex = pointIndex
        attempted = false
    }
}
