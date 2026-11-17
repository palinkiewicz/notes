package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsSnapshotTest {

    private val everyType = SettingsSnapshot(
        mapOf(
            BackupGroup.SETTINGS to mapOf(
                "ui.pureBlack" to PrefValue.Bool(true),
                "ui.toolbarPosition" to PrefValue.IntV(2),
                "input.palmWindowMs" to PrefValue.LongV(100L),
                "input.smoothingScale" to PrefValue.FloatV(0.1f),
                "input.curveX1" to PrefValue.FloatV(3f),
                "ui.measurementUnit" to PrefValue.Str("cm"),
                "ui.tags" to PrefValue.StrSet(setOf("b", "a")),
            ),
            BackupGroup.PALETTE to mapOf(
                // White is all ones and reads back as -1: the case a signed/unsigned slip breaks.
                "ui.savedColors" to PrefValue.Str("ffffffff"),
                "ui.single" to PrefValue.IntV(-1),
            ),
            BackupGroup.PAPER to mapOf("paper.themes" to PrefValue.Str("Zażółć — 草稿")),
        ),
    )

    @Test
    fun `every kind of stored value comes back as the same kind with the same value`() {
        assertEquals(everyType, SettingsSnapshot.decode(everyType.encode()))
    }

    @Test
    fun `a whole-number float stays a float rather than coming back as an int`() {
        // JSON writes 3f as `3`; without the tag it would be restored as an int and `getFloat`
        // would throw the next time the app read it.
        val decoded = SettingsSnapshot.decode(everyType.encode())!!
        assertEquals(PrefValue.FloatV(3f), decoded.groups.getValue(BackupGroup.SETTINGS)["input.curveX1"])
    }

    @Test
    fun `the same settings write the same bytes whatever order they were gathered in`() {
        // `SharedPreferences.getAll` is a HashMap: its order is not the order of anything.
        val reversed = SettingsSnapshot(
            everyType.groups.entries.reversed().associate { (group, values) ->
                group to values.entries.reversed().associate { it.key to it.value }
            },
        )
        assertEquals(everyType.encode(), reversed.encode())
    }

    @Test
    fun `floats are written the same on a device that uses a decimal comma`() {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"))
            val text = everyType.encode()
            assertTrue(text, "0.1" in text)
            assertEquals(everyType, SettingsSnapshot.decode(text))
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `something that is not a settings file reads as no settings at all`() {
        assertNull(SettingsSnapshot.decode(null))
        assertNull(SettingsSnapshot.decode(""))
        assertNull(SettingsSnapshot.decode("{not json"))
        assertNull(SettingsSnapshot.decode("[1,2,3]"))
        assertNull(SettingsSnapshot.decode("""{"groups":{}}"""))
    }

    @Test
    fun `a file from a build that needs a newer reader is refused rather than guessed at`() {
        val newer = everyType.encode().replace("\"minReaderVersion\":1", "\"minReaderVersion\":2")
        assertNull(SettingsSnapshot.decode(newer))
    }

    @Test
    fun `a group or a value type this build does not know is skipped and the rest still read`() {
        val text = """{"format":"daknote-settings","minReaderVersion":1,"version":3,"groups":{
            "widgets":{"w.size":{"int":4}},
            "tools":{"tools.state":{"string":"{}"},"tools.new":{"double":1.5},"tools.two":{"int":1,"long":2}}
        }}"""
        val decoded = SettingsSnapshot.decode(text)!!
        assertEquals(setOf(BackupGroup.TOOLS), decoded.groups.keys)
        assertEquals(mapOf("tools.state" to PrefValue.Str("{}")), decoded.groups.getValue(BackupGroup.TOOLS))
    }

    @Test
    fun `only groups with something in them are offered for restoring`() {
        val snapshot = SettingsSnapshot(
            mapOf(BackupGroup.TOOLS to emptyMap(), BackupGroup.PALETTE to mapOf("a" to PrefValue.IntV(1))),
        )
        assertEquals(setOf(BackupGroup.PALETTE), SettingsSnapshot.decode(snapshot.encode())!!.nonEmptyGroups)
    }
}
