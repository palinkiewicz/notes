package pl.dakil.notes.ink

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Holds a freshly snapped handle still until the pen has clearly moved away from it.
 *
 * A line snapped level ends a degree or three from where the pen is resting, because that is how
 * far off level the hand was. Following the pen straight away would undo the snap on the very
 * first sample and leave the line crooked by exactly that much. Inside a small radius the pen is
 * taken to be "still on the end"; once it leaves, the handle is the pen's for the rest of the
 * stroke and the radius is never applied again, so a deliberate adjustment back towards the
 * snapped spot is not swallowed.
 */
class HandleDeadband {

    private var x = 0f
    private var y = 0f
    private var radius = 0f
    private var holding = false

    /**
     * Starts holding the handle at ([x], [y]) on a shape [length] long.
     *
     * The radius scales with the length because the miss is an angle: the end of a long line sits
     * further from the pen than the end of a short one for the same few degrees.
     */
    fun arm(x: Float, y: Float, length: Float, minRadius: Float) {
        this.x = x
        this.y = y
        radius = max(minRadius, length * sin(Math.toRadians(MARGIN_DEGREES).toFloat()))
        holding = true
    }

    fun release() {
        holding = false
    }

    /** True when the pen at ([px], [py]) should move the handle. */
    fun accept(px: Float, py: Float): Boolean {
        if (!holding) return true
        if (hypot(px - x, py - y) <= radius) return false
        holding = false
        return true
    }

    private companion object {
        /** A little over how far off a guide a steady hand rests: two to three degrees. */
        const val MARGIN_DEGREES = 3.5
    }
}
