package pl.dakil.notes.editor.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import pl.dakil.notes.ink.CM_IN_POINTS
import pl.dakil.notes.ink.RULER_DEFAULT_LENGTH_CM
import pl.dakil.notes.ink.RulerPose
import pl.dakil.notes.ink.clampRulerLength
import pl.dakil.notes.ink.normalizeRulerAngle
import pl.dakil.notes.ink.snapRulerAngle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the ruler is lying, in unzoomed strip pixels — the same space the ink and the paper are
 * drawn in, so it stays on the piece of paper it was put down on rather than floating over the
 * window.
 *
 * The stored point is the middle of the *numbered edge*, not the middle of the slab. That is what
 * makes the edge hold its place on the paper through a zoom; see [RulerPose].
 *
 * Held like [SheetTransform] and for the same reason: two fingers move it at input pace, and the
 * only values they touch are snapshot floats read *inside* a draw lambda. Dragging the ruler
 * therefore costs a redraw per frame and no recomposition at all.
 *
 * ### Why the free angle is kept separately
 *
 * [angleRad] is the heading the ruler is drawn and measured at, after the 15° detents. The
 * un-detented heading has to survive as well, or every frame would re-snap an already-snapped
 * value and the ruler could never be turned off a detent again — it would stick to 45° until the
 * fingers were lifted and put back down.
 */
@Stable
class RulerState {

    private var rawX by mutableFloatStateOf(Float.NaN)
    private var rawY by mutableFloatStateOf(Float.NaN)
    private var rawAngle by mutableFloatStateOf(0f)
    private var rawLengthCm by mutableFloatStateOf(RULER_DEFAULT_LENGTH_CM)

    /** The heading the fingers have actually described, before the detents. A plain field. */
    private var freeAngle = 0f

    /** Strip pixels to one centimetre of paper. Set from the display density each composition. */
    var cmPx: Float = 0f
        private set

    /** Slab thickness on the glass, in pixels: a tool the hand holds, not a mark on the page. */
    private var thicknessOnGlass: Float = 0f

    /** Blank slab beyond each end of the scale, on the glass. See [lengthPx]. */
    private var endMarginOnGlass: Float = 0f

    /** True while two fingers have hold of it, which is when the angle readout is worth showing. */
    var dragging by mutableStateOf(false)

    val isPlaced: Boolean get() = !rawX.isNaN()

    /** Midpoint of the numbered edge — the point everything else is measured from. */
    val edgeX: Float get() = rawX
    val edgeY: Float get() = rawY
    val angleRad: Float get() = rawAngle

    /**
     * How much ruler there is, in centimetres of paper.
     *
     * The scale it carries is fixed to the paper — a centimetre is always a centimetre — so this
     * is the count of them, not their size. Pinching turns a 20 cm rule into a 5 cm one, and the
     * numbers along it simply run out sooner.
     */
    val lengthCm: Float get() = rawLengthCm

    /** The measured part, in strip pixels: real paper centimetres, so it zooms with the sheet. */
    val scaleLengthPx: Float get() = cmPx * rawLengthCm

    fun configure(ptToPx: Float, thicknessOnGlassPx: Float, endMarginOnGlassPx: Float) {
        cmPx = CM_IN_POINTS * ptToPx
        thicknessOnGlass = thicknessOnGlassPx
        endMarginOnGlass = endMarginOnGlassPx
    }

    /** Blank slab past each end of the scale, in strip pixels at [zoom]. */
    fun endMargin(zoom: Float): Float = endMarginOnGlass / zoom.coerceAtLeast(MIN_ZOOM)

    /**
     * The pose at a given placement scale.
     *
     * The slab is the scale plus a blank margin past each end — which is what a printed ruler
     * does, and what keeps its first and last numbers from hanging off the edge. Both the
     * thickness and that margin are fixed on the glass rather than on the paper: the ruler is an
     * object resting on the screen, and one that thinned out as the page was magnified would be
     * unusable at one end of the zoom range or the other. Only the far edge moves when they
     * change, because the pose hangs the slab off the numbered edge.
     */
    fun pose(zoom: Float): RulerPose = RulerPose(
        edgeX = rawX,
        edgeY = rawY,
        angleRad = rawAngle,
        length = scaleLengthPx + endMargin(zoom) * 2f,
        thickness = thicknessOnGlass / zoom.coerceAtLeast(MIN_ZOOM),
    )

    fun placeAt(x: Float, y: Float) {
        rawX = x
        rawY = y
    }

    fun placeIfUnset(x: Float, y: Float) {
        if (!isPlaced) placeAt(x, y)
    }

    /**
     * Brings the ruler back into view, and does nothing if it is already there.
     *
     * Turning the tool off, scrolling three pages, and turning it back on should not require
     * hunting for a straightedge left behind at the top of the note — but a ruler already on
     * screen must stay exactly where it was set down.
     */
    fun ensureVisible(left: Float, top: Float, right: Float, bottom: Float) {
        if (right <= left || bottom <= top) return
        if (isPlaced && rawX in left..right && rawY in top..bottom) return
        placeAt((left + right) * 0.5f, (top + bottom) * 0.5f)
    }

    /**
     * Applies one frame of a two-finger gesture: the pan the fingers made, the angle they turned
     * through about their own centroid, and how far they spread.
     *
     * Rotating about the centroid rather than the ruler's middle is what makes it feel like a
     * physical object — the part of the slab under the fingers stays under them, and the far end
     * swings, exactly as a ruler pinned by two fingertips does.
     *
     * The spread lengthens it, and only that: the width is what the hand rests against and the
     * scale is what the paper is measured in, so neither may be stretched by a gesture. Growth is
     * symmetric about the middle of the edge, which is the one choice that does not walk the ruler
     * along the page as it is being sized.
     */
    fun transformBy(
        dx: Float,
        dy: Float,
        dAngle: Float,
        lengthFactor: Float,
        pivotX: Float,
        pivotY: Float,
    ) {
        if (!isPlaced) return

        if (lengthFactor > 0f && lengthFactor.isFinite()) {
            rawLengthCm = clampRulerLength(rawLengthCm * lengthFactor)
        }

        val before = snapRulerAngle(freeAngle, DETENT_RAD, DETENT_TOLERANCE_RAD)
        freeAngle += dAngle
        val after = snapRulerAngle(freeAngle, DETENT_RAD, DETENT_TOLERANCE_RAD)
        // The anchor turns by what the ruler visibly turned by, detents included, so the slab does
        // not slide out from under the fingers as it clicks onto horizontal.
        val applied = after - before

        val c = cos(applied)
        val s = sin(applied)
        val rx = rawX - pivotX
        val ry = rawY - pivotY
        rawX = pivotX + rx * c - ry * s + dx
        rawY = pivotY + rx * s + ry * c + dy
        rawAngle = normalizeRulerAngle(after)
    }

    companion object {
        /** 15° detents: horizontal, vertical and the diagonals people actually reach for. */
        private val DETENT_RAD = (PI / 12.0).toFloat()

        /** Wide enough to hit with two fingertips, narrow enough to draw a 20° line between. */
        private val DETENT_TOLERANCE_RAD = (PI / 72.0).toFloat()

        private const val MIN_ZOOM = 0.05f

        val Saver: Saver<RulerState, List<Float>> = Saver(
            save = { listOf(it.rawX, it.rawY, it.freeAngle, it.rawLengthCm) },
            restore = { saved ->
                RulerState().apply {
                    rawX = saved[0]
                    rawY = saved[1]
                    freeAngle = saved[2]
                    rawAngle = normalizeRulerAngle(saved[2])
                    rawLengthCm = clampRulerLength(saved[3])
                }
            },
        )
    }
}
