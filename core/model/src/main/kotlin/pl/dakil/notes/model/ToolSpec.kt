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
    /** How strongly pressure modulates width. 0 = constant width. */
    val pressureInfluence: Float = 0.6f,
    /** How strongly speed thins the stroke, emulating ink starvation on a fast flick. */
    val speedInfluence: Float = 0.0f,
    /** Width multiplier bounds after all modulation, so a stroke never vanishes or blows up. */
    val minWidthFactor: Float = 0.25f,
    val maxWidthFactor: Float = 1.6f,
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
        val PEN = ToolSpec(
            tool = ToolId.PEN,
            width = 2f,
            pressureInfluence = 0.5f,
        )

        /** Wide dynamic range and speed thinning give the calligraphic look. */
        val FOUNTAIN_PEN = ToolSpec(
            tool = ToolId.FOUNTAIN_PEN,
            width = 3.2f,
            smoothing = 0.65f,
            pressureInfluence = 0.9f,
            speedInfluence = 0.45f,
            minWidthFactor = 0.15f,
            maxWidthFactor = 2.0f,
        )

        /** Low smoothing keeps the grain of the hand; slight translucency reads as graphite. */
        val PENCIL = ToolSpec(
            tool = ToolId.PENCIL,
            width = 1.8f,
            opacity = 0.85f,
            smoothing = 0.25f,
            pressureInfluence = 0.75f,
            maxWidthFactor = 1.25f,
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
