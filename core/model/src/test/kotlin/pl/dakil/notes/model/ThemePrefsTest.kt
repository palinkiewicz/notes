package pl.dakil.notes.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ThemePrefsTest {

    private lateinit var original: Locale

    /**
     * Turkish is the locale that breaks case-folding: it lower-cases `I` to a dotless `ı`. Every
     * test here runs under it, because a key that survives English and not Turkish is a key that
     * silently resets someone's theme when they change their phone's language.
     */
    @Before
    fun pinLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `every colour theme survives a round trip through its key`() {
        for (theme in AppColorTheme.entries) {
            assertEquals(theme, AppColorTheme.fromKey(theme.key))
        }
    }

    @Test
    fun `every dark theme option survives a round trip through its key`() {
        for (option in DarkThemeOption.entries) {
            assertEquals(option, DarkThemeOption.fromKey(option.key))
        }
    }

    /**
     * A saved choice this build does not recognise has to open something rather than crash — that is
     * what lets a key be renamed, or a theme dropped, without stranding the people who picked it.
     */
    @Test
    fun `an unknown key falls back to the default rather than failing`() {
        assertEquals(AppColorTheme.DAKILS_NOTES, AppColorTheme.fromKey("mauve"))
        assertEquals(DarkThemeOption.SYSTEM, DarkThemeOption.fromKey(""))
    }

    /**
     * The keys are what land in `settings`, so they must not collide and must not drift into the
     * enum's own name — renaming a constant should stay free.
     */
    @Test
    fun `keys are unique and lower case`() {
        val keys = AppColorTheme.entries.map { it.key } + DarkThemeOption.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (key in keys) {
            assertEquals(key, key.lowercase(Locale.ROOT))
        }
    }
}
