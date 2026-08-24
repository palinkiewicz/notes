package pl.dakil.notes.model

/** How a pointer sample was produced, mirroring `MotionEvent`'s tool types. */
enum class ToolType {
    FINGER,
    STYLUS,
    ERASER,
    MOUSE,
    UNKNOWN,
}

/**
 * One raw input sample, normalised away from `MotionEvent`.
 *
 * Keeping this a plain data class is what lets the whole input-classification decision table live
 * in pure Kotlin and be unit-tested on the JVM — no emulator, no Robolectric, no synthetic
 * `MotionEvent` plumbing for the logic that matters most.
 */
data class PointerSample(
    val x: Float,
    val y: Float,
    /** 0..1 as reported by the digitiser; 1.0 for devices that do not measure pressure. */
    val pressure: Float = 1f,
    /** Radians from the screen normal; 0 when unavailable. */
    val tilt: Float = 0f,
    /** Radians; the direction the stylus is pointing. */
    val orientation: Float = 0f,
    /** Major axis of the contact ellipse in pixels — the primary palm signal. */
    val touchMajor: Float = 0f,
    val toolType: ToolType = ToolType.FINGER,
    val timeMs: Long = 0L,
    val pointerId: Int = 0,
    /** True while a mouse's primary button or a stylus barrel button is down. */
    val primaryButton: Boolean = true,
)

/**
 * The tuning surface for input handling. Defaults are chosen to be invisible to a casual user;
 * every field is exposed in Settings for people who want to tune their specific stylus.
 */
data class InputConfig(
    /**
     * Lets touch draw on devices with no stylus. Off by default: on a stylus tablet, a finger
     * should pan, and turning this on there would make the palm problem much worse.
     *
     * This is the live state of the editor's finger button, not the setting behind it — see
     * `AppSettings.fingerDrawingAvailable`, which decides whether that button is there at all.
     */
    val fingerDrawingEnabled: Boolean = false,
    /**
     * After a stylus sample, touches are ignored for this long. This is the single most effective
     * palm-rejection rule: a hand resting on the glass reports as touch while the pen is working.
     */
    val palmRejectionWindowMs: Long = 100L,
    /**
     * Contact-ellipse major axis above which a touch is treated as a palm even with no stylus
     * activity. In pixels, because that is the unit `MotionEvent.getTouchMajor` reports.
     */
    val palmTouchMajorThreshold: Float = 160f,
    /** Global multiplier on every tool's smoothing, for people who want rawer or glassier ink. */
    val smoothingScale: Float = 1f,
    /** Control points of the pressure response curve; see `PressureCurve`. */
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    /** Ignore stylus samples below this pressure, suppressing the tail of a lifted pen. */
    val minPressure: Float = 0.0f,
    /**
     * Hold the pen still at the end of a stroke to snap it to a square, circle or polygon.
     *
     * On by default: the trigger is a deliberate dwell, so a stroke that simply ends never snaps,
     * and someone who does not know the feature exists will never meet it by accident.
     */
    val autoShapeEnabled: Boolean = true,
    /**
     * How long the pen must be held still before recognition runs.
     *
     * Long enough not to fire during a pause mid-stroke, short enough not to feel like a wait.
     */
    val autoShapeHoldMs: Long = 500L,
)

/**
 * A monotone pressure response curve defined by two interior control points, evaluated as a cubic
 * Bezier with endpoints fixed at (0,0) and (1,1).
 *
 * Two points is the sweet spot: enough to express the three shapes people actually want (soft
 * touch, firm touch, and a dead zone at the top for styluses that saturate early), few enough to
 * drag on a small settings graph without a curve editor.
 */
data class PressureCurve(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
) {
    /** Maps a raw 0..1 pressure to a 0..1 response. */
    fun apply(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        if (this == LINEAR) return p
        // Solve for the Bezier parameter t where x(t) == p, then return y(t). Newton converges in
        // a handful of steps for monotone curves; bisection is the fallback for flat regions.
        var t = p
        repeat(NEWTON_STEPS) {
            val x = bezier(t, x1, x2) - p
            if (kotlin.math.abs(x) < EPSILON) return bezier(t, y1, y2).coerceIn(0f, 1f)
            val dx = bezierDerivative(t, x1, x2)
            if (kotlin.math.abs(dx) < EPSILON) return@repeat
            t = (t - x / dx).coerceIn(0f, 1f)
        }
        var lo = 0f
        var hi = 1f
        repeat(BISECTION_STEPS) {
            t = (lo + hi) * 0.5f
            if (bezier(t, x1, x2) < p) lo = t else hi = t
        }
        return bezier(t, y1, y2).coerceIn(0f, 1f)
    }

    private fun bezier(t: Float, c1: Float, c2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t
    }

    private fun bezierDerivative(t: Float, c1: Float, c2: Float): Float {
        val u = 1f - t
        return 3f * u * u * c1 + 6f * u * t * (c2 - c1) + 3f * t * t * (1f - c2)
    }

    companion object {
        private const val NEWTON_STEPS = 8
        private const val BISECTION_STEPS = 16
        private const val EPSILON = 1e-5f

        val LINEAR = PressureCurve(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)
        /** Reaches full width with less force — for a light hand. */
        val SOFT = PressureCurve(0.15f, 0.45f, 0.35f, 0.9f)
        /** Needs more force before the stroke opens up — for a heavy hand. */
        val FIRM = PressureCurve(0.5f, 0.06f, 0.75f, 0.45f)
    }
}
