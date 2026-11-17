package pl.dakil.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.sync.BackupGroup
import pl.dakil.notes.sync.PrefValue
import pl.dakil.notes.sync.SettingsSnapshot

class SettingsBackupTest {

    private fun groupOf(key: String) = SettingsRepository.backupGroupOf(key)

    @Test
    fun `tools, colours and paper themes each go in a group of their own`() {
        assertEquals(BackupGroup.TOOLS, groupOf("tools.state"))
        assertEquals(BackupGroup.TOOLS, groupOf("tools.remember"))
        assertEquals(BackupGroup.PALETTE, groupOf("ui.savedColors"))
        assertEquals(BackupGroup.PALETTE, groupOf("ui.recentColors"))
        assertEquals(BackupGroup.PAPER, groupOf("paper.themes"))
        assertEquals(BackupGroup.PAPER, groupOf("paper.defaultTheme"))
    }

    @Test
    fun `the backup and sync schedule travels but nothing tied to this device does`() {
        assertEquals(BackupGroup.SETTINGS, groupOf("backup.frequency"))
        assertEquals(BackupGroup.SETTINGS, groupOf("backup.keep"))
        assertEquals(BackupGroup.SETTINGS, groupOf("sync.twoWay"))
        // Folder grants, account names, last-run times and the installation's own name: none of
        // them means anything on another device, and the timestamps would make every backup differ.
        for (key in listOf(
            "library.root", "library.deviceId", "backup.destination", "backup.enabled", "backup.lastAt",
            "sync.provider", "sync.webdav.url", "sync.webdav.user", "sync.drive.clientId", "sync.lastAt",
        )) {
            assertNull(key, groupOf(key))
        }
    }

    @Test
    fun `a setting added later is backed up without anyone having to list it`() {
        assertEquals(BackupGroup.SETTINGS, groupOf("ui.colorTheme"))
        assertEquals(BackupGroup.SETTINGS, groupOf("input.keymap"))
        assertEquals(BackupGroup.SETTINGS, groupOf("export.options"))
        assertEquals(BackupGroup.SETTINGS, groupOf("ui.someFutureThing"))
    }

    private val backup = SettingsSnapshot(
        mapOf(
            BackupGroup.SETTINGS to mapOf("ui.pureBlack" to PrefValue.Bool(true)),
            BackupGroup.TOOLS to mapOf("tools.state" to PrefValue.Str("{\"lastDrawing\":\"marker\"}")),
            BackupGroup.PALETTE to mapOf("ui.savedColors" to PrefValue.Str("ff0000ff")),
        ),
    )

    @Test
    fun `a group that was not chosen is left exactly as it is`() {
        val current = mapOf<String, Any?>("ui.pureBlack" to false, "ui.savedColors" to "00ff00ff")
        val plan = planSettingsImport(backup, setOf(BackupGroup.TOOLS), current)

        assertEquals(mapOf("tools.state" to backup.groups.getValue(BackupGroup.TOOLS)["tools.state"]), plan.put)
        assertEquals(emptySet<String>(), plan.remove)
        assertEquals(setOf(BackupGroup.TOOLS), plan.groups)
    }

    @Test
    fun `a chosen group is replaced, so a setting stored here but default in the backup goes`() {
        // The render order is stored as no key at all when it is the default: merging would leave
        // this device's order standing over the backup's default one.
        val current = mapOf<String, Any?>("ui.pureBlack" to false, "view.renderOrder" to "text,pen")
        val plan = planSettingsImport(backup, setOf(BackupGroup.SETTINGS), current)

        assertEquals(setOf("view.renderOrder"), plan.remove)
        assertEquals(mapOf<String, PrefValue>("ui.pureBlack" to PrefValue.Bool(true)), plan.put)
    }

    @Test
    fun `a backup cannot point the library, the backup folder or sync somewhere else`() {
        val tampered = SettingsSnapshot(
            mapOf(
                BackupGroup.SETTINGS to mapOf(
                    "library.root" to PrefValue.Str("content://elsewhere"),
                    "sync.webdav.url" to PrefValue.Str("https://attacker.example"),
                    "ui.pureBlack" to PrefValue.Bool(true),
                ),
                // Filed under the wrong group: choosing only "tools" must not change the theme.
                BackupGroup.TOOLS to mapOf("ui.colorTheme" to PrefValue.Str("red")),
            ),
        )
        val plan = planSettingsImport(tampered, setOf(BackupGroup.SETTINGS, BackupGroup.TOOLS), emptyMap())

        assertEquals(mapOf<String, PrefValue>("ui.pureBlack" to PrefValue.Bool(true)), plan.put)
    }

    @Test
    fun `a value of a different type from the one stored here is not written`() {
        // `getLong` on an int throws, and the settings are read as the app starts.
        val snapshot = SettingsSnapshot(
            mapOf(BackupGroup.SETTINGS to mapOf("input.palmWindowMs" to PrefValue.IntV(80))),
        )
        val plan = planSettingsImport(snapshot, setOf(BackupGroup.SETTINGS), mapOf("input.palmWindowMs" to 100L))

        assertFalse("input.palmWindowMs" in plan.put)
        assertTrue("input.palmWindowMs" in plan.remove)
    }

    @Test
    fun `choosing a group the backup does not have changes nothing`() {
        val plan = planSettingsImport(backup, setOf(BackupGroup.PAPER), mapOf("paper.themes" to "x"))

        assertTrue(plan.groups.isEmpty())
        assertTrue(plan.remove.isEmpty())
        assertTrue(plan.put.isEmpty())
    }
}
