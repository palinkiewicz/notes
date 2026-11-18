package pl.dakil.notes.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import pl.dakil.notes.ui.designsystem.AppHaptics
import pl.dakil.notes.ui.designsystem.rememberHaptics

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.library.R
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * What every row and card in the browser does when it is touched.
 *
 * Gathered into one object because the list and the card grid have to behave identically — a long
 * press means the same thing in both, and the moment they are two sets of lambdas they will drift.
 */
class LibraryItemHandlers(
    val onOpen: (LibraryItem) -> Unit,
    val onToggle: (LibraryItem) -> Unit,
    val selecting: Boolean,
    val moving: Boolean,
)

/**
 * Tap and long-press for one item.
 *
 * Long press starts a selection, and once one is running a plain tap adds to it rather than opening
 * anything — the standard file-manager contract, and the reason the per-row overflow menu could go
 * away entirely. While a move is in flight nothing may be selected: the selection *is* the thing
 * being moved, and letting it change halfway would leave the user carrying a different armful than
 * the one they picked up.
 *
 * [interactionSource] is shared with whatever draws the press — the card's squish.
 */
@Composable
fun Modifier.libraryItem(
    item: LibraryItem,
    handlers: LibraryItemHandlers,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val haptics = rememberHaptics()
    return combinedClickable(
        interactionSource = interactionSource,
        indication = if (interactionSource != null) ripple() else LocalIndication.current,
        onClick = { handlers.click(item) },
        onLongClick = handlers.longClick(item, haptics),
    )
}

/** What a tap on [item] does: add it to a running selection, or open it. */
fun LibraryItemHandlers.click(item: LibraryItem) {
    if (selecting) onToggle(item) else onOpen(item)
}

/** What a long press on [item] does, or null while a move is running and nothing may be picked. */
fun LibraryItemHandlers.longClick(item: LibraryItem, haptics: AppHaptics): (() -> Unit)? =
    if (moving) {
        null
    } else {
        {
            haptics.longPress()
            onToggle(item)
        }
    }

/** The glyph that says what a row is, at a glance and without reading its name. */
val LibraryItem.icon: ImageVector
    @Composable get() = when (this) {
        is LibraryItem.Folder -> NotesIcons.Folder
        is LibraryItem.Note -> when (summary.kind) {
            NoteKind.INK -> NotesIcons.InkNote
            NoteKind.TEXT -> NotesIcons.TextNote
        }
    }

@Composable
fun LibraryItem.contentDescription(): String = stringResource(
    when (this) {
        is LibraryItem.Folder -> R.string.library_item_folder
        is LibraryItem.Note -> when (summary.kind) {
            NoteKind.INK -> R.string.library_item_ink_note
            NoteKind.TEXT -> R.string.library_item_text_note
        }
    },
)

/** An untitled note still needs something to be called on screen. */
@Composable
fun LibraryItem.displayName(): String = name.ifBlank { stringResource(R.string.library_untitled) }
