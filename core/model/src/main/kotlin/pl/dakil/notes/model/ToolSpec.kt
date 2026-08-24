package pl.dakil.notes.model

import kotlin.math.roundToInt

/**
 * A configured drawing tool: what the pen does when it touches the page.
 *
 * Casual users only ever see [color] and [width] (a swatch row and a slider). Everything else is
 * exposed in the tool's long-press sheet, so depth is one level down rather than behind a mode.
 */
data class ToolSpec(
    val tool: ToolId,
    /** Packed ARGB. */
    val color: Int = 0xFF1B1B1FL.toInt(),
    /** Nominal width in points. */
    val width: Float = 2f,
    /** 0..1 multiplier applied to the colour's alpha. */
    val opacity: Float = 1f,
    val blend: BlendId = BlendId.NORMAL,
    /** 0 = raw input, 1 = maximum smoothing. Feeds the One Euro filter's cutoff. */
    val smoothing: Float = 0.5f,
    /**
     * How strongly pressure modulates width, as the share of the width it is allowed to take away.
     *
     * 0 = constant width; 1 = a feather-light touch draws [minWidthFactor] of [width] and a firm
     * one draws all of it. It is expressed that way round because [width] is what the user set on
     * the slider, and the width they set is the width they should get when they press.
     */
    val pressureInfluence: Float = 0.9f,
    /** How strongly speed thins the stroke, emulating ink starvation on a fast flick. */
    val speedInfluence: Float = 0.0f,
    /**
     * Width multiplier bounds after all modulation, in (0, 1].
     *
     * The floor stops a stroke vanishing under a light hand; the ceiling is what [width] means, so
     * it is 1 for every tool that wants "the slider is the width at full pressure". Values above 1
     * are not expressible: the format stores the per-point factor as a byte fraction of the
     * stroke's own [Stroke.width], and a stroke that drew wider than its nominal width could not
     * be written back.
     */
    val minWidthFactor: Float = 0.1f,
    val maxWidthFactor: Float = 1f,
    /** Radius in points for the eraser tools. */
    val eraserRadius: Float = 8f,
) {
    val effectiveColor: Int
        get() {
            if (opacity >= 1f) return color
            val a = ((color ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f)
            return (a.roundToInt() shl 24) or (color and 0x00FFFFFF)
        }

    companion object {
        /**
         * A tenth of the set width at a feather touch, all of it when pressed.
         *
         * That is a much wider range than a ballpoint really has, and it is the right default
         * anyway: a digitiser's usable pressure band is narrow and its top end is easy to miss, so
         * a modest range on paper turns into almost no visible variation in the hand.
         */
        val PEN = ToolSpec(
            tool = ToolId.PEN,
            width = 2f,
        )

        /** The widest range of the lot, plus speed thinning, for the calligraphic look. */
        val FOUNTAIN_PEN = ToolSpec(
            tool = ToolId.FOUNTAIN_PEN,
            width = 3.2f,
            smoothing = 0.65f,
            pressureInfluence = 0.94f,
            speedInfluence = 0.45f,
            minWidthFactor = 0.06f,
        )

        /** Low smoothing keeps the grain of the hand; slight translucency reads as graphite. */
        val PENCIL = ToolSpec(
            tool = ToolId.PENCIL,
            width = 1.8f,
            opacity = 0.85f,
            smoothing = 0.25f,
            pressureInfluence = 0.8f,
            minWidthFactor = 0.2f,
        )

        /**
         * MULTIPLY is what stops overlapping highlighter passes from compounding into a dark
         * smear, and constant width is what makes it read as a marker rather than a pen.
         */
        val HIGHLIGHTER = ToolSpec(
            tool = ToolId.HIGHLIGHTER,
            color = 0xFFFFE14D.toInt(),
            width = 16f,
            opacity = 0.4f,
            blend = BlendId.MULTIPLY,
            smoothing = 0.7f,
            pressureInfluence = 0f,
        )

        val ERASER_STROKE = ToolSpec(tool = ToolId.ERASER_STROKE, eraserRadius = 10f)
        val ERASER_POINT = ToolSpec(tool = ToolId.ERASER_POINT, eraserRadius = 8f)
        val LASSO = ToolSpec(tool = ToolId.LASSO)

        val DEFAULTS = listOf(PEN, FOUNTAIN_PEN, PENCIL, HIGHLIGHTER, ERASER_STROKE, ERASER_POINT, LASSO)

        fun defaultFor(tool: ToolId): ToolSpec = DEFAULTS.first { it.tool == tool }
    }
}
