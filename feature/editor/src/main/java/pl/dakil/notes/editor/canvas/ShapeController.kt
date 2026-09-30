package pl.dakil.notes.editor.canvas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.dakil.notes.ink.DwellTracker
import pl.dakil.notes.ink.ShapeRecognizer
import pl.dakil.notes.ink.StrokeBuilder
import pl.dakil.notes.ink.StrokeOutline
import pl.dakil.notes.ink.dragHandle
import pl.dakil.notes.ink.nearestHandle
import pl.dakil.notes.ink.outlineInto
import pl.dakil.notes.ink.toStroke
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.ShapeSpec
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec

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

    /** The live shape, or null while the user is still drawing an ordinary stroke. */
    var spec: ShapeSpec? = null
        private set

    /** The live shape's centreline, valid while [isLive]. */
    val outline = StrokeOutline()

    val isLive: Boolean get() = spec != null

    /** Called at pen-down, once the router has decided this pointer is drawing. */
    fun begin(x: Float, y: Float, timeMs: Long) {
        spec = null
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
    fun tick(nowMs: Long, holdMs: Long, builder: StrokeBuilder, toolSpec: ToolSpec): Boolean {
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
        previewWidth = total / n
        tool = toolSpec.tool
        previewColor = toolSpec.effectiveColor
        previewBlend = toolSpec.blend
        previewFilled = toolSpec.fill
        found.outlineInto(outline)
        return true
    }

    /** Drags the live shape by the apex the pen is resting on. True when something changed. */
    fun drag(x: Float, y: Float): Boolean {
        val current = spec ?: return false
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
        spec?.toStroke(tool, previewColor, previewWidth, previewBlend, outline, previewFilled)

    fun reset() {
        spec = null
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
