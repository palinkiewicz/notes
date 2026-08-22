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

    /** Which family a [Kind] belongs to, so the picker can keep the series apart. */
    enum class SizeGroup { A, B, US }

    /**
     * The sizes on offer, ISO 216 A and B plus the two North American ones.
     *
     * Declaration order is the order they are offered in: by family, smallest first. Nothing
     * serializes the ordinal — the file and the preferences both store [name] — so this list can be
     * reordered or extended without touching a single saved note.
     */
    enum class Kind(val width: Float, val height: Float, val group: SizeGroup) {
        A5(420f, 595f, SizeGroup.A),
        A4(595f, 842f, SizeGroup.A),
        A3(842f, 1191f, SizeGroup.A),
        A2(1191f, 1684f, SizeGroup.A),
        A1(1684f, 2384f, SizeGroup.A),
        B5(499f, 709f, SizeGroup.B),
        B4(709f, 1001f, SizeGroup.B),
        B3(1001f, 1417f, SizeGroup.B),
        B2(1417f, 2004f, SizeGroup.B),
        B1(2004f, 2835f, SizeGroup.B),
        LETTER(612f, 792f, SizeGroup.US),
        LEGAL(612f, 1008f, SizeGroup.US);

        /** "A4" stays shouted, "LETTER" does not. */
        val label: String
            get() = if (group == SizeGroup.US) name.lowercase().replaceFirstChar { it.uppercase() }
            else name
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
    /**
     * Packed ARGB of the pattern lines.
     *
     * Its alpha is the only thing that makes a rule faint. There was a separate strength
     * multiplier; two controls over one visual result only ever disagree, and the colour is the
     * one of the two a user can see while they set it.
     */
    val color: Int = 0xFF5B7FD4.toInt(),
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

/**
 * The paper itself.
 *
 * One colour, not one per theme. Paper colour is document content: it is chosen to go with the ink
 * on it, it should print as what you see, and it should be the same colour on someone else's device
 * as on yours. A sheet that changes colour with a system setting is a sheet whose appearance the
 * author does not actually control — and the rule colours that were picked against it then have to
 * be second-guessed too. Anyone who wants dark paper picks a dark colour.
 */
data class PageBackground(
    /** Packed ARGB of the paper. */
    val color: Int = 0xFFFFFDF8.toInt(),
    val pattern: PagePattern = PagePattern.NONE,
) {
    companion object {
        val DEFAULT = PageBackground()
    }
}
