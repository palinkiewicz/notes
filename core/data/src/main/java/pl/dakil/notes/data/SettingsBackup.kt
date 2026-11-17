package pl.dakil.notes.data

import pl.dakil.notes.sync.BackupGroup
import pl.dakil.notes.sync.PrefValue
import pl.dakil.notes.sync.SettingsSnapshot

/** What restoring settings will do to the stored preferences, worked out before touching them. */
internal data class SettingsImportPlan(
    val remove: Set<String>,
    val put: Map<String, PrefValue>,
    /** The groups being replaced — chosen, and present in the backup. */
    val groups: Set<BackupGroup>,
)

/**
 * Works out how to replace the [chosen] groups of [current] with what [snapshot] holds.
 *
 * Pure, so the rules that keep a backup from damaging a device are tested without one:
 *
 * - A chosen group is *replaced*, not merged: every key of it now stored is removed first. Some
 *   settings are stored as no key at all when they are the default (the render order, the keymap),
 *   and merging would leave this device's choice standing where the backup had the default.
 * - Each incoming key is filed again by this build's [SettingsRepository.backupGroupOf] and kept
 *   only if it lands in the group it arrived in. A backup is a file anyone can edit, and nothing in
 *   one may set where the library is, which folder backups go to, or which server sync talks to.
 * - A value whose type differs from what this device stores under the key is skipped. Reading a
 *   key back with the wrong getter throws, and that would stop the app opening at all.
 */
internal fun planSettingsImport(
    snapshot: SettingsSnapshot,
    chosen: Set<BackupGroup>,
    current: Map<String, Any?>,
): SettingsImportPlan {
    val groups = chosen intersect snapshot.nonEmptyGroups
    val remove = current.keys.filterTo(LinkedHashSet()) { SettingsRepository.backupGroupOf(it) in groups }
    val put = LinkedHashMap<String, PrefValue>()
    for (group in groups) {
        for ((key, value) in snapshot.groups.getValue(group)) {
            if (SettingsRepository.backupGroupOf(key) != group) continue
            val stored = PrefValue.of(current[key])
            if (stored != null && stored.javaClass != value.javaClass) continue
            put[key] = value
        }
    }
    return SettingsImportPlan(remove - put.keys, put, groups)
}
