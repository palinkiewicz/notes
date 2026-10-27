package pl.dakil.notes.format

import pl.dakil.notes.model.PaperTheme
import pl.dakil.notes.model.PaperThemes
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonWriter

/**
 * Saved paper themes as the compact JSON kept in preferences.
 *
 * Each background goes through [Schema.writeBackground] — the very object a note's `sheet.json`
 * holds — so a theme cannot describe paper differently from the way a note does, and a key added
 * to the page format reaches saved themes without a second codec to remember.
 */
object PaperThemeCodec {

    fun encode(themes: List<PaperTheme>): String = JsonWriter.write(
        JsonArray(
            themes.map { theme ->
                JsonObject.EMPTY
                    .with(ID, theme.id)
                    .with(NAME, theme.name)
                    .with(BACKGROUND, Schema.writeBackground(theme.background))
            }
        ),
        pretty = false,
    )

    /**
     * Reads what [encode] wrote. Never throws: an entry without an id or a name is skipped, and text
     * that is not this at all reads as no themes — a corrupt preference must not keep the page
     * setup sheet from opening.
     */
    fun decode(text: String?): List<PaperTheme> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonReader.parse(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.items
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val id = obj.string(ID).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = PaperThemes.cleanName(obj.string(NAME)) ?: return@mapNotNull null
                PaperTheme(id, name, Schema.readBackground(obj.obj(BACKGROUND)))
            }
            .distinctBy { it.id }
            .take(PaperTheme.MAX_THEMES)
    }

    private const val ID = "id"
    private const val NAME = "name"
    private const val BACKGROUND = "background"
}
