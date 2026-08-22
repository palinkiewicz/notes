package pl.dakil.notes.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.library.R
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The "new" button, expanding into one pill per thing that can be made here.
 *
 * Material 3 grew a real `FloatingActionButtonMenu`, but only in 1.5.0-alpha — the Compose BOM this
 * project pins ships 1.4.0. Taking it would mean overriding the BOM and moving the app's *entire*
 * Material surface onto an alpha for one component, so this is the small hand-rolled version: stock
 * `FloatingActionButton` and `ExtendedFloatingActionButton`, no new dependency, no size cost.
 *
 * The caller owns [open] because the scrim behind the menu lives in the screen's content, not in the
 * `Scaffold`'s floating-action-button slot.
 */
@Composable
fun NewNoteFab(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onCreate: (NoteKind) -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The single `+` glyph doubles as the close affordance: a quarter turn makes it an ×, which
    // reads as "this same control put the menu here".
    val rotation by animateFloatAsState(
        targetValue = if (open) 45f else 0f,
        animationSpec = tween(ITEM_STAGGER_MS * 3),
        label = "fabRotation",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MenuItem(
            visible = open,
            // The lowest item is nearest the thumb, so it animates in first and out last.
            index = 2,
            icon = NotesIcons.NewFolder,
            label = stringResource(R.string.library_new_folder),
            onClick = {
                onOpenChange(false)
                onCreateFolder()
            },
        )
        MenuItem(
            visible = open,
            index = 1,
            icon = NotesIcons.TextNote,
            label = stringResource(R.string.library_new_text),
            onClick = {
                onOpenChange(false)
                onCreate(NoteKind.TEXT)
            },
        )
        MenuItem(
            visible = open,
            index = 0,
            icon = NotesIcons.InkNote,
            label = stringResource(R.string.library_new_ink),
            onClick = {
                onOpenChange(false)
                onCreate(NoteKind.INK)
            },
            modifier = Modifier.padding(bottom = 4.dp),
        )

        FloatingActionButton(onClick = { onOpenChange(!open) }) {
            Icon(
                imageVector = NotesIcons.Add,
                // The label has to follow the state, or a screen reader announces "new note" for a
                // button that now closes the menu.
                contentDescription = stringResource(
                    if (open) R.string.library_new_close else R.string.library_new,
                ),
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun MenuItem(
    visible: Boolean,
    index: Int,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val delay = index * ITEM_STAGGER_MS
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ITEM_DURATION_MS, delayMillis = delay)) +
            scaleIn(tween(ITEM_DURATION_MS, delayMillis = delay), initialScale = 0.8f) +
            slideInVertically(tween(ITEM_DURATION_MS, delayMillis = delay)) { it / 2 },
        exit = fadeOut(tween(ITEM_DURATION_MS)) +
            scaleOut(tween(ITEM_DURATION_MS), targetScale = 0.8f) +
            slideOutVertically(tween(ITEM_DURATION_MS)) { it / 2 },
        modifier = modifier,
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(icon, contentDescription = null) },
            text = { Text(label) },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private const val ITEM_DURATION_MS = 160
private const val ITEM_STAGGER_MS = 40
