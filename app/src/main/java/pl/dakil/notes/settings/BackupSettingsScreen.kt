package pl.dakil.notes.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SyncFrequency
import pl.dakil.notes.data.sync.BackupResult
import pl.dakil.notes.data.sync.RestoreResult
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * Scheduled archives, written wherever the user points.
 *
 * The destination being a folder rather than a service is the whole design: "back up to Nextcloud",
 * "to Proton Drive", "to an SD card" are all the same picker, because each of those ships a
 * `DocumentsProvider`. No account, no credential, no network permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        uri?.let(viewModel::restoreFrom)
    }

    OutcomeDialog(
        message = backupMessage(state.lastBackup) ?: restoreMessage(state.lastRestore) ?: state.message,
        title = stringResource(R.string.settings_backup),
        onDismiss = viewModel::acknowledge,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = flatTopAppBarColors(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_backup_destination))

            SettingRow(
                title = stringResource(R.string.settings_backup_destination),
                summary = current.backupDestination?.let(::readableTreeUri)
                    ?: stringResource(R.string.settings_backup_destination_none),
                enabled = !state.busy,
                onClick = { folderPicker.launch(null) },
                leading = { Icon(NotesIcons.Folder, contentDescription = null) },
                trailing = if (state.busy) {
                    { CircularProgressIndicator(Modifier.size(24.dp)) }
                } else {
                    null
                },
            )

            SectionHeader(stringResource(R.string.settings_backup_auto))

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
                format = { count ->
                    if (count.toInt() == 0) stringResource(R.string.settings_backup_keep_all)
                    else stringResource(R.string.settings_backup_keep_value, count.toInt())
                },
                onChange = { viewModel.setBackupKeep(it.toInt()) },
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

            SectionHeader(stringResource(R.string.settings_backup_now))

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
    is RestoreResult.Done ->
        pluralStringResource(R.plurals.settings_restore_done_files, result.restored, result.restored)
    is RestoreResult.Failed -> stringResource(R.string.settings_restore_failed, result.reason)
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
