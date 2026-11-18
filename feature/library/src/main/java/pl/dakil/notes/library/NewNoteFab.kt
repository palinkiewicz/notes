package pl.dakil.notes.library

import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.animateFloatingActionButton
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The "new" button: Material's expressive FAB menu, one item per thing that can be made here.
 *
 * The button morphs from a rounded square with a `+` into a circle with an ×, and the items unfold
 * above it nearest-the-thumb first. Ink comes first because it is what the app is for; a folder
 * last because it holds notes rather than being one.
 *
 * The caller owns [open] because the scrim behind the menu lives in the screen's content, and
 * places this over that scrim. [visible] hides it — scaled away rather than snapped — while a
 * selection or a move owns the screen.
 */
@Composable
fun NewNoteFab(
    open: Boolean,
    visible: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onCreate: (NoteKind) -> Unit,
    onCreateFolder: () -> Unit,
    onImportPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    FloatingActionButtonMenu(
        expanded = open,
        modifier = modifier,
        button = {
            val add = NotesIcons.Add
            val close = NotesIcons.Close
            val currentAdd by rememberUpdatedState(add)
            val currentClose by rememberUpdatedState(close)
            val newLabel = stringResource(R.string.library_new)
            val closeLabel = stringResource(R.string.library_new_close)
            ToggleFloatingActionButton(
                checked = open,
                onCheckedChange = {
                    haptics.tick()
                    onOpenChange(it)
                },
                modifier = Modifier
                    .animateFloatingActionButton(visible = visible || open, alignment = Alignment.BottomEnd)
                    .semantics {
                        // The label has to follow the state, or a screen reader announces "new" for
                        // a button that now closes the menu.
                        contentDescription = if (open) closeLabel else newLabel
                        stateDescription = if (open) closeLabel else newLabel
                    },
            ) {
                val icon: ImageVector by remember { derivedStateOf { if (checkedProgress > 0.5f) currentClose else currentAdd } }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                onOpenChange(false)
                onCreate(NoteKind.INK)
            },
            icon = { Icon(NotesIcons.InkNote, contentDescription = null) },
            text = { Text(stringResource(R.string.library_new_ink)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                onOpenChange(false)
                onCreate(NoteKind.TEXT)
            },
            icon = { Icon(NotesIcons.TextNote, contentDescription = null) },
            text = { Text(stringResource(R.string.library_new_text)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                onOpenChange(false)
                onImportPdf()
            },
            icon = { Icon(NotesIcons.Pdf, contentDescription = null) },
            text = { Text(stringResource(R.string.library_new_from_pdf)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                onOpenChange(false)
                onCreateFolder()
            },
            icon = { Icon(NotesIcons.NewFolder, contentDescription = null) },
            text = { Text(stringResource(R.string.library_new_folder)) },
        )
    }
}
