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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.R
import pl.dakil.notes.data.AdoptOutcome
import pl.dakil.notes.data.LibraryRootKind

/**
 * Where the notes live, and how to move them somewhere the user can reach.
 *
 * The one screen in settings whose subject is the files themselves rather than how they look. It
 * exists so the answer to "where are my notes" can be a folder the user picked — which is also the
 * whole of the backup story for anyone already running Syncthing, a cloud client with a
 * `DocumentsProvider`, or nothing but a USB cable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    viewModel: StorageSettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::adopt)
    }

    state.outcome?.let { outcome ->
        AdoptOutcomeDialog(outcome = outcome, onDismiss = viewModel::acknowledge)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_storage)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = pl.dakil.notes.ui.icons.NotesIcons.Back,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors = pl.dakil.notes.ui.components.flatTopAppBarColors(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_storage_location))

            SettingRow(
                title = stringResource(R.string.settings_storage_folder),
                // The URI is shown decoded rather than prettified: a wrong folder is much easier to
                // recognise from the path it really is than from a name the app invented for it.
                summary = state.root?.let(::readableTree)
                    ?: stringResource(R.string.settings_storage_internal_summary),
                enabled = !state.busy,
                onClick = { picker.launch(null) },
                leading = { Icon(pl.dakil.notes.ui.icons.NotesIcons.Folder, contentDescription = null) },
                trailing = if (state.busy) {
                    { CircularProgressIndicator(Modifier.size(24.dp)) }
                } else {
                    null
                },
            )

            SettingRow(
                title = stringResource(R.string.settings_storage_choose),
                summary = stringResource(R.string.settings_storage_choose_description),
                enabled = !state.busy,
                onClick = { picker.launch(null) },
            )

            if (state.kind == LibraryRootKind.EXTERNAL) {
                SettingRow(
                    title = stringResource(R.string.settings_storage_revert),
                    summary = stringResource(R.string.settings_storage_revert_description),
                    enabled = !state.busy,
                    onClick = viewModel::revert,
                )
            }
        }
    }
}

@Composable
private fun AdoptOutcomeDialog(outcome: AdoptOutcome, onDismiss: () -> Unit) {
    val message = when (outcome) {
        // `pluralStringResource`, not a bare count: Polish has three plural forms, and
        // "Skopiowano 1 plików" is the sort of thing that reads as a machine wrote it.
        is AdoptOutcome.Joined ->
            pluralStringResource(R.plurals.settings_storage_joined_notes, outcome.noteCount, outcome.noteCount)
        is AdoptOutcome.Migrated ->
            pluralStringResource(R.plurals.settings_storage_migrated_files, outcome.copied, outcome.copied)
        is AdoptOutcome.Failed -> stringResource(R.string.settings_storage_failed, outcome.reason)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_storage)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/** Turns a tree URI into the path a file manager would show for it. */
private fun readableTree(raw: String): String {
    val decoded = Uri.decode(raw)
    // `content://…/tree/primary:Notes/Work` — everything after the volume is what the user knows.
    val cut = decoded.lastIndexOf(':')
    return if (cut >= 0 && cut < decoded.length - 1) decoded.substring(cut + 1) else decoded
}
