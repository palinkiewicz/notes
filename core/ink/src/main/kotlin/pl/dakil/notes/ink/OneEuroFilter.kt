package pl.dakil.notes.ink

/**
 * The One Euro filter (Casiez, Roussel & Vogel, CHI 2012), applied to a pen position as a **point**.
 *
 * Handwriting has a problem that a fixed low-pass filter cannot solve: slow, careful movement needs
 * heavy smoothing to hide digitiser jitter, while a fast flick needs almost none or the ink visibly
 * lags behind the pen. One Euro adapts its cutoff to the measured speed, so it smooths when the
 * hand is still and gets out of the way when it moves.
 *
 * ### Why x and y are filtered together
 *
 * The filter is written for a scalar, and the obvious way to use it on a pen is one instance per
 * axis. That is wrong, and wrong in a way that is plainly visible: each axis would set its cutoff
 * from *its own* speed, so the two axes get different amounts of smoothing on the same sample.
 *
 * Draw a circle at a steady pace and every axis in turn passes through a standstill. At the top of
 * the circle x is moving at full speed and y is momentarily stationary, so x is barely touched
 * while y — which is exactly at its turning point, where the real signal bends hardest — is
 * smoothed with the heaviest cutoff the settings allow. A low-pass at an extremum clips it. The top
 * of the circle comes out flat, the sides come out flat for the mirror-image reason, and the
 * corners are where the two regimes cross over: a circle drawn as a rounded square.
 *
 * Deriving one cutoff from the speed *magnitude* makes the smoothing isotropic — it cannot depend
 * on which way the pen happens to be travelling, so it can lag a shape but never distort it. That
 * is also what makes the filter behave the same on a page held at any rotation.
 *
 * One instance per stroke; [reset] between strokes.
 */
class OneEuroFilter(
    /** Cutoff at zero velocity, in Hz. Lower = smoother and laggier when moving slowly. */
    var minCutoff: Float = 1.0f,
    /** How aggressively the cutoff rises with speed. Higher = less lag when moving fast. */
    var beta: Float = 0.007f,
    /** Cutoff of the derivative estimator itself. */
    var derivativeCutoff: Float = 1.0f,
) {
    /** The filtered position, valid after each [filter] call. Also the filter's own history. */
    var x = 0f
        private set
    var y = 0f
        private set

    private var hasPrevious = false
    private var previousDx = 0f
    private var previousDy = 0f
    private var previousTimeS = 0f

    fun reset() {
        hasPrevious = false
        previousDx = 0f
        previousDy = 0f
    }

    /** Sets the cutoffs from a 0..1 smoothing setting, without allocating. */
    fun tune(smoothing: Float) {
        val s = smoothing.coerceIn(0f, 1f)
        minCutoff = MIN_CUTOFF_RAW - MIN_CUTOFF_SPAN * s
        beta = BETA_RAW - BETA_SPAN * s
        derivativeCutoff = 1.0f
    }

    /**
     * Filters the point ([rawX], [rawY]) sampled at [timeS] seconds, leaving the result in [x] and
     * [y]. The first sample of a stroke passes through untouched, so a stroke always starts exactly
     * where the pen touched down.
     */
    fun filter(rawX: Float, rawY: Float, timeS: Float) {
        if (!hasPrevious) {
            hasPrevious = true
            x = rawX
            y = rawY
            previousTimeS = timeS
            previousDx = 0f
            previousDy = 0f
            return
        }

        val dt = (timeS - previousTimeS).takeIf { it > MIN_DT } ?: MIN_DT
        val rate = 1f / dt

        val derivativeAlpha = alpha(rate, derivativeCutoff)
        previousDx = lowPass((rawX - x) * rate, previousDx, derivativeAlpha)
        previousDy = lowPass((rawY - y) * rate, previousDy, derivativeAlpha)

        // The adaptive part: speed raises the cutoff, which reduces smoothing exactly when
        // smoothing would read as lag. One speed for the point, not one per axis — see above.
        val speed = fastDistance(previousDx, previousDy)
        val cutoff = minCutoff + beta * speed
        val positionAlpha = alpha(rate, cutoff)

        x = lowPass(rawX, x, positionAlpha)
        y = lowPass(rawY, y, positionAlpha)
        previousTimeS = timeS
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

        /** 12 Hz (essentially raw) down to 0.6 Hz (heavy). */
        private const val MIN_CUTOFF_RAW = 12f
        private const val MIN_CUTOFF_SPAN = 11.4f

        /** More speed adaptivity when smoothing is light. */
        private const val BETA_RAW = 0.02f
        private const val BETA_SPAN = 0.017f

        /**
         * Maps a 0..1 smoothing setting onto filter parameters.
         *
         * 0 gives essentially raw input (useful for the pencil, where the hand's grain is the
         * point); 1 gives glassy strokes suited to the highlighter and to shaky hands.
         */
        fun forSmoothing(smoothing: Float): OneEuroFilter =
            OneEuroFilter().apply { tune(smoothing) }
    }
}
