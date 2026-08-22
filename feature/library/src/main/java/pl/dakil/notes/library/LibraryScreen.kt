package pl.dakil.notes.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.icons.NotesIcons
import java.text.DateFormat

/**
 * The note browser: a file manager over the library, with folders that are real directories.
 *
 * Composed entirely from stock Material 3 — `SearchBar`, `TopAppBar` for the selection state,
 * `FilterChip`, `AssistChip`, `ListItem`, `Card`, `BottomAppBar`. Nothing here reimplements
 * something the design system already provides; the one hand-rolled piece is [NewNoteFab], and its
 * own documentation says why.
 *
 * The screen has three modes and they are mutually exclusive: browsing, selecting, and moving. Each
 * owns the top of the screen and back means something different in each, which is why the back
 * handlers below are ordered rather than combined.
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
    var filterMenuOpen by remember { mutableStateOf(false) }
    var newMenuOpen by remember { mutableStateOf(false) }
    // Held by the screen rather than by the row that asked for it. Dismissing a row hands focus
    // back to the search bar, which expands on focus and takes the whole list out of composition —
    // and a dialog owned by a row in that list would go with it.
    var renaming by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    val selected = state.selectedItems
    val moving = state.moving

    if (renaming) {
        val item = selected.singleOrNull()
        if (item == null) {
            renaming = false
        } else {
            RenameNoteDialog(
                initial = item.name,
                onDismiss = { renaming = false },
                onConfirm = { name ->
                    renaming = false
                    viewModel.renameSelected(name)
                },
            )
        }
    }

    if (newFolder) {
        NewFolderDialog(
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                newFolder = false
                viewModel.createFolder(name)
            },
        )
    }

    state.pendingDelete?.let { items ->
        ConfirmDeleteDialog(
            items = items,
            onDismiss = viewModel::cancelDelete,
            onConfirm = viewModel::confirmDelete,
        )
    }

    // Every dialog here has a field that takes focus, and when the dialog goes the focus goes back
    // to whatever had it — the search bar, which expands the moment it is focused. So closing the
    // "new folder" dialog left the browser looking like a search with the keyboard up over it.
    //
    // This has to run *after* the dialog has left the composition, which is why it is an effect and
    // not part of the dismissal callbacks: those fire before the focus is handed back, and anything
    // they set is immediately overwritten by it.
    val focusManager = LocalFocusManager.current
    val dialogOpen = renaming || newFolder || state.pendingDelete != null
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen) {
            focusManager.clearFocus()
            searchActive = false
        }
    }

    // The menu takes no focus, so without this back would dismiss the whole screen behind it.
    BackHandler(enabled = newMenuOpen) { newMenuOpen = false }
    BackHandler(enabled = !newMenuOpen && state.selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !newMenuOpen && !state.selecting && moving != null) { viewModel.cancelMove() }
    // Last: only once nothing transient is open does back mean "leave this folder".
    BackHandler(enabled = !newMenuOpen && !state.selecting && moving == null && state.canGoUp) {
        viewModel.goUp()
    }

    val handlers = LibraryItemHandlers(
        onOpen = { item ->
            when {
                item is LibraryItem.Folder -> viewModel.openFolder(item)
                // While moving, a note is scenery: the only thing to do here is find a folder.
                moving != null -> Unit
                else -> {
                    searchActive = false
                    onOpenNote(item.ref)
                }
            }
        },
        onToggle = { item ->
            // Selecting from the expanded search overlay collapses it: the selection app bar takes
            // the search bar's place, and leaving the overlay expanded behind it would hide the
            // very rows that are now selected. The results themselves stay — the query is not
            // cleared — so the list underneath is the same one that was just being read.
            searchActive = false
            viewModel.toggleSelection(item)
        },
        selecting = state.selecting,
        moving = moving != null,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Neither the search bar nor the selection app bar is in the `topBar` slot — the search bar
        // has to be free to expand over the whole screen — so both apply the status bar inset
        // themselves, as every top-edge M3 component does. Letting the Scaffold pad for it as well
        // applies it twice, which is what pushed the search bar down the screen. The horizontal and
        // bottom edges are still the Scaffold's business.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        bottomBar = {
            if (moving != null) {
                MoveBar(
                    count = moving.items.size,
                    destination = state.folderName,
                    enabled = canMoveInto(moving, state.path),
                    onCancel = viewModel::cancelMove,
                    onConfirm = viewModel::confirmMoveHere,
                )
            }
        },
        floatingActionButton = {
            // Nothing new is made mid-selection or mid-move; the bars own the screen then.
            if (!state.selecting && moving == null) {
                NewNoteFab(
                    open = newMenuOpen,
                    onOpenChange = { newMenuOpen = it },
                    onCreate = { kind -> viewModel.createNote("Untitled", kind, onOpenNote) },
                    onCreateFolder = { newFolder = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(padding).fillMaxSize()) {

                if (state.selecting) {
                    SelectionTopBar(
                        count = selected.size,
                        canMove = true,
                        onClear = viewModel::clearSelection,
                        onRename = { renaming = true },
                        onMove = viewModel::beginMove,
                        onDelete = viewModel::askDelete,
                    )
                } else {
                    LibrarySearchBar(
                        state = state,
                        active = searchActive,
                        onActiveChange = { active ->
                            searchActive = active
                            if (!active) viewModel.closeSearch()
                        },
                        onQuery = viewModel::setQuery,
                        handlers = handlers,
                        loadPreview = viewModel::loadPreview,
                        dateFormat = dateFormat,
                    )
                }

                if (!searchActive) {
                    Breadcrumbs(state.crumbs, viewModel::goToCrumb)
                    FilterRow(
                        state = state,
                        sortMenuOpen = sortMenuOpen,
                        setSortMenuOpen = { sortMenuOpen = it },
                        filterMenuOpen = filterMenuOpen,
                        setFilterMenuOpen = { filterMenuOpen = it },
                        onSort = viewModel::setSort,
                        onFilter = viewModel::setFilter,
                        onTag = viewModel::toggleTag,
                    )
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

                        state.visibleItems.isEmpty() -> EmptyState(
                            title = if (state.isSearching) "No matches" else "Nothing here yet",
                            detail = when {
                                state.isSearching -> "Nothing in ${state.folderName} matches \"${state.query}\"."
                                state.filter != LibraryFilter.ALL ->
                                    "No ${state.filter.label.lowercase()} in this folder."

                                else -> "Tap the button below to start your first note."
                            },
                        )

                        else -> LibraryContent(
                            state = state,
                            handlers = handlers,
                            loadPreview = viewModel::loadPreview,
                            dateFormat = dateFormat,
                        )
                    }
                }
            }

            // Under the FAB but over the list: tapping anywhere else closes the menu, which is the
            // only dismissal a menu with no focus of its own can offer.
            AnimatedVisibility(visible = newMenuOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { newMenuOpen = false }
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
) {
    // Room at the foot for the FAB, or for the move bar when one is up, so the last card is never
    // parked underneath either.
    val bottom = if (state.moving != null) 8.dp else 88.dp
    val padding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottom)
    when (state.layout) {
        LibraryLayout.CARDS -> LibraryCards(
            items = state.visibleItems,
            selection = state.selection,
            handlers = handlers,
            loadPreview = loadPreview,
            dateFormat = dateFormat,
            showPath = state.isSearching,
            contentPadding = padding,
        )

        LibraryLayout.LIST -> LibraryList(
            items = state.visibleItems,
            selection = state.selection,
            handlers = handlers,
            loadPreview = loadPreview,
            dateFormat = dateFormat,
            showPath = state.isSearching,
            contentPadding = PaddingValues(bottom = bottom),
        )
    }
}

/**
 * The search field, and the results it shows while it is expanded.
 *
 * Scoped to the folder the user is standing in, and it says so — both in the placeholder and in a
 * line above the results. A search that silently covered the whole library while the breadcrumbs
 * said "Work" would be answering a question nobody asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySearchBar(
    state: LibraryUiState,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onQuery: (String) -> Unit,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = state.query,
                onQueryChange = onQuery,
                onSearch = { onActiveChange(false) },
                expanded = active,
                onExpandedChange = onActiveChange,
                placeholder = { Text("Search in ${state.folderName}") },
                leadingIcon = { Icon(NotesIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(NotesIcons.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
        },
        expanded = active,
        onExpandedChange = onActiveChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (active) 0.dp else 12.dp),
    ) {
        // Only inside a folder. At the root the search covers everything, which is what a search
        // bar with no folder above it already means — saying so is a line of chrome that tells the
        // reader nothing they had not assumed.
        if (state.canGoUp) {
            Text(
                text = "Searching ${state.folderName} and the folders inside it.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
        }
        if (state.isSearching && state.visibleItems.isEmpty()) {
            EmptyState(
                title = "No matches",
                detail = "Nothing in ${state.folderName} matches \"${state.query}\".",
            )
        } else {
            LibraryList(
                items = state.visibleItems,
                selection = state.selection,
                handlers = handlers,
                loadPreview = loadPreview,
                dateFormat = dateFormat,
                // Results can come from any folder beneath this one, so each says where it lives.
                showPath = true,
                contentPadding = PaddingValues(bottom = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    state: LibraryUiState,
    sortMenuOpen: Boolean,
    setSortMenuOpen: (Boolean) -> Unit,
    filterMenuOpen: Boolean,
    setFilterMenuOpen: (Boolean) -> Unit,
    onSort: (NoteSort) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onTag: (String) -> Unit,
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
                        trailingIcon = if (sort == state.sort) {
                            { Icon(NotesIcons.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            setSortMenuOpen(false)
                            onSort(sort)
                        },
                    )
                }
            }
        }

        Box {
            AssistChip(
                onClick = { setFilterMenuOpen(true) },
                label = { Text(state.filter.label) },
                leadingIcon = { Icon(NotesIcons.Filter, contentDescription = null) },
            )
            DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { setFilterMenuOpen(false) }) {
                for (filter in LibraryFilter.entries) {
                    DropdownMenuItem(
                        text = { Text(filter.label) },
                        trailingIcon = if (filter == state.filter) {
                            { Icon(NotesIcons.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            setFilterMenuOpen(false)
                            onFilter(filter)
                        },
                    )
                }
            }
        }

        for (tag in state.allTags) {
            FilterChip(
                selected = tag in state.activeTags,
                onClick = { onTag(tag) },
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
