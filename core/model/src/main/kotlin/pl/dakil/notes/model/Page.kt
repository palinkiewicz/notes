package pl.dakil.notes.model

/**
 * Paper extent, in points (1/72 inch).
 *
 * Always a real, printable size. Endless scrolling is [ViewMode.CONTINUOUS] — a way of looking at
 * the sheet — not a size, so a note written as one long scroll still prints onto A4.
 */
sealed interface PageSize {
    val width: Float
    val height: Float

    data class Fixed(val kind: Kind) : PageSize {
        override val width: Float get() = kind.width
        override val height: Float get() = kind.height
    }

    data class Custom(override val width: Float, override val height: Float) : PageSize

    enum class Kind(val width: Float, val height: Float) {
        A4(595f, 842f),
        A3(842f, 1191f),
        A5(420f, 595f),
        LETTER(612f, 792f),
        LEGAL(612f, 1008f);
    }

    companion object {
        val A4 = Fixed(Kind.A4)
    }
}

/**
 * A repeating background pattern, drawn procedurally in the canvas rather than tiled from a bitmap
 * — no assets, no memory, and it stays crisp at any zoom.
 */
enum class PatternType {
    NONE,
    GRID,
    RULED,
    DOTTED,
    ISOMETRIC,
    STAVES;

    companion object {
        fun fromKey(key: String): PatternType =
            entries.firstOrNull { it.key == key } ?: NONE
    }

    /** Stable lower-case token used in the file, decoupled from the enum name. */
    val key: String get() = name.lowercase()
}

data class PagePattern(
    val type: PatternType = PatternType.NONE,
    /** Line/dot spacing in points. */
    val spacing: Float = 24f,
    /** Packed ARGB of the pattern lines; alpha here is multiplied by [opacity]. */
    val color: Int = 0xFF5B7FD4.toInt(),
    val opacity: Float = 0.35f,
    /** Left margin rule offset in points; 0 disables the margin line. */
    val margin: Float = 0f,
    /** Packed ARGB of the margin rule, kept separate so it can stay red on blue-ruled paper. */
    val marginColor: Int = 0xFFD05A5A.toInt(),
    /** Secondary spacing, used by STAVES for the gap between staff groups. */
    val groupSpacing: Float = 0f,
) {
    companion object {
        val NONE = PagePattern(type = PatternType.NONE)
    }
}

data class PageBackground(
    /** Packed ARGB used in light mode. */
    val color: Int = 0xFFFFFDF8.toInt(),
    /** Packed ARGB used in dark mode. */
    val darkColor: Int = 0xFF12161A.toInt(),
    val pattern: PagePattern = PagePattern.NONE,
    /**
     * When true, rule colours are lightened automatically against dark paper.
     *
     * On by default so a blue-ruled page stays legible in dark mode without the user configuring
     * anything — but it has to be defeatable, because someone who deliberately picks a rule colour
     * for a dark page does not want it silently adjusted.
     */
    val adaptPatternToDark: Boolean = true,
) {
    companion object {
        val DEFAULT = PageBackground()
    }
}
