package pl.dakil.notes.editor.canvas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.dakil.notes.ink.DwellTracker
import pl.dakil.notes.ink.HandleDeadband
import pl.dakil.notes.ink.ShapeRecognizer
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.ink.StrokeOutline
import pl.dakil.notes.ink.dragHandle
import pl.dakil.notes.ink.handleInto
import pl.dakil.notes.ink.nearestHandle
import pl.dakil.notes.ink.outlineInto
import pl.dakil.notes.ink.toStroke
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.ShapeSpec
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.StrokeCore
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import kotlin.math.hypot

/**
 * The auto-shape gesture: hold the pen still, and the stroke becomes the shape it was meant to be.
 *
 * ### Why it has to poll
 *
 * Android delivers no `ACTION_MOVE` while a pointer is genuinely stationary, which is precisely the
 * situation this has to detect. Nothing arrives to notice the pause *with*, so a small job ticks
 * while a stroke is in progress and asks. It runs only between pen-down and pen-up, never while the
 * editor merely sits open.
 *
 * ### Why none of this is snapshot state
 *
 * Same reason as the wet stroke it replaces: a shape being dragged updates as fast as the pen
 * reports, and routing that through recomposition would cost far more than redrawing. The overlay
 * bumps its draw-phase counter and reads [outline] straight out of this object.
 */
class ShapeController {

    private val dwell = DwellTracker()

    /** Keeps a snapped line's end where the snap put it until the pen clearly moves off it. */
    private val deadband = HandleDeadband()
    private var job: Job? = null

    private var handle = 0
    private var tool = ToolId.PEN

    /** The style the live shape is drawn and committed with; see [tick] for where it comes from. */
    var previewColor: Int = 0
        private set
    var previewWidth: Float = 1f
        private set
    var previewBlend: BlendId = BlendId.NORMAL
        private set

    /** Whether the tool that drew the stroke fills what it encloses. See `Stroke.filled`. */
    var previewFilled: Boolean = false
        private set

    /** The inside of a two-tone marker shape, or null. See `Stroke.core`. */
    var previewCore: StrokeCore? = null
        private set

    /** The live shape, or null while the user is still drawing an ordinary stroke. */
    var spec: ShapeSpec? = null
        private set

    /** The live shape's centreline, valid while [isLive]. */
    val outline = StrokeOutline()

    val isLive: Boolean get() = spec != null

    /** Called at pen-down, once the router has decided this pointer is drawing. */
    fun begin(x: Float, y: Float, timeMs: Long) {
        spec = null
        deadband.release()
        dwell.start(x, y, timeMs, pointIndex = 1)
    }

    /** Feeds one sample of an ordinary stroke still being drawn. */
    fun onSample(x: Float, y: Float, timeMs: Long, pointCount: Int, tolerance: Float) {
        dwell.onSample(x, y, timeMs, pointCount, tolerance)
    }

    /**
     * Attempts recognition if the pen has been still long enough. True when a shape was found, at
     * which point the stroke under the pen has been replaced and the caller should redraw.
     */
    fun tick(
        nowMs: Long,
        holdMs: Long,
        builder: StrokeBuilder,
        toolSpec: ToolSpec,
        /** How far a still pen may wander, in document points at the current zoom. */
        tolerance: Float,
    ): Boolean {
        if (isLive || dwell.attempted || dwell.heldFor(nowMs) < holdMs) return false
        dwell.attempted = true

        // Only the stroke up to the moment the pen stopped. The samples deposited while holding
        // still are a knot at one point, and fitting to them pulls a corner towards the centre.
        val n = dwell.anchorPointIndex.coerceAtMost(builder.pointCount)
        if (n < 2) return false
        val xs = FloatArray(n) { builder.x(it) }
        val ys = FloatArray(n) { builder.y(it) }
        val found = ShapeRecognizer.recognize(xs, ys, n) ?: return false

        // The width the user was actually drawing at. A pressure-sensitive stylus varies over a
        // stroke and a finger does not, so the shape has to take its weight from the ink it
        // replaces rather than from the tool's nominal setting, or it lands visibly heavier or
        // lighter than everything around it.
        var total = 0f
        for (i in 0 until n) total += builder.widthAt(i)

        spec = found
        handle = found.nearestHandle(dwell.anchorX, dwell.anchorY)
        val reach = openEndReach(found)
        if (reach != null) {
            // Centred on the snapped end, and always wide enough to contain the pen as it rests
            // now: the snap may have swung the end a few points away from it, and a pen that
            // starts outside its own margin would undo the snap on its first tremor.
            val end = FloatArray(2)
            found.handleInto(handle, end)
            val miss = hypot(dwell.anchorX - end[0], dwell.anchorY - end[1])
            deadband.arm(end[0], end[1], length = reach, minRadius = miss + tolerance * 2f)
        } else {
            deadband.release()
        }
        previewWidth = total / n
        tool = toolSpec.tool
        previewColor = toolSpec.effectiveColor
        previewBlend = toolSpec.blend
        previewFilled = toolSpec.fill
        previewCore = toolSpec.core
        found.outlineInto(outline)
        return true
    }

    /**
     * How far apart the two ends of an open shape are, or null for one with no free end to hold —
     * a closed shape's corners are not where a stroke started and stopped.
     */
    private fun openEndReach(spec: ShapeSpec): Float? = when (spec) {
        is ShapeSpec.Line -> hypot(spec.x1 - spec.x0, spec.y1 - spec.y0)
        is ShapeSpec.Arc -> if (handle == 1) null else {
            val a = FloatArray(2).also { spec.handleInto(0, it) }
            val b = FloatArray(2).also { spec.handleInto(2, it) }
            hypot(b[0] - a[0], b[1] - a[1])
        }
        else -> null
    }

    /** Drags the live shape by the apex the pen is resting on. True when something changed. */
    fun drag(x: Float, y: Float): Boolean {
        val current = spec ?: return false
        if (!deadband.accept(x, y)) return false
        // Take the handle back from the drag as well as the shape: pulled past the corner it pins,
        // a shape mirrors, and the apex under the pen becomes a different one. Holding the old
        // index would pin the wrong corner on the very next sample.
        val (next, movedHandle) = current.dragHandle(handle, x, y)
        handle = movedHandle
        if (next == current) return false
        spec = next
        next.outlineInto(outline)
        return true
    }

    /** The stroke to commit at pen-up, or null when no shape was recognised. */
    fun commit(): Stroke? =
        spec?.toStroke(tool, previewColor, previewWidth, previewBlend, outline, previewFilled, previewCore)

    fun reset() {
        spec = null
        deadband.release()
        dwell.reset()
        stopTicking()
    }

    fun startTicking(scope: CoroutineScope, onTick: () -> Unit) {
        stopTicking()
        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                onTick()
            }
        }
    }

    fun stopTicking() {
        job?.cancel()
        job = null
    }

    private companion object {
        /**
         * Fast enough that the snap lands within a frame or two of the hold elapsing, slow enough
         * that the poll itself is invisible next to the input it runs alongside.
         */
        const val TICK_MS = 32L
    }
}
