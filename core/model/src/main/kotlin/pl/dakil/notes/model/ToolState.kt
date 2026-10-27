package pl.dakil.notes.model

import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonWriter

/**
 * The tools as the user left them: every tool's settings, and which pen and eraser were in hand.
 *
 * What the editor keeps across restarts, so a pen set to white or a highlighter turned down to a
 * faint wash is still that way tomorrow.
 */
data class ToolState(
    val presets: List<ToolSpec> = ToolSpec.DEFAULTS,
    val lastDrawing: ToolId = ToolId.PEN,
    val lastEraser: ToolId = ToolId.ERASER_STROKE,
) {
    fun encode(): String = JsonWriter.write(
        JsonObject.EMPTY
            .with(LAST_DRAWING, lastDrawing.key)
            .with(LAST_ERASER, lastEraser.key)
            .with(TOOLS, JsonArray(presets.map(::writeSpec))),
        pretty = false,
    )

    companion object {
        /**
         * Reads what [encode] wrote, starting from the defaults and overlaying whatever is valid.
         *
         * Never throws. A missing field keeps its default, an unknown tool is skipped, and text that
         * is not this at all gives the defaults back: losing a remembered colour is a nuisance, but a
         * corrupt preference that stopped the editor opening would be a broken app.
         */
        fun decode(text: String?): ToolState {
            if (text.isNullOrBlank()) return ToolState()
            val root = runCatching { JsonReader.parseObject(text) }.getOrNull() ?: return ToolState()
            val stored = root.array(TOOLS)?.items.orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { obj -> ToolId.fromKey(obj.string(TOOL, ""))?.let { it to obj } }
                .toMap()
            return ToolState(
                presets = ToolSpec.DEFAULTS.map { default ->
                    stored[default.tool]?.let { readSpec(default, it) } ?: default
                },
                lastDrawing = ToolId.fromKey(root.string(LAST_DRAWING, ""))?.takeIf { it.isDrawing } ?: ToolId.PEN,
                lastEraser = ToolId.fromKey(root.string(LAST_ERASER, ""))?.takeIf { it.isEraser }
                    ?: ToolId.ERASER_STROKE,
            )
        }

        // Only what the toolbar lets the user change. Blend and the width bounds are what makes a
        // highlighter a highlighter, so they come from the defaults: a better default shipped later
        // then reaches everyone, rather than only the people who never touched the tool.
        private fun writeSpec(spec: ToolSpec): JsonObject = JsonObject.EMPTY
            .with(TOOL, spec.tool.key)
            .with(COLOR, spec.color)
            .with(WIDTH, spec.width)
            .with(OPACITY, spec.opacity)
            .with(SMOOTHING, spec.smoothing)
            .with(PRESSURE, spec.pressureInfluence)
            .with(SPEED, spec.speedInfluence)
            .with(ERASER_RADIUS, spec.eraserRadius)
            .with(FILL, spec.fill)
            .let { if (spec.tool == ToolId.MARKER) it.with(INSIDE, spec.insideColor).with(RIM, spec.rim) else it }

        private fun readSpec(default: ToolSpec, obj: JsonObject): ToolSpec = default.copy(
            insideColor = obj.int(INSIDE, default.insideColor),
            rim = obj.float(RIM, default.rim).takeIf { it.isFinite() }
                ?.coerceIn(StrokeCore.RIM_MIN, StrokeCore.RIM_MAX) ?: default.rim,
            color = obj.int(COLOR, default.color),
            width = obj.positive(WIDTH, default.width),
            opacity = obj.fraction(OPACITY, default.opacity),
            smoothing = obj.fraction(SMOOTHING, default.smoothing),
            pressureInfluence = obj.fraction(PRESSURE, default.pressureInfluence),
            speedInfluence = obj.fraction(SPEED, default.speedInfluence),
            eraserRadius = obj.positive(ERASER_RADIUS, default.eraserRadius),
            fill = obj.bool(FILL, default.fill),
        )

        private fun JsonObject.fraction(key: String, default: Float): Float =
            float(key, default).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default

        private fun JsonObject.positive(key: String, default: Float): Float =
            float(key, default).takeIf { it.isFinite() && it > 0f } ?: default

        private const val TOOLS = "tools"
        private const val LAST_DRAWING = "lastDrawing"
        private const val LAST_ERASER = "lastEraser"
        private const val TOOL = "tool"
        private const val COLOR = "color"
        private const val WIDTH = "width"
        private const val OPACITY = "opacity"
        private const val SMOOTHING = "smoothing"
        private const val PRESSURE = "pressure"
        private const val SPEED = "speed"
        private const val ERASER_RADIUS = "eraserRadius"
        private const val FILL = "fill"
        private const val INSIDE = "inside"
        private const val RIM = "rim"
    }
}
