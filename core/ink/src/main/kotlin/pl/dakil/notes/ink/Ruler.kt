package pl.dakil.notes.ink

import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PointerSample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One centimetre in typographic points.
 *
 * The ruler measures the *paper*, not the glass: a point is a real 1/72", so a centimetre is a
 * fixed number of document units and a tick lands on the same spot of the sheet whatever the note
 * is magnified to. That is what makes a measurement taken on screen survive printing.
 *
 * Derived from [MeasurementUnit] rather than spelled out again, so the ruler's centimetre and the
 * one the page-setup dialogs measure in can never drift apart. Not `const` for that reason — an
 * enum property is not a compile-time constant — which costs a field read and nothing else.
 */
val CM_IN_POINTS: Float = 1f / MeasurementUnit.CENTIMETRE.perPoint

/** How long the ruler starts out, in centimetres of paper. Slightly under the width of an A4 sheet. */
const val RULER_DEFAULT_LENGTH_CM: Float = 20f

/**
 * How short and how long the ruler may be pinched, in centimetres of paper.
 *
 * The floor is set by the hand rather than by the geometry: two fingers have to fit on the slab to
 * take hold of it again, and a ruler shorter than a few centimetres could only be picked up by
 * accident. The ceiling is well past any page it could be laid on, which is the point — a
 * straightedge longer than the paper is exactly what you want for ruling a full-page diagonal.
 */
const val RULER_MIN_LENGTH_CM: Float = 4f
const val RULER_MAX_LENGTH_CM: Float = 60f

/** Keeps a pinched length inside the usable range. */
fun clampRulerLength(centimetres: Float): Float =
    if (!centimetres.isFinite()) RULER_DEFAULT_LENGTH_CM
    else centimetres.coerceIn(RULER_MIN_LENGTH_CM, RULER_MAX_LENGTH_CM)

/** Which of the ruler's two long edges — either can be drawn against, as on a real straightedge. */
enum class RulerSide { UPPER, LOWER }

/**
 * A straight edge: one point on it and a unit vector along it.
 *
 * Deliberately an infinite line rather than a segment. A pen that runs off the end of a real ruler
 * leaves the edge, but on a tablet the ruler is routinely shorter than the line being drawn, and
 * having to re-place it every 20 cm is worse than the small licence taken here.
 */
data class RulerEdge(val x: Float, val y: Float, val dx: Float, val dy: Float) {

    /** Distance from the edge's anchor to the foot of the perpendicular through ([px], [py]). */
    fun along(px: Float, py: Float): Float = (px - x) * dx + (py - y) * dy

    fun projectX(px: Float, py: Float): Float = x + along(px, py) * dx
    fun projectY(px: Float, py: Float): Float = y + along(px, py) * dy

    /** Perpendicular distance to the line, unsigned. */
    fun distanceTo(px: Float, py: Float): Float = abs((px - x) * -dy + (py - y) * dx)
}

/**
 * Where the ruler is lying: the middle of its numbered edge, its heading, and its size.
 *
 * The pose is a value rather than a stored object because two of its four numbers depend on the
 * zoom — the slab keeps a constant size on the glass while the paper under it does not — so it is
 * rebuilt per frame and per gesture from the state that genuinely persists.
 *
 * ### Why the anchor is an edge and not the middle of the slab
 *
 * Because the slab is a fixed size *on the glass*, its width in paper terms changes with every
 * zoom. Anchored down the middle, that width would grow outwards in both directions and **both
 * drawing edges would slide across the paper** as the user zoomed — so a ruler carefully lined up
 * against a word or a margin would no longer be lined up with it after a pinch, which is the one
 * thing a straightedge may never do.
 *
 * Hanging the slab off one edge pins that edge to the paper exactly. The numbered edge is the one
 * chosen, because it is the one the user aligns by: the numbers are printed against it, and the
 * measurement is read from it.
 *
 * [length] and [thickness] are in the same space as [edgeX]/[edgeY]; the editor works in unzoomed
 * strip pixels, the tests in whatever is convenient.
 */
data class RulerPose(
    /** Midpoint of the numbered ([RulerSide.UPPER]) edge. */
    val edgeX: Float,
    val edgeY: Float,
    val angleRad: Float,
    val length: Float,
    val thickness: Float,
) {
    val dirX: Float get() = cos(angleRad)
    val dirY: Float get() = sin(angleRad)

    /** Perpendicular to the ruler, pointing from the numbered edge across to the far one. */
    val normalX: Float get() = -sin(angleRad)
    val normalY: Float get() = cos(angleRad)

    /** Signed distance along the ruler from the middle of its numbered edge. */
    fun along(x: Float, y: Float): Float = (x - edgeX) * dirX + (y - edgeY) * dirY

    /** Distance across the ruler: 0 on the numbered edge, [thickness] on the far one. */
    fun offset(x: Float, y: Float): Float = (x - edgeX) * normalX + (y - edgeY) * normalY

    fun edge(side: RulerSide): RulerEdge {
        val across = if (side == RulerSide.UPPER) 0f else thickness
        return RulerEdge(
            x = edgeX + normalX * across,
            y = edgeY + normalY * across,
            dx = dirX,
            dy = dirY,
        )
    }

    /** True when a gesture at this point should take hold of the ruler rather than the page. */
    fun grabbedAt(x: Float, y: Float, margin: Float): Boolean =
        offset(x, y) in -margin..(thickness + margin) &&
            abs(along(x, y)) <= length * 0.5f + margin

    /**
     * The edge a stroke starting at ([x], [y]) should stick to, or null to draw free-hand.
     *
     * A point inside the slab snaps to whichever edge is nearer instead of being refused. On paper
     * the pen cannot be under the ruler at all; on glass it can, and the user who put it there was
     * plainly aiming at the straightedge.
     */
    fun snapSide(x: Float, y: Float, band: Float): RulerSide? {
        if (abs(along(x, y)) > length * 0.5f + band) return null
        val across = offset(x, y)
        return when {
            across < 0f -> if (-across <= band) RulerSide.UPPER else null
            across > thickness -> if (across - thickness <= band) RulerSide.LOWER else null
            across <= thickness * 0.5f -> RulerSide.UPPER
            else -> RulerSide.LOWER
        }
    }
}

/**
 * Folds an angle into (-180°, 180°].
 *
 * Only enough to keep the stored heading from growing without bound as the fingers turn round and
 * round; the whole circle is kept.
 *
 * It is tempting to fold the far half away too, on the grounds that a straightedge is symmetric and
 * turning it end for end draws the same line — and that is what this used to do, to keep the
 * printed numbers the right way up. But the slab hangs off its numbered edge, so subtracting 180°
 * flips the body of the ruler across to the other side of that edge: the user turning the ruler
 * past vertical sees it jump, mid-gesture, out from under their fingers. A real ruler turned upside
 * down stays where it is and reads upside down, so this one does the same.
 */
fun normalizeRulerAngle(angleRad: Float): Float {
    if (!angleRad.isFinite()) return 0f
    val half = Math.PI.toFloat()
    val full = (Math.PI * 2.0).toFloat()
    var a = angleRad
    while (a > half) a -= full
    while (a <= -half) a += full
    return a
}

/**
 * Pulls an angle onto the nearest multiple of [stepRad] when it is within [toleranceRad].
 *
 * The detent a set square has and a bare rotation gesture does not: horizontal, vertical and the
 * usual diagonals are what people actually want, and hitting them to a fraction of a degree with
 * two fingers is otherwise luck.
 */
fun snapRulerAngle(angleRad: Float, stepRad: Float, toleranceRad: Float): Float {
    if (stepRad <= 0f || !angleRad.isFinite()) return angleRad
    val nearest = (angleRad / stepRad).roundToInt() * stepRad
    return if (abs(angleRad - nearest) <= toleranceRad) nearest else angleRad
}

/** How finely the ruler is subdivided — the finest division that is still legible on the glass. */
enum class RulerScale(val stepCm: Float) {
    CENTIMETRE(1f),
    MILLIMETRE(0.1f),
    TENTH_MILLIMETRE(0.01f),
}

/**
 * Chooses the subdivision for a magnification, given how many screen pixels a centimetre of paper
 * occupies.
 *
 * Ticks closer together than [MIN_TICK_GAP_PX] stop being ticks and become a grey band, so each
 * level only appears once the paper is magnified enough to hold it. This is why the same ruler
 * reads in centimetres across a whole page and in hundredths of a centimetre when zoomed right in.
 */
fun rulerScaleFor(screenPxPerCm: Float): RulerScale = when {
    screenPxPerCm * RulerScale.TENTH_MILLIMETRE.stepCm >= MIN_TICK_GAP_PX -> RulerScale.TENTH_MILLIMETRE
    screenPxPerCm * RulerScale.MILLIMETRE.stepCm >= MIN_TICK_GAP_PX -> RulerScale.MILLIMETRE
    else -> RulerScale.CENTIMETRE
}

/**
 * How many centimetres apart the numbers are.
 *
 * Zoomed out far enough, consecutive centimetre labels would overlap into an unreadable smear —
 * the ticks still carry the scale there, so the numbers thin out instead of shrinking.
 */
fun rulerLabelStepCm(screenPxPerCm: Float): Int = when {
    screenPxPerCm >= MIN_LABEL_GAP_PX -> 1
    screenPxPerCm * 5f >= MIN_LABEL_GAP_PX -> 5
    else -> 10
}

/** Below this on-screen spacing a division reads as a wash rather than as ticks. */
private const val MIN_TICK_GAP_PX = 5f

/** Room for two digits plus air, so consecutive numbers never touch. */
private const val MIN_LABEL_GAP_PX = 26f

/**
 * The edge one stroke has taken hold of, held for that stroke's lifetime.
 *
 * Engagement is decided once, at pen-down, and never revisited: a real ruler does not let go
 * halfway along a line because the hand drifted, and re-testing per sample would make the ink
 * step on and off the edge wherever the pen wandered past the band.
 */
class RulerGuide {

    var edge: RulerEdge? = null
        private set

    val isEngaged: Boolean get() = edge != null

    fun engage(edge: RulerEdge?) {
        this.edge = edge
    }

    fun release() {
        edge = null
    }

    /** Puts a sample back onto the edge, or returns it untouched when nothing is engaged. */
    fun snap(sample: PointerSample): PointerSample {
        val line = edge ?: return sample
        return sample.copy(
            x = line.projectX(sample.x, sample.y),
            y = line.projectY(sample.x, sample.y),
        )
    }
}
