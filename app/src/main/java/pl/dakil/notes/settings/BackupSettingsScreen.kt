package pl.dakil.notes.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SyncFrequency
import pl.dakil.notes.data.sync.BackupContents
import pl.dakil.notes.data.sync.BackupResult
import pl.dakil.notes.data.sync.RestoreResult
import pl.dakil.notes.sync.BackupGroup
import pl.dakil.notes.ui.designsystem.AppCircularProgress
import kotlin.math.roundToInt

/**
 * Scheduled archives, written wherever the user points.
 *
 * The destination being a folder rather than a service is the whole design: "back up to Nextcloud",
 * "to Proton Drive", "to an SD card" are all the same picker, because each of those ships a
 * `DocumentsProvider`. No account, no credential, no network permission.
 */
@Composable
fun BackupSettingsScreen(
    current: AppSettings,
    viewModel: BackupSyncViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setBackupDestination)
    }
    // `OpenDocument` rather than `GetContent`: restoring wants a specific file the user goes and
    // finds, not whatever a gallery app feels like offering.
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::inspectBackup)
    }

    state.pendingRestore?.let { pending ->
        RestoreChoiceDialog(
            contents = pending.contents,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::cancelRestore,
        )
    }

    OutcomeDialog(
        message = backupMessage(state.lastBackup)
            ?: restoreMessage(state.lastRestore)
            ?: stringResource(R.string.settings_restore_unreadable).takeIf { state.restoreUnreadable }
            ?: state.message,
        title = stringResource(R.string.settings_backup),
        onDismiss = viewModel::acknowledge,
    )

    SettingsSubscreen(stringResource(R.string.settings_backup), onNavigateBack) {
        SettingsGroup {
            SettingRow(
                title = stringResource(R.string.settings_backup_destination),
                summary = current.backupDestination?.let(::readableTreeUri)
                    ?: stringResource(R.string.settings_backup_destination_none),
                enabled = !state.busy,
                onClick = { folderPicker.launch(null) },
                trailing = if (state.busy) {
                    { AppCircularProgress(Modifier.size(24.dp)) }
                } else {
                    null
                },
            )
        }

        SettingsGroup(stringResource(R.string.settings_section_schedule)) {
            SwitchRow(
                title = stringResource(R.string.settings_backup_auto),
                summary = stringResource(R.string.settings_backup_auto_description),
                checked = current.backupEnabled,
                onCheckedChange = viewModel::setBackupEnabled,
                // A schedule with nowhere to write is a switch that silently does nothing.
                enabled = current.backupDestination != null && !state.busy,
            )

            SelectRow(
                title = stringResource(R.string.settings_backup_frequency),
                selected = current.backupFrequency,
                options = SyncFrequency.entries.map { it to it.label() },
                onSelect = viewModel::setBackupFrequency,
                enabled = current.backupEnabled,
            )

            SliderRow(
                title = stringResource(R.string.settings_backup_keep),
                value = current.backupKeep.toFloat(),
                range = 0f..20f,
                // Whole archives, so one stop each; rounded because a stop is a float near the count.
                steps = 19,
                format = { count ->
                    if (count.roundToInt() == 0) stringResource(R.string.settings_backup_keep_all)
                    else stringResource(R.string.settings_backup_keep_value, count.roundToInt())
                },
                onChange = { viewModel.setBackupKeep(it.roundToInt()) },
                enabled = current.backupEnabled,
            )

            SwitchRow(
                title = stringResource(R.string.settings_backup_charging),
                checked = current.backupOnCharging,
                onCheckedChange = viewModel::setBackupOnCharging,
                enabled = current.backupEnabled,
            )

            SwitchRow(
                title = stringResource(R.string.settings_backup_wifi),
                checked = current.backupWifiOnly,
                onCheckedChange = viewModel::setBackupWifiOnly,
                enabled = current.backupEnabled,
            )
        }

        SettingsGroup(stringResource(R.string.settings_section_manual)) {
            SettingRow(
                title = stringResource(R.string.settings_backup_now),
                summary = stringResource(R.string.settings_backup_last, lastRunLabel(current.lastBackupAt)),
                enabled = current.backupDestination != null && !state.busy,
                onClick = viewModel::backUpNow,
            )

            SettingRow(
                title = stringResource(R.string.settings_backup_restore),
                summary = stringResource(R.string.settings_backup_restore_description),
                enabled = !state.busy,
                onClick = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
            )
        }
    }
}

@Composable
private fun backupMessage(result: BackupResult?): String? = when (result) {
    null -> null
    is BackupResult.Done -> if (result.pruned > 0) {
        stringResource(R.string.settings_backup_pruned, result.fileName, result.files, result.pruned)
    } else {
        pluralStringResource(
            R.plurals.settings_backup_done_files, result.files, result.files, result.fileName,
        )
    }
    is BackupResult.Failed -> stringResource(R.string.settings_backup_failed, result.reason)
}

@Composable
private fun restoreMessage(result: RestoreResult?): String? = when (result) {
    null -> null
    is RestoreResult.Done -> {
        val files = pluralStringResource(R.plurals.settings_restore_done_files, result.restored, result.restored)
        val settings = stringResource(R.string.settings_restore_done_settings)
        when {
            result.settingsGroups == 0 -> files
            // "Restored 0 files" after unticking the notes would read as something having failed.
            result.restored == 0 -> settings
            else -> "$files $settings"
        }
    }
    is RestoreResult.Failed -> stringResource(R.string.settings_restore_failed, result.reason)
}

/**
 * What to take from a backup: its notes, and each part of the settings it carries.
 *
 * Everything is ticked to start with, since bringing a phone back to how it was is the common case;
 * the choice is there for the other one — moving the tools and palette to a tablet whose own theme
 * and input settings suit it better. An archive made before backups held settings shows its notes
 * alone, with a line saying why, so nobody goes looking for the settings in it.
 */
@Composable
private fun RestoreChoiceDialog(
    contents: BackupContents,
    onConfirm: (notes: Boolean, groups: Set<BackupGroup>) -> Unit,
    onDismiss: () -> Unit,
) {
    var notes by rememberSaveable(contents) { mutableStateOf(contents.noteFiles > 0) }
    // Saved as keys: the enum's own key is the stable name for it, not its ordinal or `name`.
    var chosenKeys by rememberSaveable(contents) {
        mutableStateOf(contents.groups.map { it.key })
    }
    val chosen = chosenKeys.mapNotNull(BackupGroup::fromKey).toSet()
    val offered = BackupGroup.entries.filter { it in contents.groups }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_restore_choose_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (contents.noteFiles > 0) {
                    ChoiceRow(
                        title = pluralStringResource(
                            R.plurals.settings_restore_choose_notes, contents.noteFiles, contents.noteFiles,
                        ),
                        summary = stringResource(R.string.settings_restore_notes_description),
                        checked = notes,
                        onCheckedChange = { notes = it },
                    )
                }
                for (group in offered) {
                    ChoiceRow(
                        title = group.label(),
                        summary = group.description(),
                        checked = group in chosen,
                        onCheckedChange = { on ->
                            chosenKeys = if (on) chosenKeys + group.key else chosenKeys - group.key
                        },
                    )
                }
                Text(
                    text = stringResource(
                        if (offered.isEmpty()) R.string.settings_restore_notes_only
                        else R.string.settings_restore_replaces,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(notes, chosen) },
                enabled = notes || chosen.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.settings_restore_choose_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** A whole-row checkbox: the label is as much a target as the box, as a list of choices should be. */
@Composable
private fun ChoiceRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A tree URI as the path a file manager would show for it. */
internal fun readableTreeUri(raw: String): String {
    val decoded = Uri.decode(raw)
    val cut = decoded.lastIndexOf(':')
    return if (cut >= 0 && cut < decoded.length - 1) decoded.substring(cut + 1) else decoded
}

@Composable
internal fun lastRunLabel(at: Long): String =
    if (at <= 0L) {
        stringResource(R.string.settings_backup_never)
    } else {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
