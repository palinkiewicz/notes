package pl.dakil.notes.model

/**
 * A page look the user saved under a name, to put on another sheet in one tap.
 *
 * Only the [PageBackground]: paper colour, rules, spacing and the margin rule. Page size and text
 * margins are about what the sheet is printed on, not what it looks like, and someone who keeps a
 * "graph, dark" theme wants it on A4 and A5 alike.
 *
 * [id] is fixed when the theme is made and survives a rename, so "the default for new notes" keeps
 * pointing at the same theme whatever it is called later.
 */
data class PaperTheme(
    val id: String,
    val name: String,
    val background: PageBackground,
) {
    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_THEMES = 32

        /** An id no theme in [existing] has, in base 36 so it stays short in the stored text. */
        fun newId(existing: Collection<PaperTheme>, now: Long): String {
            val taken = existing.mapTo(HashSet()) { it.id }
            var n = now
            while (n.toString(36) in taken) n++
            return n.toString(36)
        }
    }
}

/** List edits over saved themes, kept pure so the settings screen and the sheet cannot disagree. */
object PaperThemes {

    fun add(themes: List<PaperTheme>, theme: PaperTheme): List<PaperTheme> =
        if (themes.size >= PaperTheme.MAX_THEMES || themes.any { it.id == theme.id }) themes
        else themes + theme

    fun replace(themes: List<PaperTheme>, theme: PaperTheme): List<PaperTheme> =
        themes.map { if (it.id == theme.id) theme else it }

    fun remove(themes: List<PaperTheme>, id: String): List<PaperTheme> = themes.filterNot { it.id == id }

    fun move(themes: List<PaperTheme>, id: String, by: Int): List<PaperTheme> {
        val from = themes.indexOfFirst { it.id == id }
        if (from < 0) return themes
        val to = (from + by).coerceIn(themes.indices)
        if (from == to) return themes
        return themes.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The theme a page is wearing, if any — the first one whose background is exactly its own. */
    fun matching(themes: List<PaperTheme>, background: PageBackground): PaperTheme? =
        themes.firstOrNull { it.background == background }

    /** Trims and bounds a typed name; blank comes back as null so the caller can refuse it. */
    fun cleanName(raw: String): String? =
        raw.trim().take(PaperTheme.MAX_NAME_LENGTH).trim().ifEmpty { null }
}
