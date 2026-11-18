package pl.dakil.notes.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.data.TrashedItem
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.sync.TrashNaming
import pl.dakil.notes.ui.designsystem.BackButton
import pl.dakil.notes.ui.designsystem.CollapsingScaffold
import pl.dakil.notes.ui.designsystem.EmptyState
import pl.dakil.notes.ui.designsystem.ShapedIcon
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.designsystem.segmentedItemColors
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.SegmentedShapes

/**
 * What has been deleted, for thirty days: each item with the folder it came from and how long it
 * has left, to restore or let go of early.
 *
 * Reached from settings rather than the navigation bar: it is where a mistake is undone, not a place
 * anyone visits daily, and the snackbar after each delete is the quick way back. Laid out as a
 * settings page for that reason — the collapsing title, the back arrow, rows joined into one card.
 */
@Composable
fun TrashScreen(viewModel: TrashViewModel, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    val haptics = rememberHaptics()

    val snackbarHostState = remember { SnackbarHostState() }
    val message = when {
        state.restored != null -> stringResource(R.string.trash_restored_x, state.restored!!)
        state.errorRes != null -> stringResource(state.errorRes!!, state.errorArg.orEmpty())
        else -> null
    }
    LaunchedEffect(message) {
        message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessages()
    }

    CollapsingScaffold(
        title = stringResource(R.string.trash_title),
        modifier = modifier.fillMaxSize(),
        subtitle = if (state.items.isNotEmpty()) {
            pluralStringResource(R.plurals.trash_count, state.items.size, state.items.size)
        } else {
            null
        },
        navigationIcon = { BackButton(onNavigateBack) },
        actions = {
            if (state.items.isNotEmpty()) {
                BarAction(NotesIcons.EmptyTrash, stringResource(R.string.trash_empty_action), viewModel::askEmpty)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))

                state.items.isEmpty() -> EmptyState(
                    icon = NotesIcons.Trash,
                    polygon = MaterialShapes.Cookie9Sided,
                    title = stringResource(R.string.trash_empty_title),
                    body = stringResource(R.string.trash_retention, TrashNaming.RETENTION_DAYS),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                ) {
                    item(key = "retention") {
                        Text(
                            stringResource(R.string.trash_retention, TrashNaming.RETENTION_DAYS),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
                        )
                    }
                    itemsIndexed(state.items, key = { _, item -> item.ref.value }) { index, item ->
                        Column(Modifier.animateItem()) {
                            if (index > 0) Spacer(Modifier.height(SegmentedShapes.gap))
                            TrashRow(
                                item = item,
                                index = index,
                                count = state.items.size,
                                now = viewModel.now(),
                                onRestore = {
                                    haptics.confirm()
                                    viewModel.restore(item)
                                },
                                onDelete = { viewModel.askDeleteForever(item) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.pendingDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever_x, item.displayName()),
            text = stringResource(R.string.library_delete_undone),
            confirm = stringResource(R.string.trash_delete_forever),
            onConfirm = {
                haptics.confirm()
                viewModel.confirmDeleteForever()
            },
            onDismiss = viewModel::dismissDialogs,
        )
    }
    if (state.confirmingEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            text = stringResource(R.string.trash_empty_confirm_text),
            confirm = stringResource(R.string.trash_empty_action),
            onConfirm = {
                haptics.confirm()
                viewModel.confirmEmpty()
            },
            onDismiss = viewModel::dismissDialogs,
        )
    }
}

@Composable
private fun TrashRow(item: TrashedItem, index: Int, count: Int, now: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val origin = if (item.originPath.isEmpty()) {
        stringResource(R.string.trash_from_root)
    } else {
        stringResource(R.string.trash_from_x, item.originPath)
    }
    val days = TrashNaming.daysLeft(item.deletedAt, now)
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = segmentedItemColors(),
        leadingContent = {
            ShapedIcon(
                icon = when {
                    item.isDirectory -> NotesIcons.FolderFill
                    NoteKind.of(item.name) == NoteKind.TEXT -> NotesIcons.TextNote
                    else -> NotesIcons.InkNote
                },
                polygon = if (item.isDirectory) MaterialShapes.Cookie4Sided else MaterialShapes.Square,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = {
            Text("$origin · ${pluralStringResource(R.plurals.trash_days_left, days, days)}", maxLines = 2)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore, shapes = IconButtonDefaults.shapes()) {
                    Icon(NotesIcons.RestoreFromTrash, contentDescription = stringResource(R.string.trash_restore))
                }
                IconButton(onClick = onDelete, shapes = IconButtonDefaults.shapes()) {
                    Icon(NotesIcons.DeleteForever, contentDescription = stringResource(R.string.trash_delete_forever))
                }
            }
        },
    ) {
        Text(item.displayName(), maxLines = 1)
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NotesIcons.DeleteForever, contentDescription = null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.library_cancel)) } },
    )
}
