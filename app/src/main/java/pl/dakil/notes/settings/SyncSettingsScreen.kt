package pl.dakil.notes.settings

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
import pl.dakil.notes.data.SyncProvider
import pl.dakil.notes.sync.SyncOutcome
import pl.dakil.notes.sync.SyncRefusal
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * Mirroring the library to a server the user chose.
 *
 * Both backends put **real files in real folders** on the far end, not an opaque archive: the point
 * of syncing to Drive is that the notes are still `.md` and `.daknote` files anyone can open from a
 * browser. An account is optional and the app works completely without one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    current: AppSettings,
    viewModel: BackupSyncViewModel,
    onNavigateToDrive: () -> Unit,
    onNavigateToWebDav: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val guard = state.lastSync?.refusals?.filterIsInstance<SyncRefusal.DeleteGuardTripped>()?.firstOrNull()

    if (guard != null) {
        // Not merely reported: the deletions are held until the user says so, because the shape
        // that trips this is also the shape a bug or a lapsed permission produces.
        DeleteGuardDialog(
            wouldDelete = guard.wouldDelete,
            recorded = guard.recorded,
            onConfirm = {
                viewModel.acknowledge()
                viewModel.syncNow(confirmDeletes = true)
            },
            onDismiss = viewModel::acknowledge,
        )
    } else {
        OutcomeDialog(
            message = syncMessage(state.lastSync) ?: state.message,
            title = stringResource(R.string.settings_sync),
            onDismiss = viewModel::acknowledge,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_sync)) },
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {

            SectionHeader(stringResource(R.string.settings_sync_provider))

            when (current.syncProvider) {
                SyncProvider.NONE -> {
                    SettingRow(
                        title = stringResource(R.string.settings_sync_connect_drive),
                        summary = stringResource(R.string.settings_sync_connect_drive_description),
                        enabled = !state.busy,
                        onClick = onNavigateToDrive,
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_sync_connect_webdav),
                        summary = stringResource(R.string.settings_sync_connect_webdav_description),
                        enabled = !state.busy,
                        onClick = onNavigateToWebDav,
                    )
                }

                SyncProvider.DRIVE, SyncProvider.WEBDAV -> {
                    SettingRow(
                        title = when (current.syncProvider) {
                            SyncProvider.DRIVE -> stringResource(R.string.settings_drive_title)
                            else -> stringResource(R.string.settings_webdav_title)
                        },
                        summary = when (current.syncProvider) {
                            SyncProvider.DRIVE -> current.driveFolder
                            else -> current.webDavUrl
                        },
                        enabled = !state.busy,
                        onClick = if (current.syncProvider == SyncProvider.DRIVE) onNavigateToDrive else onNavigateToWebDav,
                        trailing = if (state.busy) {
                            { CircularProgressIndicator(Modifier.size(24.dp)) }
                        } else {
                            null
                        },
                    )

                    SettingRow(
                        title = stringResource(R.string.settings_sync_now),
                        summary = stringResource(R.string.settings_sync_last, lastRunLabel(current.lastSyncAt)),
                        enabled = !state.busy,
                        onClick = { viewModel.syncNow() },
                    )

                    SelectRow(
                        title = stringResource(R.string.settings_sync_frequency),
                        selected = current.syncFrequency,
                        options = SyncFrequency.entries.map { it to it.label() },
                        onSelect = viewModel::setSyncFrequency,
                        enabled = !state.busy,
                    )

                    SwitchRow(
                        title = stringResource(R.string.settings_sync_wifi),
                        checked = current.syncWifiOnly,
                        onCheckedChange = viewModel::setSyncWifiOnly,
                        enabled = !state.busy,
                    )

                    SwitchRow(
                        title = stringResource(R.string.settings_sync_two_way),
                        summary = stringResource(R.string.settings_sync_two_way_description),
                        checked = current.syncTwoWay,
                        onCheckedChange = viewModel::setSyncTwoWay,
                        enabled = !state.busy,
                    )

                    SettingRow(
                        title = stringResource(R.string.settings_sync_disconnect),
                        summary = stringResource(R.string.settings_sync_disconnect_description),
                        enabled = !state.busy,
                        onClick = viewModel::disconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun syncMessage(outcome: SyncOutcome?): String? {
    if (outcome == null) return null
    outcome.failure?.let { return stringResource(R.string.settings_sync_failed, it) }
    return buildString {
        append(stringResource(R.string.settings_sync_result, outcome.uploaded, outcome.downloaded, outcome.deleted))
        if (outcome.conflicts.isNotEmpty()) {
            append("\n\n")
            append(
                pluralStringResource(
                    R.plurals.settings_sync_conflicts_notes,
                    outcome.conflicts.size,
                    outcome.conflicts.size,
                )
            )
        }
        if (outcome.refusals.any { it is SyncRefusal.ListingIncomplete }) {
            append("\n\n")
            append(stringResource(R.string.settings_sync_refused_incomplete))
        }
    }
}

@Composable
private fun DeleteGuardDialog(
    wouldDelete: Int,
    recorded: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sync)) },
        text = { Text(stringResource(R.string.settings_sync_refused_guard, wouldDelete, recorded)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_sync_confirm_deletes))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}
