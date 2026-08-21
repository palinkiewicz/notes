package pl.dakil.notes.ink

import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.ToolType
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Accumulates raw pointer samples into a finished [Stroke].
 *
 * One builder is reused for the whole session: growable primitive arrays are cleared rather than
 * reallocated, so a 240 Hz stylus does not generate garbage on the input thread.
 *
 * The pipeline per sample is: reject → pressure curve → One Euro smoothing → distance decimation →
 * width modulation. Everything is applied at capture time and baked into the result, because a
 * stroke is a record of what the user drew, not a recipe to be re-cooked with later settings.
 */
class StrokeBuilder {

    private var xs = FloatArray(INITIAL_CAPACITY)
    private var ys = FloatArray(INITIAL_CAPACITY)
    private var factors = FloatArray(INITIAL_CAPACITY)
    private var tilts = FloatArray(INITIAL_CAPACITY)
    private var times = IntArray(INITIAL_CAPACITY)

    private var count = 0
    private var startTimeMs = 0L
    private var anyPressure = false
    private var anyTilt = false

    private val filterX = OneEuroFilter()
    private val filterY = OneEuroFilter()

    private var spec: ToolSpec = ToolSpec.PEN
    private var config: InputConfig = InputConfig()

    /**
     * The straightedge this stroke is being drawn against, if any.
     *
     * ### Why the constraint has to live here, past the filter
     *
     * Snapping the incoming samples is not enough. The One Euro filter runs **per axis**, and its
     * cutoff is set from that axis's own speed, so x and y are blended by different amounts on the
     * same sample. Two points that both lie on a sloped line therefore filter to a point that does
     * not — the faster the hand and the sharper the change of direction, the further off it lands,
     * and since the deviation is perpendicular to the line it can put the ink under the ruler.
     *
     * Projecting after filtering makes every stored point exactly collinear by construction, so
     * there is no hand speed at which a ruled line can bend. The smoothing still does its job: it
     * damps the travel *along* the edge, which is the only freedom the pen has left.
     */
    private var guide: RulerEdge? = null

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastTimeMs = 0L
    private var smoothedSpeed = 0f

    /** Live bounds, so the canvas can invalidate only the region the wet stroke has touched. */
    var minX = 0f; private set
    var minY = 0f; private set
    var maxX = 0f; private set
    var maxY = 0f; private set

    val pointCount: Int get() = count
    val isEmpty: Boolean get() = count == 0

    fun x(i: Int): Float = xs[i]
    fun y(i: Int): Float = ys[i]
    fun widthFactor(i: Int): Float = if (anyPressure) factors[i] else 1f

    /** Width in points at point [i] — what the renderer needs to size the outline. */
    fun widthAt(i: Int): Float = spec.width * widthFactor(i)

    val nominalWidth: Float get() = spec.width
    val color: Int get() = spec.effectiveColor
    val blend: BlendId get() = spec.blend
    val toolSpec: ToolSpec get() = spec

    fun bounds(): Rect =
        if (count == 0) Rect.ZERO
        else Rect(minX, minY, maxX, maxY).inflate(spec.width * 0.5f + 1f)

    fun start(spec: ToolSpec, config: InputConfig, sample: PointerSample, guide: RulerEdge? = null) {
        this.spec = spec
        this.config = config
        this.guide = guide
        count = 0
        anyPressure = false
        anyTilt = false
        startTimeMs = sample.timeMs
        lastTimeMs = sample.timeMs
        smoothedSpeed = 0f

        val effectiveSmoothing = (spec.smoothing * config.smoothingScale).coerceIn(0f, 1f)
        val tuned = OneEuroFilter.forSmoothing(effectiveSmoothing)
        filterX.apply { minCutoff = tuned.minCutoff; beta = tuned.beta; reset() }
        filterY.apply { minCutoff = tuned.minCutoff; beta = tuned.beta; reset() }

        lastRawX = sample.x
        lastRawY = sample.y
        minX = sample.x; maxX = sample.x
        minY = sample.y; maxY = sample.y

        append(sample, force = true)
    }

    /**
     * Adds a sample. Returns true if it produced a new point, so the caller knows whether the
     * canvas actually needs to redraw.
     */
    fun add(sample: PointerSample): Boolean = append(sample, force = false)

    /** Called at pointer-up so the stroke ends exactly where the pen left the page. */
    fun finish(sample: PointerSample?): Stroke? {
        if (sample != null) append(sample, force = true)
        if (count < 1) return null

        // A tap with no travel is still a mark: give it two coincident points so the renderer
        // draws a dot rather than nothing.
        if (count == 1) {
            append(
                PointerSample(
                    x = xs[0] + 0.01f, y = ys[0],
                    pressure = 1f, toolType = ToolType.STYLUS, timeMs = lastTimeMs,
                ),
                force = true,
            )
        }

        return Stroke(
            tool = spec.tool,
            color = spec.effectiveColor,
            width = spec.width,
            blend = spec.blend,
            xs = xs.copyOf(count),
            ys = ys.copyOf(count),
            widthFactors = if (anyPressure) factors.copyOf(count) else null,
            tilts = if (anyTilt) tilts.copyOf(count) else null,
            times = times.copyOf(count),
        )
    }

    fun reset() {
        count = 0
        anyPressure = false
        anyTilt = false
        guide = null
    }

    private fun append(sample: PointerSample, force: Boolean): Boolean {
        // Suppress the feathered tail of a lifting pen, which otherwise leaves a hairline trail.
        if (!force && sample.pressure < config.minPressure) return false

        val dtMs = (sample.timeMs - lastTimeMs).coerceAtLeast(0L)
        val timeS = (sample.timeMs - startTimeMs) / 1000f

        val smoothX = filterX.filter(sample.x, timeS)
        val smoothY = filterY.filter(sample.y, timeS)
        val line = guide
        val fx = if (line == null) smoothX else line.projectX(smoothX, smoothY)
        val fy = if (line == null) smoothY else line.projectY(smoothX, smoothY)

        if (!force && count > 0) {
            // Decimation: digitisers report far more points than the geometry needs. Dropping
            // samples closer than a third of a point removes roughly half the data with no visible
            // change, which shows up directly in file size and in tessellation cost.
            val dx = fx - xs[count - 1]
            val dy = fy - ys[count - 1]
            if (dx * dx + dy * dy < MIN_STEP_SQ) return false
        }

        val travel = hypot(sample.x - lastRawX, sample.y - lastRawY)
        val instantSpeed = if (dtMs > 0) travel / dtMs * 1000f else 0f
        // Speed is noisy per-sample; a running average keeps width modulation from flickering.
        smoothedSpeed += (instantSpeed - smoothedSpeed) * SPEED_SMOOTHING

        ensureCapacity(count + 1)
        xs[count] = fx
        ys[count] = fy
        factors[count] = computeWidthFactor(sample)
        tilts[count] = sample.tilt
        times[count] = (sample.timeMs - startTimeMs).toInt()
        count++

        if (fx < minX) minX = fx
        if (fx > maxX) maxX = fx
        if (fy < minY) minY = fy
        if (fy > maxY) maxY = fy

        lastRawX = sample.x
        lastRawY = sample.y
        lastTimeMs = sample.timeMs
        if (sample.tilt != 0f) anyTilt = true
        return true
    }

    /**
     * Combines pressure and speed into a single 0..1 fraction of the tool's nominal width.
     *
     * Speed thinning is what makes the fountain pen read as ink rather than as a uniform ribbon:
     * a fast flick starves the nib. It is off by default for the plain pen, where users expect a
     * consistent line.
     */
    private fun computeWidthFactor(sample: PointerSample): Float {
        val hasPressureInput = sample.toolType == ToolType.STYLUS || sample.toolType == ToolType.ERASER

        var factor = 1f

        if (spec.pressureInfluence > 0f && hasPressureInput) {
            anyPressure = true
            val curved = config.pressureCurve.apply(sample.pressure)
            factor *= 1f - spec.pressureInfluence + spec.pressureInfluence * curved
        }

        if (spec.speedInfluence > 0f) {
            anyPressure = true
            val normalised = (smoothedSpeed / SPEED_REFERENCE).coerceIn(0f, 1f)
            factor *= 1f - spec.speedInfluence * normalised
        }

        if (!anyPressure) return 1f

        // Clamp in factor space, then renormalise against the tool's own maximum so the stored
        // factor genuinely spans (0, 1] and uses the full byte range on disk.
        val clamped = factor.coerceIn(spec.minWidthFactor, spec.maxWidthFactor)
        return (clamped / spec.maxWidthFactor).coerceIn(MIN_FACTOR, 1f)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= xs.size) return
        val size = maxOf(needed, xs.size * 2)
        xs = xs.copyOf(size)
        ys = ys.copyOf(size)
        factors = factors.copyOf(size)
        tilts = tilts.copyOf(size)
        times = times.copyOf(size)
    }

    companion object {
        private const val INITIAL_CAPACITY = 512

        /** Minimum travel between stored points, in points. */
        private const val MIN_STEP = 0.35f
        private const val MIN_STEP_SQ = MIN_STEP * MIN_STEP

        /** Speed at which speed-thinning reaches full effect, in points per second. */
        private const val SPEED_REFERENCE = 1600f
        private const val SPEED_SMOOTHING = 0.2f

        private const val MIN_FACTOR = 0.02f
    }
}

/** Euclidean distance, kept here so the hot paths avoid `kotlin.math.hypot`'s overflow handling. */
internal fun fastDistance(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
