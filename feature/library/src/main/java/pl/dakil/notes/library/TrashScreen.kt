package pl.dakil.notes.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.data.TrashedItem
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.sync.TrashNaming
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * What has been deleted, for thirty days: each item with the folder it came from and how long it
 * has left, to restore or let go of early.
 *
 * Reached from settings rather than the navigation bar: it is where a mistake is undone, not a place
 * anyone visits daily, and the snackbar after each delete is the quick way back. Laid out as a
 * settings page for that reason — the flat bar, the back arrow — with stock `ListItem` rows and
 * `AlertDialog` confirmations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(viewModel: TrashViewModel, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.trash_back))
                    }
                },
                colors = flatTopAppBarColors(),
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = viewModel::askEmpty) { Text(stringResource(R.string.trash_empty_action)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.items.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.trash_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.trash_retention, TrashNaming.RETENTION_DAYS),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.trash_retention, TrashNaming.RETENTION_DAYS),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                    items(state.items, key = { it.ref.value }) { item ->
                        TrashRow(
                            item = item,
                            now = viewModel.now(),
                            onRestore = { viewModel.restore(item) },
                            onDelete = { viewModel.askDeleteForever(item) },
                        )
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
            onConfirm = viewModel::confirmDeleteForever,
            onDismiss = viewModel::dismissDialogs,
        )
    }
    if (state.confirmingEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            text = stringResource(R.string.trash_empty_confirm_text),
            confirm = stringResource(R.string.trash_empty_action),
            onConfirm = viewModel::confirmEmpty,
            onDismiss = viewModel::dismissDialogs,
        )
    }
}

@Composable
private fun TrashRow(item: TrashedItem, now: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val origin = if (item.originPath.isEmpty()) {
        stringResource(R.string.trash_from_root)
    } else {
        stringResource(R.string.trash_from_x, item.originPath)
    }
    val days = TrashNaming.daysLeft(item.deletedAt, now)
    ListItem(
        leadingContent = {
            Icon(
                imageVector = when {
                    item.isDirectory -> NotesIcons.Folder
                    NoteKind.of(item.name) == NoteKind.TEXT -> NotesIcons.TextNote
                    else -> NotesIcons.InkNote
                },
                contentDescription = null,
            )
        },
        headlineContent = { Text(item.displayName(), maxLines = 1) },
        supportingContent = {
            Text("$origin · ${pluralStringResource(R.plurals.trash_days_left, days, days)}", maxLines = 2)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(NotesIcons.Undo, contentDescription = stringResource(R.string.trash_restore))
                }
                IconButton(onClick = onDelete) {
                    Icon(NotesIcons.Delete, contentDescription = stringResource(R.string.trash_delete_forever))
                }
            }
        },
    )
}


@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NotesIcons.Delete, contentDescription = null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}
