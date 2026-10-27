package pl.dakil.notes.format

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PaperTheme
import pl.dakil.notes.model.PaperThemes
import pl.dakil.notes.model.PatternType
import pl.dakil.notes.model.json.JsonWriter

class PaperThemeCodecTest {

    private lateinit var original: Locale

    // Turkish folds `I` to a dotless `ı` and Polish writes a decimal comma: the two ways a device
    // setting has corrupted stored tokens before. Running under one of them catches both kinds.
    @Before
    fun useTurkish() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    }

    @After
    fun restoreLocale() = Locale.setDefault(original)

    private fun theme(id: String, type: PatternType) = PaperTheme(
        id = id,
        name = "Theme $id",
        background = PageBackground(
            color = 0xFF12161A.toInt(),
            pattern = PagePattern(
                type = type,
                spacing = 14.5f,
                color = 0x665B7FD4,
                margin = 56.7f,
                marginColor = 0xFFD05A5A.toInt(),
                groupSpacing = 30f,
            ),
        ),
    )

    @Test
    fun `every pattern type survives being saved as a theme and read back`() {
        val themes = PatternType.entries.mapIndexed { i, type -> theme("t$i", type) }
        assertEquals(themes, PaperThemeCodec.decode(PaperThemeCodec.encode(themes)))
    }

    @Test
    fun `a theme stores its paper as exactly the object a note's sheet json does`() {
        // One description of paper, not two: a key added to the page format reaches themes too.
        val bg = theme("a", PatternType.ISOMETRIC).background
        val encoded = PaperThemeCodec.encode(listOf(theme("a", PatternType.ISOMETRIC)))
        assertTrue(encoded.contains(JsonWriter.write(Schema.writeBackground(bg), pretty = false)))
        assertTrue(encoded.contains("\"isometric\""))
    }

    @Test
    fun `nothing stored, or something unreadable, is no themes rather than a crash`() {
        assertEquals(emptyList<PaperTheme>(), PaperThemeCodec.decode(null))
        assertEquals(emptyList<PaperTheme>(), PaperThemeCodec.decode("{not json"))
        assertEquals(emptyList<PaperTheme>(), PaperThemeCodec.decode("""{"id":"a"}"""))
    }

    @Test
    fun `a theme missing its id or name is skipped and the rest survive`() {
        val text = """[{"name":"No id"},{"id":"x","name":"  "},{"id":"ok","name":"Graph"}]"""
        val themes = PaperThemeCodec.decode(text)
        assertEquals(listOf("ok"), themes.map { it.id })
        // No background at all is the default paper, the same as a note without one.
        assertEquals(PageBackground.DEFAULT, themes.single().background)
    }

    @Test
    fun `a page wears the first theme whose paper is exactly its own`() {
        val graph = theme("g", PatternType.GRID)
        val ruled = theme("r", PatternType.RULED)
        assertEquals(ruled, PaperThemes.matching(listOf(graph, ruled), ruled.background))
        val tweaked = ruled.background.copy(color = 0xFFFFFFFF.toInt())
        assertNull(PaperThemes.matching(listOf(graph, ruled), tweaked))
    }

    @Test
    fun `a new id never collides with one already in use`() {
        val now = 1_700_000_000_000L
        val existing = listOf(PaperTheme(now.toString(36), "A", PageBackground.DEFAULT))
        val id = PaperTheme.newId(existing, now)
        assertTrue(id != existing.single().id)
    }

    @Test
    fun `moving a theme past either end leaves it at that end`() {
        val list = listOf(theme("a", PatternType.NONE), theme("b", PatternType.GRID), theme("c", PatternType.DOTTED))
        assertEquals(listOf("b", "c", "a"), PaperThemes.move(list, "a", 5).map { it.id })
        assertEquals(listOf("c", "a", "b"), PaperThemes.move(list, "c", -9).map { it.id })
    }
}
