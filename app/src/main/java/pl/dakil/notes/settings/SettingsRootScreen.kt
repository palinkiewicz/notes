package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The settings index.
 *
 * Rows and section headers and nothing else. Everything a casual note-taker never has to look at is
 * one level down, which is not the same as hiding it behind an "advanced mode": every subscreen is
 * named on the first screen, and none is a mode you can be stuck in.
 *
 * Grouped by topic rather than by note kind: images and reopening apply to both kinds, and a split
 * by kind would have to put them in one place and leave the other kind to wonder where they went.
 *
 * The only screen in settings with icons. Here they tell the destinations apart at a glance; inside
 * a subscreen every row is a setting of the same kind, and an icon per row would be decoration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    onNavigateToAppearance: () -> Unit,
    onNavigateToEditor: () -> Unit,
    onNavigateToPaperColours: () -> Unit,
    onNavigateToInput: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToTrash: () -> Unit,
) {
    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = flatTopAppBarColors(),
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
            SectionHeader(stringResource(R.string.settings_section_general))

            NavigationRow(
                title = stringResource(R.string.settings_appearance),
                summary = stringResource(R.string.settings_appearance_summary),
                icon = NotesIcons.Palette,
                onClick = onNavigateToAppearance,
            )

            SectionHeader(stringResource(R.string.settings_section_notes))

            NavigationRow(
                title = stringResource(R.string.settings_editor),
                summary = stringResource(R.string.settings_editor_summary),
                icon = NotesIcons.Note,
                onClick = onNavigateToEditor,
            )
            NavigationRow(
                title = stringResource(R.string.settings_paper_colours),
                summary = stringResource(R.string.settings_paper_colours_summary),
                icon = NotesIcons.PageSetup,
                onClick = onNavigateToPaperColours,
            )
            NavigationRow(
                title = stringResource(R.string.settings_input),
                summary = stringResource(R.string.settings_input_summary),
                icon = NotesIcons.Pen,
                onClick = onNavigateToInput,
            )

            SectionHeader(stringResource(R.string.settings_section_data))

            NavigationRow(
                title = stringResource(R.string.settings_storage),
                summary = stringResource(R.string.settings_storage_summary),
                icon = NotesIcons.Folder,
                onClick = onNavigateToStorage,
            )
            NavigationRow(
                title = stringResource(R.string.settings_backup),
                summary = stringResource(R.string.settings_backup_summary),
                icon = NotesIcons.Export,
                onClick = onNavigateToBackup,
            )
            NavigationRow(
                title = stringResource(R.string.settings_sync),
                summary = stringResource(R.string.settings_sync_summary),
                icon = NotesIcons.Layers,
                onClick = onNavigateToSync,
            )
            // Here rather than in the navigation bar: it is where a mistake is undone, not somewhere
            // anyone goes every day, and the snackbar after a delete already offers the quick way back.
            NavigationRow(
                title = stringResource(R.string.settings_trash),
                summary = stringResource(R.string.settings_trash_summary),
                icon = NotesIcons.Trash,
                onClick = onNavigateToTrash,
            )
            // A dialog rather than a screen, and so no chevron: there is nothing here to navigate
            // into, only something to read and close.
            SettingRow(
                title = stringResource(R.string.settings_about),
                summary = stringResource(R.string.settings_about_summary),
                leading = { Icon(NotesIcons.Info, contentDescription = null) },
                onClick = { showAbout = true },
            )
        }
    }
}
