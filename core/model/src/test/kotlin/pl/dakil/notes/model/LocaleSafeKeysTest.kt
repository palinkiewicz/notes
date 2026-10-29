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
    fun `tool keys are the same in Turkish as anywhere else`() {
        for (tool in ToolId.entries) {
            assertEquals(tool, ToolId.fromKey(tool.key))
        }
    }

    @Test
    fun `drawing order keys are the same in Turkish as anywhere else`() {
        for (layer in RenderLayer.entries) {
            assertEquals(layer, RenderLayer.fromKey(layer.key))
        }
        assertEquals(RenderOrder.DEFAULT, RenderOrder.decode(RenderOrder.DEFAULT.encode()))
    }

    @Test
    fun `side button keys are the same in Turkish as anywhere else`() {
        for (action in StylusButtonAction.entries) {
            assertEquals(action, StylusButtonAction.fromKey(action.key))
        }
    }

    @Test
    fun `view mode keys are the same in Turkish as anywhere else`() {
        for (mode in ViewMode.entries) {
            assertEquals(mode, ViewMode.fromKey(mode.key))
        }
    }

    @Test
    fun `export choice keys are the same in Turkish as anywhere else`() {
        for (format in ExportFormat.entries) assertEquals(format, ExportFormat.fromKey(format.key))
        for (size in RasterSize.entries) assertEquals(size, RasterSize.fromKey(size.key))
        for (quality in PdfImageQuality.entries) assertEquals(quality, PdfImageQuality.fromKey(quality.key))
        for (quality in JpegQuality.entries) assertEquals(quality, JpegQuality.fromKey(quality.key))
        assertEquals("intelligent", ExportColorPreset.INTELLIGENT.key)
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
