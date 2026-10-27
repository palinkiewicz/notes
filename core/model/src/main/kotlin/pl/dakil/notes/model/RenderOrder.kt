package pl.dakil.notes.model

/**
 * The kinds of thing a sheet paints, as the drawing-order setting arranges them.
 *
 * One per drawing tool rather than one for "ink": a highlighter is the case that started this — it
 * belongs under the words it marks, where a pen belongs over them — and once one tool has a place of
 * its own there is no reason to deny the others one.
 */
enum class RenderLayer(
    /** A stable name for settings, never the ordinal or the constant's name — see `LocaleSafeKeysTest`. */
    val key: String,
) {
    IMAGES("images"),
    TEXT("text"),
    HIGHLIGHTER("highlighter"),
    PEN("pen"),
    FOUNTAIN_PEN("fountainPen"),
    PENCIL("pencil"),
    MARKER("marker");

    /**
     * Whether this is strokes, and so can share a level with other strokes.
     *
     * Pictures and text boxes cannot: each is a layer of the editor of its own, with its own input
     * handling, and there is no interleaving a text box between two strokes by when they were drawn.
     */
    val isInk: Boolean get() = this != IMAGES && this != TEXT

    companion object {
        private val VALUES = entries.toTypedArray()

        fun fromKey(key: String): RenderLayer? = VALUES.firstOrNull { it.key == key }

        /**
         * The layer a stroke drawn with [tool] is painted in.
         *
         * Erasers and the lasso make no strokes, and an unknown tool from a newer file already reads
         * as a plain pen (`ToolId.fromOrdinal`), so everything that is not a drawing tool is a pen.
         */
        fun of(tool: ToolId): RenderLayer = when (tool) {
            ToolId.HIGHLIGHTER -> HIGHLIGHTER
            ToolId.FOUNTAIN_PEN -> FOUNTAIN_PEN
            ToolId.PENCIL -> PENCIL
            ToolId.MARKER -> MARKER
            else -> PEN
        }
    }
}

/**
 * What a sheet paints on top of what: a stack of levels, **bottom first**, each holding one or more
 * [RenderLayer]s.
 *
 * Tools that share a level are painted together, stroke by stroke in the order they were drawn —
 * which is how all ink behaved before there was a setting, and is still what most people want for
 * pens: a word written over a crossing-out should sit on top of it whichever pen wrote either.
 * Separate levels are absolute: every stroke of a higher one is over every stroke of a lower one.
 *
 * Always complete — every layer appears exactly once — and [RenderLayer.IMAGES] and
 * [RenderLayer.TEXT] are always alone, so a renderer can ask [levelOf] for anything and never has
 * to handle a mixed level that is not strokes.
 *
 * A preference of this device, not a property of the note: it changes how every sheet is shown,
 * printed and previewed here, and nothing of it is written into a file.
 */
class RenderOrder private constructor(
    /** Bottom first. The members of a level are in display order, bottom first, too. */
    val levels: List<List<RenderLayer>>,
) {
    private val levelByLayer = IntArray(RenderLayer.entries.size).also { index ->
        levels.forEachIndexed { level, members -> for (layer in members) index[layer.ordinal] = level }
    }

    fun levelOf(layer: RenderLayer): Int = levelByLayer[layer.ordinal]

    fun levelOf(tool: ToolId): Int = levelOf(RenderLayer.of(tool))

    val isDefault: Boolean get() = this == DEFAULT

    /**
     * One step towards the top.
     *
     * A step is the smallest change that can be seen, so a tool passes another in two of them: a
     * tool on its own beside a level of ink first **joins** it, and a tool in a shared level first
     * **leaves** it, standing alone just above. That is the whole of grouping — there is no separate
     * "link" control to find, and every press does exactly one visible thing. Pictures and text
     * never join anything; they move past the whole neighbouring level in one step.
     */
    fun raise(layer: RenderLayer): RenderOrder = step(layer, up = true)

    /** One step towards the bottom; the mirror of [raise]. */
    fun lower(layer: RenderLayer): RenderOrder = step(layer, up = false)

    fun canRaise(layer: RenderLayer): Boolean = raise(layer) != this

    fun canLower(layer: RenderLayer): Boolean = lower(layer) != this

    /** `images;text;highlighter;marker,pencil,fountainPen,pen` — levels bottom first, by [RenderLayer.key]. */
    fun encode(): String = levels.joinToString(";") { level -> level.joinToString(",") { it.key } }

    private fun step(layer: RenderLayer, up: Boolean): RenderOrder {
        val at = levelOf(layer)
        val out = levels.map { it.toMutableList() }.toMutableList()
        val level = out[at]

        if (level.size > 1) {
            // Leaving a shared level, onto a level of its own on the side it was moved towards.
            level.remove(layer)
            out.add(if (up) at + 1 else at, mutableListOf(layer))
            return RenderOrder(out)
        }

        val next = if (up) at + 1 else at - 1
        if (next !in out.indices) return this
        val neighbour = out[next]
        if (layer.isInk && neighbour.all { it.isInk }) {
            // Joining, at the end of the level it came in from, so the list does not reshuffle
            // under the finger: a tool raised into the level above appears at that level's foot.
            if (up) neighbour.add(0, layer) else neighbour.add(layer)
            out.removeAt(at)
        } else {
            out[at] = neighbour
            out[next] = level
        }
        return RenderOrder(out)
    }

    override fun equals(other: Any?): Boolean = other is RenderOrder && other.levels == levels

    override fun hashCode(): Int = levels.hashCode()

    override fun toString(): String = "RenderOrder(${encode()})"

    companion object {
        /**
         * Pictures on the paper, then the text, then the highlighter over the paper and under the
         * words it marks — and every other pen together on top, in the order it was drawn in.
         */
        val DEFAULT = RenderOrder(
            listOf(
                listOf(RenderLayer.IMAGES),
                listOf(RenderLayer.TEXT),
                listOf(RenderLayer.HIGHLIGHTER),
                listOf(RenderLayer.MARKER, RenderLayer.PENCIL, RenderLayer.FOUNTAIN_PEN, RenderLayer.PEN),
            ),
        )

        /**
         * Reads back what [encode] wrote. Never fails: unknown keys and repeats are dropped, and a
         * layer the string does not mention — one added since it was saved — is put where the
         * default would put it, not on top of everything the user arranged.
         */
        fun decode(text: String?): RenderOrder {
            if (text.isNullOrBlank()) return DEFAULT
            val seen = HashSet<RenderLayer>()
            val levels = text.split(';').map { level ->
                level.split(',').mapNotNull { RenderLayer.fromKey(it.trim()) }.filter(seen::add)
            }
            return of(levels)
        }

        /** [levels] made into a valid order — see the class's invariants. */
        fun of(levels: List<List<RenderLayer>>): RenderOrder {
            val out = ArrayList<MutableList<RenderLayer>>()
            val placed = HashSet<RenderLayer>()
            for (level in levels) {
                val members = level.filter(placed::add)
                // Pictures and text keep a level each, just under the ink they were listed with.
                for (alone in members.filter { !it.isInk }) out += mutableListOf(alone)
                val ink = members.filter { it.isInk }
                if (ink.isNotEmpty()) out += ink.toMutableList()
            }
            // In the default's own order, so layers missing together go back together as it has them.
            for (missing in DEFAULT.levels.flatten()) {
                if (missing !in placed) insertAtDefault(out, missing)
            }
            return RenderOrder(out)
        }

        private fun insertAtDefault(out: MutableList<MutableList<RenderLayer>>, layer: RenderLayer) {
            val home = DEFAULT.levelOf(layer)
            // Into the level that holds what it is grouped with by default, when that is still ink.
            if (layer.isInk) {
                val mate = DEFAULT.levels[home].firstNotNullOfOrNull { other ->
                    out.firstOrNull { other in it && it.all(RenderLayer::isInk) }
                }
                if (mate != null) {
                    mate += layer
                    return
                }
            }
            // Otherwise just above the highest level holding anything the default puts beneath it.
            val above = out.indexOfLast { level -> level.any { DEFAULT.levelOf(it) < home } }
            out.add(above + 1, mutableListOf(layer))
        }
    }
}
