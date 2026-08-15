package pl.dakil.notes.ink

import kotlin.math.abs

/**
 * The One Euro filter (Casiez, Roussel & Vogel, CHI 2012).
 *
 * Handwriting has a problem that a fixed low-pass filter cannot solve: slow, careful movement needs
 * heavy smoothing to hide digitiser jitter, while a fast flick needs almost none or the ink visibly
 * lags behind the pen. One Euro adapts its cutoff to the measured speed, so it smooths when the
 * hand is still and gets out of the way when it moves.
 *
 * One instance per axis; [reset] between strokes.
 */
class OneEuroFilter(
    /** Cutoff at zero velocity, in Hz. Lower = smoother and laggier when moving slowly. */
    var minCutoff: Float = 1.0f,
    /** How aggressively the cutoff rises with speed. Higher = less lag when moving fast. */
    var beta: Float = 0.007f,
    /** Cutoff of the derivative estimator itself. */
    var derivativeCutoff: Float = 1.0f,
) {
    private var hasPrevious = false
    private var previousValue = 0f
    private var previousDerivative = 0f
    private var previousTimeS = 0f

    fun reset() {
        hasPrevious = false
        previousDerivative = 0f
    }

    /**
     * Filters [value] sampled at [timeS] seconds. The first sample of a stroke passes through
     * untouched, so a stroke always starts exactly where the pen touched down.
     */
    fun filter(value: Float, timeS: Float): Float {
        if (!hasPrevious) {
            hasPrevious = true
            previousValue = value
            previousTimeS = timeS
            previousDerivative = 0f
            return value
        }

        val dt = (timeS - previousTimeS).takeIf { it > MIN_DT } ?: MIN_DT
        val rate = 1f / dt

        val rawDerivative = (value - previousValue) * rate
        val derivative = lowPass(rawDerivative, previousDerivative, alpha(rate, derivativeCutoff))
        previousDerivative = derivative

        // The adaptive part: speed raises the cutoff, which reduces smoothing exactly when
        // smoothing would read as lag.
        val cutoff = minCutoff + beta * abs(derivative)
        val filtered = lowPass(value, previousValue, alpha(rate, cutoff))

        previousValue = filtered
        previousTimeS = timeS
        return filtered
    }

    private fun alpha(rate: Float, cutoff: Float): Float {
        val tau = 1f / (TWO_PI * cutoff)
        val dt = 1f / rate
        return 1f / (1f + tau / dt)
    }

    private fun lowPass(value: Float, previous: Float, alpha: Float): Float =
        alpha * value + (1f - alpha) * previous

    companion object {
        private const val TWO_PI = (2.0 * Math.PI).toFloat()
        private const val MIN_DT = 1f / 1000f

        /**
         * Maps a 0..1 smoothing setting onto filter parameters.
         *
         * 0 gives essentially raw input (useful for the pencil, where the hand's grain is the
         * point); 1 gives glassy strokes suited to the highlighter and to shaky hands.
         */
        fun forSmoothing(smoothing: Float): OneEuroFilter {
            val s = smoothing.coerceIn(0f, 1f)
            return OneEuroFilter(
                minCutoff = 12f - 11.4f * s,   // 12 Hz (raw) down to 0.6 Hz (heavy)
                beta = 0.02f - 0.017f * s,     // more speed adaptivity when smoothing is light
                derivativeCutoff = 1.0f,
            )
        }
    }
}
