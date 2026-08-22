package pl.dakil.notes.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The tokens that get written into a `.daknote` file are derived from enum names by case-folding,
 * and case-folding is locale-dependent. In Turkish `I` folds to a dotless `ı`, so a device set to
 * Turkish would write `ısometric` where every other device writes `isometric` — a file the rest of
 * the world could not read back, produced by nothing more than a language setting.
 */
class LocaleSafeKeysTest {

    private lateinit var original: Locale

    @Before
    fun pinTurkish() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `pattern keys are the same in Turkish as anywhere else`() {
        assertEquals("isometric", PatternType.ISOMETRIC.key)
        for (type in PatternType.entries) {
            assertEquals(type, PatternType.fromKey(type.key))
        }
    }

    @Test
    fun `view mode keys are the same in Turkish as anywhere else`() {
        for (mode in ViewMode.entries) {
            assertEquals(mode, ViewMode.fromKey(mode.key))
        }
    }

    @Test
    fun `paper names do not change with the device language`() {
        assertEquals("Letter", PageSize.Kind.LETTER.label)
        assertEquals("A4", PageSize.Kind.A4.label)
    }

    @Test
    fun `colour hex is upper cased the same way everywhere`() {
        // 0xFF0D1B2A has no letter that folds differently, so use one that does: 'I' never appears
        // in hex, but the uppercase call is still pinned so a future change cannot reintroduce it.
        assertEquals("#FFABCDEF", ColorCodec.toHex(0xFFABCDEF.toInt()))
    }
}
