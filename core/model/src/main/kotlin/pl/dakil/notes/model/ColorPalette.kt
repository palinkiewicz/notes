package pl.dakil.notes.model

/**
 * The colour lists the user keeps: the palette they saved, and the recents the app keeps for them.
 *
 * Two lists with opposite contracts. Saved colours are the user's and stay exactly where they were
 * put: a full palette refuses a new colour rather than dropping an old one, because silently losing
 * a mix to make room for another is the whole complaint this exists to answer. Recents are the
 * app's guess and turn over freely — and since a saved colour or a preset is one tap away already,
 * spending a recent slot on either would only push out a mix that is not kept anywhere else.
 *
 * Saved colours compare on the full ARGB, alpha included: a translucent highlighter mix is a
 * different colour from its opaque twin, and saving one must not claim to have saved the other.
 */
object ColorPalette {

    const val MAX_SAVED = 24
    const val MAX_RECENT = 12

    /** Appends [argb] unless it is already saved; unchanged when the palette is full. */
    fun save(saved: List<Int>, argb: Int, cap: Int = MAX_SAVED): List<Int> =
        if (argb in saved || saved.size >= cap) saved else saved + argb

    fun remove(saved: List<Int>, argb: Int): List<Int> = saved.filterNot { it == argb }

    /** Moves the colour at [from] to [to], both clamped; out-of-range [from] changes nothing. */
    fun move(saved: List<Int>, from: Int, to: Int): List<Int> {
        if (from !in saved.indices) return saved
        val target = to.coerceIn(saved.indices)
        if (from == target) return saved
        return saved.toMutableList().apply { add(target, removeAt(from)) }
    }

    /**
     * Puts [argb] at the front of [recents], de-duplicated — unless it is saved or a preset.
     *
     * A preset matches on RGB alone, because tapping a preset keeps the tool's own opacity: the same
     * blue at 40% is still that preset, picked again.
     */
    fun recordRecent(
        recents: List<Int>,
        argb: Int,
        saved: List<Int>,
        presets: IntArray,
        cap: Int = MAX_RECENT,
    ): List<Int> {
        if (argb in saved) return recents
        if (presets.any { (it and 0x00FFFFFF) == (argb and 0x00FFFFFF) }) return recents
        if (recents.firstOrNull() == argb) return recents
        return (listOf(argb) + recents.filterNot { it == argb }).take(cap)
    }

    /** `#AARRGGBB` joined by commas: readable in a preferences dump, and the same on every locale. */
    fun encode(colors: List<Int>): String = colors.joinToString(",") { ColorCodec.toHex(it) }

    /**
     * Reads what [encode] wrote, and the bare decimal integers recents were once stored as.
     *
     * Never throws: an unreadable entry is skipped, so one bad value costs one colour and not the
     * palette.
     */
    fun decode(text: String?, cap: Int): List<Int> =
        text.orEmpty()
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                if (token.startsWith("#")) ColorCodec.parse(token) else token.toIntOrNull()
            }
            .distinct()
            .take(cap)
            .toList()
}
