package pl.dakil.notes.ink

import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.ToolType

/** What a pointer should be allowed to do. */
sealed interface InputIntent {
    /** Lay down ink (or erase, or lasso — whatever the active tool is). */
    data object Draw : InputIntent

    /** Pan and zoom the canvas. */
    data object Navigate : InputIntent

    /** Discard entirely: a palm, or a stray touch while the stylus is working. */
    data object Ignore : InputIntent
}

/**
 * The outcome of routing one pointer-down.
 *
 * [revoked] carries the pointer ids whose in-flight strokes this event supersedes. That matters
 * because the decision to draw is made at pointer-down, before the app can know a second finger —
 * or a stylus — is coming: when it does, the accidental first mark has to be taken back, not
 * merely stopped.
 */
data class InputDecision(
    val intent: InputIntent,
    val revoked: List<Int> = emptyList(),
)

/**
 * Decides what each pointer is for.
 *
 * This is deliberately pure Kotlin over [PointerSample] rather than `MotionEvent`, so the entire
 * decision table — the part of the app most likely to feel broken on a real device — is testable
 * on the JVM without an emulator.
 *
 * The router is stateful across a gesture: an intent is chosen at pointer-down and held for that
 * pointer's whole lifetime, so a stroke can never turn into a pan halfway through.
 */
class InputRouter(config: InputConfig = InputConfig()) {

    private class Live(val intent: InputIntent, val toolType: ToolType)

    /**
     * Changing configuration does not re-decide pointers that are already down: a setting change
     * must never reinterpret the mark currently being drawn.
     */
    var config: InputConfig = config

    private val active = LinkedHashMap<Int, Live>()

    /**
     * When the stylus was last seen, or [NEVER].
     *
     * The sentinel must be tested explicitly rather than subtracted from: `timeMs - Long.MIN_VALUE`
     * overflows to a negative number, which would make every touch look like it arrived inside the
     * palm-rejection window and silently kill all input on a device with no stylus at all.
     */
    private var lastStylusTimeMs = NEVER

    val activePointerCount: Int get() = active.size
    val isDrawing: Boolean get() = active.values.any { it.intent is InputIntent.Draw }
    val isNavigating: Boolean get() = active.values.any { it.intent is InputIntent.Navigate }

    fun intentFor(pointerId: Int): InputIntent? = active[pointerId]?.intent

    fun begin(sample: PointerSample): InputDecision {
        val stylus = sample.toolType == ToolType.STYLUS || sample.toolType == ToolType.ERASER
        if (stylus) lastStylusTimeMs = sample.timeMs

        if (stylus) {
            // A stylus outranks everything. Any finger already drawing was a palm the time-window
            // rule could not catch, because it touched down before the pen did.
            val revoked = revokeWhere { it.intent is InputIntent.Draw && it.toolType.isTouch() }
            active[sample.pointerId] = Live(InputIntent.Draw, sample.toolType)
            return InputDecision(InputIntent.Draw, revoked)
        }

        if (sample.toolType == ToolType.MOUSE) {
            val intent = if (sample.primaryButton) InputIntent.Draw else InputIntent.Navigate
            active[sample.pointerId] = Live(intent, sample.toolType)
            return InputDecision(intent)
        }

        // --- Touch -----------------------------------------------------------------------------

        // The single most effective palm rule: while the pen is in use, the hand resting on the
        // glass reports as an ordinary touch, and always lands near in time to a stylus sample.
        if (lastStylusTimeMs != NEVER && sample.timeMs - lastStylusTimeMs < config.palmRejectionWindowMs) {
            active[sample.pointerId] = Live(InputIntent.Ignore, sample.toolType)
            return InputDecision(InputIntent.Ignore)
        }

        // Size rule, for the case where the palm lands before the pen does.
        if (config.palmTouchMajorThreshold > 0f && sample.touchMajor > config.palmTouchMajorThreshold) {
            active[sample.pointerId] = Live(InputIntent.Ignore, sample.toolType)
            return InputDecision(InputIntent.Ignore)
        }

        // A second finger always means pan/zoom, even in finger-drawing mode — otherwise a
        // stylus-less phone would have no way left to navigate the page.
        if (config.multiTouchNavigates && active.any { it.value.toolType.isTouch() }) {
            val revoked = revokeWhere { it.intent is InputIntent.Draw && it.toolType.isTouch() }
            active[sample.pointerId] = Live(InputIntent.Navigate, sample.toolType)
            return InputDecision(InputIntent.Navigate, revoked)
        }

        val intent = if (config.fingerDrawingEnabled) InputIntent.Draw else InputIntent.Navigate
        active[sample.pointerId] = Live(intent, sample.toolType)
        return InputDecision(intent)
    }

    /**
     * Returns the intent this pointer was assigned at [begin]. Samples for a pointer that is not
     * live are ignored rather than guessed at, which is what happens after a revocation.
     */
    fun update(sample: PointerSample): InputIntent {
        if (sample.toolType == ToolType.STYLUS || sample.toolType == ToolType.ERASER) {
            lastStylusTimeMs = sample.timeMs
        }
        return active[sample.pointerId]?.intent ?: InputIntent.Ignore
    }

    fun end(sample: PointerSample): InputIntent {
        if (sample.toolType == ToolType.STYLUS || sample.toolType == ToolType.ERASER) {
            // Refresh the window on lift too, so the palm still resting on the glass after the pen
            // leaves the page does not immediately start panning.
            lastStylusTimeMs = sample.timeMs
        }
        return active.remove(sample.pointerId)?.intent ?: InputIntent.Ignore
    }

    fun end(pointerId: Int): InputIntent = active.remove(pointerId)?.intent ?: InputIntent.Ignore

    fun cancel() = active.clear()

    /** Diagnostic hook; the stylus window is otherwise driven purely by sample times. */
    fun lastStylusTimeMs(): Long = lastStylusTimeMs

    private fun ToolType.isTouch(): Boolean = this == ToolType.FINGER || this == ToolType.UNKNOWN

    /**
     * Demotes matching pointers to [InputIntent.Ignore] and returns their ids.
     *
     * They stay in [active] rather than being dropped: the finger is still physically on the
     * glass, so forgetting it would leave [activePointerCount] lying and let the next event treat
     * a two-finger gesture as a one-finger one. They are removed on the pointer-up that actually
     * happens.
     */
    private inline fun revokeWhere(predicate: (Live) -> Boolean): List<Int> {
        if (active.isEmpty()) return emptyList()
        var out: ArrayList<Int>? = null
        for (entry in active.entries) {
            if (predicate(entry.value)) {
                (out ?: ArrayList<Int>(2).also { out = it }).add(entry.key)
                entry.setValue(Live(InputIntent.Ignore, entry.value.toolType))
            }
        }
        return out ?: emptyList()
    }

    companion object {
        /** Sentinel meaning "no stylus sample has ever been seen". */
        const val NEVER = Long.MIN_VALUE
    }
}
