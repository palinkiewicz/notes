package pl.dakil.notes.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.library.R
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The app bar that replaces the search bar while things are selected.
 *
 * Rename is offered for one item only. Renaming several at once has no sensible meaning — they
 * would all end up with the same name and the store would step aside from the collisions, leaving
 * "Notes", "Notes (2)", "Notes (3)" — so the button goes away rather than doing that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    canMove: Boolean,
    onClear: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(pluralStringResource(R.plurals.library_selected_x, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = NotesIcons.Close,
                    contentDescription = stringResource(R.string.library_clear_selection),
                )
            }
        },
        actions = {
            if (count == 1) {
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = NotesIcons.Rename,
                        contentDescription = stringResource(R.string.library_rename),
                    )
                }
            }
            if (canMove) {
                IconButton(onClick = onMove) {
                    Icon(
                        imageVector = NotesIcons.Move,
                        contentDescription = stringResource(R.string.library_move),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = NotesIcons.Delete,
                    contentDescription = stringResource(R.string.library_delete),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

/**
 * The bar along the bottom while a move is in flight.
 *
 * It sits at the bottom rather than the top because the browsing happens above it: the user is
 * walking the folder tree with one hand and the confirmation has to stay under the thumb the whole
 * way. The button says where the items are going, so nobody has to remember which folder they have
 * ended up in.
 */
@Composable
fun MoveBar(
    count: Int,
    destination: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomAppBar(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.library_moving_x, count, count),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (enabled) {
                    stringResource(R.string.library_move_into_x, destination)
                } else {
                    stringResource(R.string.library_move_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.library_cancel)) }
        Button(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier.padding(end = 16.dp),
        ) { Text(stringResource(R.string.library_move_here)) }
    }
}

/** The folder path shown along the top, so "where am I" never needs guessing. */
@Composable
fun Breadcrumbs(
    crumbs: List<Crumb>,
    onCrumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.size <= 1) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, crumb ->
            if (i > 0) {
                Icon(
                    imageVector = NotesIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onCrumb(i) }) {
                Text(crumb.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
