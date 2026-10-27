package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.editor.PaperPreview
// The theme menu's strings, shared with the editor rather than translated twice.
import pl.dakil.notes.editor.R as EditorR
import pl.dakil.notes.editor.ThemeNameDialog
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ColorPalette
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PaperTheme
import pl.dakil.notes.ui.color.ColorPickerSheet
import pl.dakil.notes.ui.color.ColorSwatch
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The saved palette, laid out where there is room to manage it.
 *
 * Everything here can also be done from the long-press menus in a picker; this screen is for the
 * people who never long-press, and for tidying a palette without a note open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedColorsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The colour being edited; an edit with no original is "add one".
    var editing by remember { mutableStateOf<ColorEdit?>(null) }
    val saved = current.savedColors
    val removedMessage = stringResource(R.string.settings_colour_removed)
    val undo = stringResource(R.string.settings_undo)

    fun remove(color: Int) {
        val index = saved.indexOf(color)
        settings.unsaveColor(color)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(removedMessage, undo, withDismissAction = true) == SnackbarResult.ActionPerformed) {
                settings.saveColor(color)
                // Saving appends; walk it back to the place it was taken from.
                settings.moveSavedColor(color, index - (settings.read().savedColors.size - 1))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_saved_colours)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = flatTopAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.settings_saved_colours_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                saved.forEachIndexed { index, color ->
                    SavedColorTile(
                        color = color,
                        index = index,
                        count = saved.size,
                        onEdit = { editing = ColorEdit(color) },
                        onMove = { by -> settings.moveSavedColor(color, by) },
                        onRemove = { remove(color) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { editing = ColorEdit(null) },
                    enabled = saved.size < ColorPalette.MAX_SAVED,
                ) {
                    Icon(NotesIcons.Add, contentDescription = null)
                    Text(stringResource(R.string.settings_add_colour), Modifier.padding(start = 8.dp))
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_saved_count, saved.size, saved.size, ColorPalette.MAX_SAVED,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            SectionHeader(stringResource(R.string.settings_recent_colours))
            SettingRow(
                title = stringResource(R.string.settings_clear_recent),
                summary = stringResource(R.string.settings_clear_recent_description),
                enabled = current.recentColors.isNotEmpty(),
                onClick = settings::clearRecentColors,
            )
        }
    }

    editing?.let { edit ->
        val start = edit.original ?: ColorCodec.INK_PRESETS.first()
        ColorPickerSheet(
            title = stringResource(
                if (edit.original == null) R.string.settings_add_colour else R.string.settings_edit_colour
            ),
            initial = start,
            // Nothing to preview live: the palette is what changes, and it changes on commit —
            // Done or a swipe away, as everywhere else the picker is used. Cancel commits nothing.
            onColorChange = {},
            onCommit = {
                if (edit.original == null) settings.saveColor(it)
                else if (it != edit.original) settings.replaceSavedColor(edit.original, it)
            },
            onDismiss = { editing = null },
            presets = ColorCodec.INK_PRESETS,
            recents = current.recentColors,
        )
    }
}

private class ColorEdit(val original: Int?)

@Composable
private fun SavedColorTile(
    color: Int,
    index: Int,
    count: Int,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box {
            ColorSwatch(color = color, selected = false, onClick = { menuOpen = true }, size = 56.dp)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_edit_colour)) },
                    leadingIcon = { Icon(NotesIcons.Rename, contentDescription = null) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_move_earlier)) },
                    leadingIcon = { Icon(NotesIcons.MoveEarlier, contentDescription = null) },
                    enabled = index > 0,
                    onClick = { menuOpen = false; onMove(-1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_move_later)) },
                    leadingIcon = { Icon(NotesIcons.MoveLater, contentDescription = null) },
                    enabled = index < count - 1,
                    onClick = { menuOpen = false; onMove(1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(pl.dakil.notes.ui.R.string.ui_color_remove)) },
                    leadingIcon = { Icon(NotesIcons.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
        Text(
            // With alpha only when there is some: most colours are opaque, and two extra digits on
            // every tile is noise.
            text = ColorCodec.toHex(color, includeAlpha = ColorCodec.alpha(color) != 0xFF),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Saved paper themes: which one new notes start on, their order, and their names.
 *
 * Themes are made from a page, in Page setup, because that is where a paper can be seen while it
 * is set up — so this screen manages them but does not create them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperThemesScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<PaperTheme?>(null) }
    val themes = current.paperThemes
    val plain = stringResource(R.string.settings_plain_paper)
    val undo = stringResource(R.string.settings_undo)
    val deletedFormat = stringResource(R.string.settings_theme_deleted)

    fun delete(theme: PaperTheme) {
        val index = themes.indexOf(theme)
        val wasDefault = current.defaultPaperThemeId == theme.id
        settings.removePaperTheme(theme.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                deletedFormat.format(theme.name), undo, withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) settings.restorePaperTheme(theme, index, wasDefault)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_paper_themes)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = flatTopAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SelectRow(
                title = stringResource(R.string.settings_new_note_paper),
                summary = stringResource(R.string.settings_new_note_paper_description),
                selected = themes.firstOrNull { it.id == current.defaultPaperThemeId }?.id,
                options = listOf<Pair<String?, String>>(null to plain) + themes.map { it.id to it.name },
                onSelect = settings::setDefaultPaperTheme,
            )

            SectionHeader(stringResource(R.string.settings_paper_themes))

            if (themes.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_paper_themes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            themes.forEachIndexed { index, theme ->
                ThemeRow(
                    theme = theme,
                    isDefault = theme.id == current.defaultPaperThemeId,
                    canMoveUp = index > 0,
                    canMoveDown = index < themes.lastIndex,
                    onMove = { by -> settings.movePaperTheme(theme.id, by) },
                    onRename = { renaming = theme },
                    onToggleDefault = {
                        settings.setDefaultPaperTheme(if (theme.id == current.defaultPaperThemeId) null else theme.id)
                    },
                    onDelete = { delete(theme) },
                )
            }
        }
    }

    renaming?.let { theme ->
        ThemeNameDialog(
            title = stringResource(EditorR.string.editor_theme_rename),
            initial = theme.name,
            onConfirm = {
                settings.renamePaperTheme(theme.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun ThemeRow(
    theme: PaperTheme,
    isDefault: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onToggleDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    SettingRow(
        title = theme.name,
        summary = if (isDefault) stringResource(EditorR.string.editor_theme_is_default) else null,
        onClick = { menuOpen = true },
        leading = {
            PaperPreview(
                background = theme.background,
                // The size only decides how a preview is cropped, and A4 is what most paper is.
                pageSize = PageSize.A4,
                modifier = Modifier
                    .size(width = 40.dp, height = 54.dp)
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(NotesIcons.MoveUp, contentDescription = stringResource(R.string.settings_move_earlier))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(NotesIcons.MoveDown, contentDescription = stringResource(R.string.settings_move_later))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(NotesIcons.More, contentDescription = stringResource(EditorR.string.editor_theme_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isDefault) EditorR.string.editor_theme_unset_default
                                        else EditorR.string.editor_theme_set_default
                                    )
                                )
                            },
                            leadingIcon = { Icon(NotesIcons.Default, contentDescription = null) },
                            onClick = { menuOpen = false; onToggleDefault() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(EditorR.string.editor_theme_rename)) },
                            leadingIcon = { Icon(NotesIcons.Rename, contentDescription = null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(EditorR.string.editor_theme_delete)) },
                            leadingIcon = { Icon(NotesIcons.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        },
    )
}
