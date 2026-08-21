package pl.dakil.notes.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.NoteSummary
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.icons.NotesIcons
import java.text.DateFormat
import java.util.Date

/**
 * The note browser.
 *
 * Composed entirely from stock Material 3: `SearchBar`, `FilterChip` for tags, `AssistChip` for
 * sort, `ListItem` rows, and an extended FAB on wide layouts. Nothing here is a reimplementation of
 * something the design system already provides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenNote: (StoreRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchActive by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var newNoteMenuOpen by remember { mutableStateOf(false) }
    // Held by the screen rather than by the row that asked for it. Dismissing the row's menu hands
    // focus back to the search bar, which expands on focus and takes the whole list out of
    // composition — and a dialog owned by a row in that list would go with it.
    var renaming by remember { mutableStateOf<NoteSummary?>(null) }

    renaming?.let { note ->
        RenameNoteDialog(
            initial = note.title,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                viewModel.renameNote(note.ref, name)
            },
        )
    }

    // The menu takes no focus, so without this back would dismiss the whole screen behind it.
    BackHandler(enabled = newNoteMenuOpen) { newNoteMenuOpen = false }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The search bar is not in the `topBar` slot — it has to be free to expand over the whole
        // screen — so it applies the status bar inset itself, as every top-edge M3 component does.
        // Letting the Scaffold pad for it as well applies it twice, which is what pushed the search
        // bar down the screen. The horizontal and bottom edges are still the Scaffold's business.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        floatingActionButton = {
            NewNoteFab(
                open = newNoteMenuOpen,
                onOpenChange = { newNoteMenuOpen = it },
                onCreate = { kind -> viewModel.createNote("Untitled", kind, onOpenNote) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(padding).fillMaxSize()) {

                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            onSearch = { searchActive = false },
                            expanded = searchActive,
                            onExpandedChange = { searchActive = it },
                            placeholder = { Text("Search notes") },
                            leadingIcon = { Icon(NotesIcons.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setQuery("") }) {
                                        Icon(NotesIcons.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                        )
                    },
                    expanded = searchActive,
                    onExpandedChange = { searchActive = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (searchActive) 0.dp else 12.dp),
                ) {
                    // Expanded search results, shown over the list while typing.
                    NoteList(
                        notes = state.visibleNotes,
                        selected = state.selectedNote,
                        onOpen = { ref ->
                            searchActive = false
                            onOpenNote(ref)
                        },
                        onDelete = viewModel::deleteNote,
                        onRename = { renaming = it },
                    )
                }

                if (!searchActive) {
                    Breadcrumbs(state, viewModel)
                    FilterRow(state, viewModel, sortMenuOpen) { sortMenuOpen = it }
                    HorizontalDivider()

                    when {
                        state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        state.error != null -> EmptyState(
                            title = "Nothing to show",
                            detail = state.error!!,
                            action = "Retry",
                            onAction = viewModel::refresh,
                        )

                        state.folders.isEmpty() && state.visibleNotes.isEmpty() -> EmptyState(
                            title = if (state.isSearching) "No matches" else "No notes yet",
                            detail = if (state.isSearching) {
                                "Nothing here matches \"${state.query}\"."
                            } else {
                                "Tap the button below to start your first note."
                            },
                        )

                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(state.folders, key = { it.ref.value }) { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.name) },
                                    leadingContent = {
                                        Icon(NotesIcons.Folder, contentDescription = null)
                                    },
                                    modifier = Modifier.clickableRow { viewModel.openFolder(folder) },
                                )
                            }
                            items(state.visibleNotes, key = { it.ref.value }) { note ->
                                NoteRow(
                                    note = note,
                                    selected = state.selectedNote == note.ref,
                                    onOpen = { onOpenNote(note.ref) },
                                    onDelete = { viewModel.deleteNote(note.ref) },
                                    onRename = { renaming = note },
                                )
                            }
                        }
                    }
                }
            }

            // Under the FAB but over the list: tapping anywhere else closes the menu, which is the
            // only dismissal a menu with no focus of its own can offer.
            AnimatedVisibility(
                visible = newNoteMenuOpen,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { newNoteMenuOpen = false }
                )
            }
        }
    }
}

@Composable
private fun NoteList(
    notes: List<NoteSummary>,
    selected: StoreRef?,
    onOpen: (StoreRef) -> Unit,
    onDelete: (StoreRef) -> Unit,
    onRename: (NoteSummary) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(notes, key = { it.ref.value }) { note ->
            NoteRow(
                note = note,
                selected = selected == note.ref,
                onOpen = { onOpen(note.ref) },
                onDelete = { onDelete(note.ref) },
                onRename = { onRename(note) },
            )
        }
    }
}

@Composable
private fun NoteRow(
    note: NoteSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    ListItem(
        headlineContent = {
            Text(
                text = note.title.ifBlank { "Untitled" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (note.snippet.isNotBlank()) {
                    Text(note.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = dateFormat.format(Date(note.modifiedAt)) +
                        if (note.tags.isEmpty()) "" else "  ·  " + note.tags.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = when (note.kind) {
                    NoteKind.INK -> NotesIcons.Note
                    NoteKind.TEXT -> NotesIcons.TextNote
                },
                contentDescription = when (note.kind) {
                    NoteKind.INK -> "Ink note"
                    NoteKind.TEXT -> "Text note"
                },
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(NotesIcons.More, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(NotesIcons.Rename, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(NotesIcons.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickableRow(onOpen),
    )
}

@Composable
private fun Breadcrumbs(state: LibraryUiState, viewModel: LibraryViewModel) {
    if (state.crumbs.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.crumbs.forEachIndexed { i, crumb ->
            if (i > 0) Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { viewModel.goToCrumb(i) }) { Text(crumb.name) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    sortMenuOpen: Boolean,
    setSortMenuOpen: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { setSortMenuOpen(true) },
                label = { Text(state.sort.label) },
                leadingIcon = { Icon(NotesIcons.Sort, contentDescription = null) },
            )
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { setSortMenuOpen(false) }) {
                for (sort in NoteSort.entries) {
                    DropdownMenuItem(
                        text = { Text(sort.label) },
                        onClick = {
                            setSortMenuOpen(false)
                            viewModel.setSort(sort)
                        },
                    )
                }
            }
        }

        for (tag in state.allTags) {
            FilterChip(
                selected = tag in state.activeTags,
                onClick = { viewModel.toggleTag(tag) },
                label = { Text(tag) },
                leadingIcon = if (tag in state.activeTags) {
                    { Icon(NotesIcons.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    detail: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

private val NoteSort.label: String
    get() = when (this) {
        NoteSort.MODIFIED_DESC -> "Recently edited"
        NoteSort.MODIFIED_ASC -> "Oldest edited"
        NoteSort.TITLE_ASC -> "Title A–Z"
        NoteSort.TITLE_DESC -> "Title Z–A"
        NoteSort.CREATED_DESC -> "Recently created"
    }

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
