package pl.dakil.notes.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.designsystem.BackButton
import pl.dakil.notes.ui.designsystem.RollingText
import pl.dakil.notes.ui.designsystem.appTopBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The library's title: where the user is, and how much is in it.
 *
 * At the root it is simply the library's name; inside a folder it is that folder, with the way back
 * up as its navigation icon. The line under it counts what is here and rolls as it changes, which is
 * the whole of the screen's "welcome" — the notes themselves are the point, not a greeting.
 */
@Composable
fun LibraryTopBar(
    title: String,
    subtitle: String?,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    onSearch: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    LargeFlexibleTopAppBar(
        modifier = modifier,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = subtitle?.let { { RollingText(it) } },
        navigationIcon = { if (canGoUp) BackButton(onGoUp) },
        actions = {
            BarAction(NotesIcons.Search, stringResource(R.string.library_search), onSearch)
        },
        colors = appTopBarColors(),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The app bar that replaces the title while things are selected: how many, and the way out. The
 * actions live in [SelectionToolbar] at the bottom, under the thumb that did the picking.
 */
@Composable
fun SelectionTopBar(count: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        modifier = modifier,
        title = { RollingText(pluralStringResource(R.plurals.library_selected_x, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear, shapes = IconButtonDefaults.shapes()) {
                Icon(NotesIcons.Close, contentDescription = stringResource(R.string.library_clear_selection))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/**
 * What can be done to the selection, floating over the notes.
 *
 * Rename is offered for one item only. Renaming several at once has no sensible meaning — they
 * would all end up with the same name and the store would step aside from the collisions, leaving
 * "Notes", "Notes (2)", "Notes (3)" — so the button goes away rather than doing that.
 */
@Composable
fun SelectionToolbar(
    count: Int,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        if (count == 1) BarAction(NotesIcons.Rename, stringResource(R.string.library_rename), onRename)
        BarAction(NotesIcons.MoveTo, stringResource(R.string.library_move), onMove)
        BarAction(NotesIcons.Delete, stringResource(R.string.library_delete), onDelete)
    }
}

/**
 * The bar along the bottom while a move is in flight.
 *
 * It sits at the bottom because the browsing happens above it: the user is walking the folder tree
 * with one hand and the confirmation has to stay under the thumb the whole way. It says where the
 * items are going, so nobody has to remember which folder they have ended up in.
 */
@Composable
fun MoveToolbar(
    count: Int,
    destination: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        IconButton(onClick = onCancel, shapes = IconButtonDefaults.shapes()) {
            Icon(NotesIcons.Close, contentDescription = stringResource(R.string.library_cancel))
        }
        Column(
            modifier = Modifier.widthIn(max = 180.dp).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            RollingText(
                pluralStringResource(R.plurals.library_moving_x, count, count),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (enabled) {
                    stringResource(R.string.library_move_into_x, destination)
                } else {
                    stringResource(R.string.library_move_hint)
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onConfirm,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(NotesIcons.MoveTo, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.library_move_here), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

/** An icon action with its name on long press, the way every bar in the app labels its icons. */
@Composable
internal fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** The folder path, as chips, so "where am I" never needs guessing and any level is one tap away. */
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
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        crumbs.forEachIndexed { i, crumb ->
            if (i > 0) {
                Icon(
                    imageVector = NotesIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val current = i == crumbs.lastIndex
            AssistChip(
                onClick = { onCrumb(i) },
                enabled = !current,
                label = {
                    Text(
                        // The root is a label, not a folder, so it follows the language of the moment.
                        text = if (i == 0) stringResource(R.string.library_root_folder) else crumb.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = if (i == 0) {
                    { Icon(NotesIcons.Note, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
                } else {
                    null
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.primary,
                ),
                border = if (current) null else AssistChipDefaults.assistChipBorder(enabled = true),
            )
        }
    }
}
