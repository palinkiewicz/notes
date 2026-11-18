package pl.dakil.notes.settings

import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.ui.designsystem.CollapsingScaffold
import pl.dakil.notes.ui.designsystem.PageColumn
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.Spacing

/**
 * The settings index.
 *
 * Everything a casual note-taker never has to look at is one level down, which is not the same as
 * hiding it behind an "advanced mode": every subscreen is named on the first screen, and none is a
 * mode you can be stuck in.
 *
 * Grouped by topic rather than by note kind: images and reopening apply to both kinds, and a split
 * by kind would have to put them in one place and leave the other kind to wonder where they went.
 *
 * Each destination gets its own glyph in its own shape, and each group its own colour, so the list
 * can be found by eye. Inside a subscreen every row is a setting of the same kind, and an icon per
 * row would be decoration.
 */
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
    onNavigateToAbout: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    CollapsingScaffold(title = stringResource(R.string.settings_title), large = true) { padding ->
        PageColumn(padding, spacing = Spacing.xs) {
            SettingsGroup(stringResource(R.string.settings_section_general)) {
                NavigationRow(
                    title = stringResource(R.string.settings_appearance),
                    summary = stringResource(R.string.settings_appearance_summary),
                    icon = NotesIcons.Palette,
                    polygon = MaterialShapes.Cookie9Sided,
                    iconContainerColor = colors.primaryContainer,
                    iconContentColor = colors.onPrimaryContainer,
                    onClick = onNavigateToAppearance,
                )
            }

            SettingsGroup(stringResource(R.string.settings_section_notes)) {
                NavigationRow(
                    title = stringResource(R.string.settings_editor),
                    summary = stringResource(R.string.settings_editor_summary),
                    icon = NotesIcons.EditNote,
                    polygon = MaterialShapes.Cookie4Sided,
                    onClick = onNavigateToEditor,
                )
                NavigationRow(
                    title = stringResource(R.string.settings_paper_colours),
                    summary = stringResource(R.string.settings_paper_colours_summary),
                    icon = NotesIcons.PageSetup,
                    polygon = MaterialShapes.Clover4Leaf,
                    onClick = onNavigateToPaperColours,
                )
                NavigationRow(
                    title = stringResource(R.string.settings_input),
                    summary = stringResource(R.string.settings_input_summary),
                    icon = NotesIcons.Pen,
                    polygon = MaterialShapes.Slanted,
                    onClick = onNavigateToInput,
                )
            }

            SettingsGroup(stringResource(R.string.settings_section_data)) {
                NavigationRow(
                    title = stringResource(R.string.settings_storage),
                    summary = stringResource(R.string.settings_storage_summary),
                    icon = NotesIcons.Folder,
                    polygon = MaterialShapes.Cookie6Sided,
                    iconContainerColor = colors.tertiaryContainer,
                    iconContentColor = colors.onTertiaryContainer,
                    onClick = onNavigateToStorage,
                )
                NavigationRow(
                    title = stringResource(R.string.settings_backup),
                    summary = stringResource(R.string.settings_backup_summary),
                    icon = NotesIcons.Backup,
                    polygon = MaterialShapes.Arch,
                    iconContainerColor = colors.tertiaryContainer,
                    iconContentColor = colors.onTertiaryContainer,
                    onClick = onNavigateToBackup,
                )
                NavigationRow(
                    title = stringResource(R.string.settings_sync),
                    summary = stringResource(R.string.settings_sync_summary),
                    icon = NotesIcons.Sync,
                    polygon = MaterialShapes.Sunny,
                    iconContainerColor = colors.tertiaryContainer,
                    iconContentColor = colors.onTertiaryContainer,
                    onClick = onNavigateToSync,
                )
                // Here rather than in the navigation bar: it is where a mistake is undone, not
                // somewhere anyone goes every day, and the snackbar after a delete already offers
                // the quick way back.
                NavigationRow(
                    title = stringResource(R.string.settings_trash),
                    summary = stringResource(R.string.settings_trash_summary),
                    icon = NotesIcons.Trash,
                    polygon = MaterialShapes.Pill,
                    iconContainerColor = colors.tertiaryContainer,
                    iconContentColor = colors.onTertiaryContainer,
                    onClick = onNavigateToTrash,
                )
            }

            SettingsGroup(stringResource(R.string.settings_section_app)) {
                NavigationRow(
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary),
                    icon = NotesIcons.Info,
                    polygon = MaterialShapes.Flower,
                    iconContainerColor = colors.surfaceContainerHighest,
                    iconContentColor = colors.onSurfaceVariant,
                    onClick = onNavigateToAbout,
                )
            }
        }
    }
}
