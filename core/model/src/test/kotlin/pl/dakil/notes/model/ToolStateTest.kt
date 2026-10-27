package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolStateTest {

    private fun ToolState.spec(tool: ToolId) = presets.first { it.tool == tool }

    @Test
    fun `a white pen and a faint highlighter come back exactly as they were left`() {
        // White is all ones, which reads back as -1: the case a signed/unsigned slip would break.
        val state = ToolState(
            presets = ToolSpec.DEFAULTS.map {
                when (it.tool) {
                    ToolId.PEN -> it.copy(color = 0xFFFFFFFF.toInt(), width = 3.5f, fill = true)
                    ToolId.HIGHLIGHTER -> it.copy(opacity = 0.2f, color = 0xFF66CCFF.toInt())
                    ToolId.ERASER_POINT -> it.copy(eraserRadius = 20f)
                    else -> it
                }
            },
            lastDrawing = ToolId.HIGHLIGHTER,
            lastEraser = ToolId.ERASER_POINT,
        )

        assertEquals(state, ToolState.decode(state.encode()))
    }

    @Test
    fun `the marker's inside colour and outline thickness come back as they were left`() {
        val state = ToolState(
            presets = ToolSpec.DEFAULTS.map {
                if (it.tool == ToolId.MARKER) it.copy(insideColor = 0xFFFFFFFF.toInt(), rim = 0.3f) else it
            },
            lastDrawing = ToolId.MARKER,
        )

        assertEquals(state, ToolState.decode(state.encode()))
    }

    @Test
    fun `tools saved before the marker existed still decode, with the marker at its defaults`() {
        // What every existing install has on disk the first time it runs a build with the marker.
        val state = ToolState.decode("""{"lastDrawing":"pen","tools":[{"tool":"pen","color":-1}]}""")
        assertEquals(ToolSpec.MARKER, state.spec(ToolId.MARKER))
    }

    @Test
    fun `an outline thickness out of range is clamped rather than drawing no inside at all`() {
        // A rim of a half or more would leave the inside nothing to be drawn in.
        val state = ToolState.decode("""{"tools":[{"tool":"marker","rim":0.9}]}""")
        assertEquals(StrokeCore.RIM_MAX, state.spec(ToolId.MARKER).rim, 0f)
    }

    @Test
    fun `only the marker draws with an inside`() {
        assertEquals(null, ToolSpec.PEN.core)
        assertEquals(StrokeCore(ToolSpec.MARKER.insideColor, ToolSpec.MARKER.rim), ToolSpec.MARKER.core)
    }

    @Test
    fun `opacity reaches the marker's inside as well as its outline`() {
        // Otherwise a half-faded marker would have a solid middle in a ghostly edge.
        val faded = ToolSpec.MARKER.copy(opacity = 0.5f)
        assertEquals(0x80, faded.core!!.color ushr 24)
        assertEquals(0x80, faded.effectiveColor ushr 24)
    }

    @Test
    fun `saving the same tools twice writes the same text`() {
        val state = ToolState(presets = ToolSpec.DEFAULTS.map { it.copy(opacity = 0.75f) })
        assertEquals(state.encode(), ToolState.decode(state.encode()).encode())
    }

    @Test
    fun `nothing stored, or something unreadable, gives the defaults`() {
        assertEquals(ToolState(), ToolState.decode(null))
        assertEquals(ToolState(), ToolState.decode(""))
        assertEquals(ToolState(), ToolState.decode("{not json"))
        assertEquals(ToolState(), ToolState.decode("[1,2,3]"))
    }

    @Test
    fun `a tool this build has never heard of is skipped rather than failing the rest`() {
        val text = """{"tools":[{"tool":"airbrush","color":5},{"tool":"pen","color":-1}]}"""
        val state = ToolState.decode(text)
        assertEquals(-1, state.spec(ToolId.PEN).color)
        assertEquals(ToolSpec.DEFAULTS.size, state.presets.size)
    }

    @Test
    fun `a field missing from the stored tool keeps its default`() {
        val state = ToolState.decode("""{"tools":[{"tool":"highlighter","opacity":0.1}]}""")
        val highlighter = state.spec(ToolId.HIGHLIGHTER)
        assertEquals(0.1f, highlighter.opacity, 0f)
        assertEquals(ToolSpec.HIGHLIGHTER.color, highlighter.color)
        assertEquals(ToolSpec.HIGHLIGHTER.width, highlighter.width, 0f)
    }

    @Test
    fun `blend and width bounds always come from the defaults`() {
        // Not user settings: they are what makes a highlighter a highlighter, so an improved default
        // has to reach people who once changed its colour.
        val state = ToolState.decode("""{"tools":[{"tool":"highlighter","blend":0}]}""")
        assertEquals(BlendId.MULTIPLY, state.spec(ToolId.HIGHLIGHTER).blend)
    }

    @Test
    fun `values out of range fall back or are clamped instead of drawing nonsense`() {
        val state = ToolState.decode(
            """{"tools":[{"tool":"pen","width":-4,"opacity":7}],"lastDrawing":"eraserStroke"}""",
        )
        assertEquals(ToolSpec.PEN.width, state.spec(ToolId.PEN).width, 0f)
        assertEquals(1f, state.spec(ToolId.PEN).opacity, 0f)
        // An eraser is not a drawing tool, so it cannot be the pen the bar goes back to.
        assertEquals(ToolId.PEN, state.lastDrawing)
    }
}
