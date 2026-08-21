package pl.dakil.notes.editor.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ink.RulerPose
import pl.dakil.notes.ink.RulerScale
import pl.dakil.notes.ink.rulerLabelStepCm
import pl.dakil.notes.ink.rulerScaleFor
import java.text.DecimalFormatSymbols
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The straightedge lying on the page.
 *
 * Drawn inside the sheet's placement layer, in strip pixels, so it stays on the paper as the note
 * is panned — a ruler that slid about over the window would measure the glass rather than the
 * sheet. What it must *not* inherit from the paper is its size on the glass: the slab, its ticks
 * and its numbers are all divided back out of the zoom, in exactly the way the lasso marquee is,
 * so it stays a usable tool at every magnification.
 *
 * ### Why the scale measures paper
 *
 * A tick every centimetre of *document* means a measurement read here is the measurement that will
 * come off the printer. The zoom's only say is how many divisions are legible, which is what
 * [rulerScaleFor] decides — centimetres across a whole page, millimetres closer in, hundredths of a
 * centimetre once the paper is magnified enough to show them apart.
 *
 * ### Why it takes no pointer input
 *
 * A `Canvas` with no pointer modifier is not a hit-test target, so this can sit on top of the ink
 * overlay without taking a single event off it. Moving the ruler is a two-finger gesture, and two
 * fingers already mean navigation — so it is handled where navigation is, in
 * [sheetTransformGestures], and there is still exactly one input authority.
 */
@Composable
fun RulerOverlay(
    ruler: RulerState,
    transform: SheetTransform,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme

    // The slab is deliberately translucent: it is held over work in progress, and a ruler you
    // cannot see the drawing under is one that has to be moved to be used.
    val bodyColor = scheme.surfaceVariant.copy(alpha = 0.72f)
    val edgeColor = scheme.onSurfaceVariant.copy(alpha = 0.85f)
    val markColor = scheme.onSurfaceVariant

    val cmTickPx = with(density) { CM_TICK.toPx() }
    val mmTickPx = with(density) { MM_TICK.toPx() }
    val fineTickPx = with(density) { FINE_TICK.toPx() }
    val labelSizePx = with(density) { LABEL_SIZE.toPx() }
    val hairlinePx = with(density) { HAIRLINE.toPx() }

    // One Paint for the life of the overlay: text is drawn per tick, per frame, while the ruler is
    // being dragged, and allocating there would put garbage on the input path.
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val decimalSeparator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }

    Canvas(modifier) {
        // Every read here is inside the draw lambda, so panning the page or dragging the ruler
        // invalidates the draw phase alone.
        if (!ruler.isPlaced || ruler.cmPx <= 0f) return@Canvas
        val zoom = transform.zoom.coerceAtLeast(MIN_ZOOM)
        val pose = ruler.pose(zoom)

        val half = pose.length * 0.5f
        val across = pose.thickness

        // The origin of this frame is the middle of the numbered edge, so that edge is at y = 0
        // and the slab hangs off it — which is exactly what keeps it still on the paper when the
        // zoom changes how wide the slab has to be. See RulerPose.
        withTransform({
            translate(pose.edgeX, pose.edgeY)
            rotate(degrees = Math.toDegrees(pose.angleRad.toDouble()).toFloat(), pivot = Offset.Zero)
        }) {
            drawRect(
                color = bodyColor,
                topLeft = Offset(-half, 0f),
                size = Size(pose.length, across),
            )
            // The two drawing edges, drawn heavier than the ends: they are what the pen aims at.
            val edgeWidth = hairlinePx * 1.5f / zoom
            drawLine(edgeColor, Offset(-half, 0f), Offset(half, 0f), edgeWidth)
            drawLine(edgeColor, Offset(-half, across), Offset(half, across), edgeWidth)
            drawLine(edgeColor, Offset(-half, 0f), Offset(-half, across), edgeWidth * 0.6f)
            drawLine(edgeColor, Offset(half, 0f), Offset(half, across), edgeWidth * 0.6f)

            drawScale(
                pose = pose,
                scaleZero = -half + ruler.endMargin(zoom),
                lengthCm = ruler.lengthCm,
                cmPx = ruler.cmPx,
                zoom = zoom,
                visible = transform.visibleAlong(pose),
                color = markColor,
                paint = paint,
                decimalSeparator = decimalSeparator,
                hairlinePx = hairlinePx,
                cmTickPx = cmTickPx,
                mmTickPx = mmTickPx,
                fineTickPx = fineTickPx,
                labelSizePx = labelSizePx,
            )

            if (ruler.dragging) {
                drawHeading(
                    pose = pose,
                    zoom = zoom,
                    lengthCm = ruler.lengthCm,
                    decimalSeparator = decimalSeparator,
                    paint = paint,
                    labelSizePx = labelSizePx,
                    background = scheme.secondaryContainer,
                    color = scheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * The ticks and their numbers, in the ruler's own frame: x runs along the edge from the zero end,
 * y across the slab.
 *
 * Only the ticks the window can actually see are emitted. At the finest division a 20 cm ruler
 * carries two thousand of them, and the reason that division is on at all is that the page is
 * magnified far enough for nearly all of them to be somewhere off screen.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawScale(
    pose: RulerPose,
    /** Where the zero of the scale sits along the slab, inset from its end. */
    scaleZero: Float,
    /** How many centimetres of paper this ruler currently spans. */
    lengthCm: Float,
    cmPx: Float,
    zoom: Float,
    visible: ClosedFloatingPointRange<Float>,
    color: Color,
    paint: Paint,
    decimalSeparator: Char,
    hairlinePx: Float,
    cmTickPx: Float,
    mmTickPx: Float,
    fineTickPx: Float,
    labelSizePx: Float,
) {
    val across = pose.thickness

    val scale = rulerScaleFor(cmPx * zoom)
    val labelStepCm = rulerLabelStepCm(cmPx * zoom)
    val stepPx = scale.stepCm * cmPx
    if (stepPx <= 0f) return
    val stepsPerCm = (1f / scale.stepCm).roundToInt()

    val from = max(visible.start, scaleZero)
    val to = min(visible.endInclusive, scaleZero + lengthCm * cmPx)
    if (from > to) return

    val first = ceil((from - scaleZero) / stepPx).toInt().coerceAtLeast(0)
    // The scale runs out where the ruler does: pinched down to 5 cm it is numbered 0 to 5, and
    // the ticks past that end simply do not exist rather than being drawn off the slab.
    val last = floor((to - scaleZero) / stepPx).toInt()
        .coerceAtMost(floor(lengthCm * stepsPerCm).toInt())
    if (last - first > MAX_TICKS) return

    val tickWidth = hairlinePx / zoom
    paint.color = color.toArgb()
    paint.textSize = labelSizePx / zoom

    for (i in first..last) {
        val x = scaleZero + i * stepPx
        val whole = i % stepsPerCm == 0
        val tenth = !whole && stepsPerCm == 100 && i % 10 == 0
        val length = when {
            whole -> cmTickPx
            tenth -> mmTickPx
            scale == RulerScale.MILLIMETRE -> mmTickPx
            else -> fineTickPx
        } / zoom

        // Both edges are marked, because either can be drawn against, and a measurement taken on
        // the edge the pen is not using is the wrong measurement.
        drawLine(color, Offset(x, 0f), Offset(x, length), tickWidth)
        drawLine(color, Offset(x, across), Offset(x, across - length), tickWidth)

        val centimetres = i / stepsPerCm
        val label = when {
            whole && centimetres % labelStepCm == 0 -> centimetres.toString()
            // Tenths get named once the paper is magnified far enough that a millimetre is a wide
            // gap: at that point the whole ruler on screen may not contain a single whole number.
            tenth -> "${i / 100}$decimalSeparator${(i / 10) % 10}"
            else -> null
        }
        if (label != null) {
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x,
                (cmTickPx + labelSizePx * 1.15f) / zoom,
                paint,
            )
        }
    }
}

/**
 * The heading and the length, shown only while the ruler is in the hand.
 *
 * Both are set by a gesture that can land anywhere. The detents are invisible without the angle —
 * it is how the user knows the edge clicked onto 45° rather than 44.6° — and a ruler being pinched
 * shorter should say what it now measures, since the end of its scale may be off screen.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawHeading(
    pose: RulerPose,
    zoom: Float,
    lengthCm: Float,
    decimalSeparator: Char,
    paint: Paint,
    labelSizePx: Float,
    background: Color,
    color: Color,
) {
    val degrees = Math.toDegrees(pose.angleRad.toDouble()).toFloat()
    val millimetres = (lengthCm * 10f).roundToInt()
    val text = "${degrees.roundToInt()}°   ${millimetres / 10}$decimalSeparator${millimetres % 10} cm"
    val size = labelSizePx * 1.2f / zoom
    paint.textSize = size

    val padding = size * 0.6f
    val width = paint.measureText(text) + padding * 2f
    val height = size + padding
    // Clear of the slab rather than on it: the numbers underneath are the reason the ruler is
    // being turned, and a badge sitting over them hides the very thing being lined up.
    val top = pose.thickness + height * 0.4f
    drawRoundRect(
        color = background.copy(alpha = 0.9f),
        topLeft = Offset(-width * 0.5f, top),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height * 0.5f),
    )
    paint.color = color.toArgb()
    drawContext.canvas.nativeCanvas.drawText(text, 0f, top + height * 0.5f + size * 0.38f, paint)
}

/**
 * How much of the ruler's length the window can see, as a range measured from its middle.
 *
 * The four corners of the visible band are projected onto the ruler's axis; the extremes of those
 * bound everything that could possibly be on screen, whatever angle the ruler is at.
 */
private fun SheetTransform.visibleAlong(pose: RulerPose): ClosedFloatingPointRange<Float> {
    val left = visibleLeft()
    val top = visibleTop()
    val right = visibleRight()
    val bottom = visibleBottom()
    var lowest = Float.MAX_VALUE
    var highest = -Float.MAX_VALUE
    for (x in floatArrayOf(left, right)) {
        for (y in floatArrayOf(top, bottom)) {
            val along = pose.along(x, y)
            if (along < lowest) lowest = along
            if (along > highest) highest = along
        }
    }
    // A margin for the tick that starts just off screen and reaches into it.
    val margin = abs(pose.thickness) + 1f
    return (lowest - margin)..(highest + margin)
}

/** About a centimetre on the glass: wide enough for two rows of ticks and the numbers between. */
val RULER_THICKNESS = 68.dp

/** Blank slab past each end of the scale, so the first and last numbers have somewhere to sit. */
val RULER_END_MARGIN = 14.dp
private val CM_TICK = 15.dp
private val MM_TICK = 9.dp
private val FINE_TICK = 4.5.dp
private val LABEL_SIZE = 11.dp
private val HAIRLINE = 1.dp

private const val MIN_ZOOM = 0.05f

/** A ceiling on one frame's tick count, in case a huge window meets a very fine division. */
private const val MAX_TICKS = 4_000
