package pl.dakil.notes.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutContext
import pl.dakil.notes.ui.designsystem.AppLinearProgress
import pl.dakil.notes.ui.designsystem.AppMenu
import pl.dakil.notes.ui.designsystem.AppMenuChoiceItem
import pl.dakil.notes.ui.designsystem.AppMenuGroup
import pl.dakil.notes.ui.designsystem.ConnectedToggleRow
import pl.dakil.notes.ui.designsystem.EmptyState
import pl.dakil.notes.ui.designsystem.EntranceScope
import pl.dakil.notes.ui.designsystem.ToggleOption
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.keys.ShortcutHandler
import java.text.DateFormat

/**
 * The note browser: a file manager over the library, with folders that are real directories.
 *
 * Composed from stock Material 3 Expressive — a large flexible app bar whose subtitle counts what is
 * here, the search bar that opens full screen, a connected button group for the kind filter, the
 * FAB menu for making things, and a floating toolbar for acting on a selection.
 *
 * The screen has three modes and they are mutually exclusive: browsing, selecting, and moving. Each
 * owns the edges of the screen and back means something different in each, which is why the back
 * handlers below are ordered rather than combined.
 *
 * [reselected] counts taps on the Notes tab while it is already open; each one scrolls to the top.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenNote: (StoreRef) -> Unit,
    modifier: Modifier = Modifier,
    reselected: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val searchState = rememberSearchBarState()
    // Seeded from the view model: the screen leaves composition while a note is open, and a query
    // kept from before that has to be what the field shows when it comes back.
    val searchText = rememberTextFieldState(state.query)
    var newMenuOpen by remember { mutableStateOf(false) }
    // Held by the screen rather than by the row that asked for it, so a dialog outlives the list
    // that opened it (a search collapsing takes its rows out of composition).
    var renaming by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val gridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val selected = state.selectedItems
    val moving = state.moving
    val searchExpanded = searchState.targetValue == SearchBarValue.Expanded

    // ---- Search: the field is the source of truth while it is typed into; the view model runs it.
    LaunchedEffect(searchText) {
        snapshotFlow { searchText.text.toString() }.collect { text ->
            if (text != viewModel.state.value.query) viewModel.setQuery(text)
        }
    }
    LaunchedEffect(state.query) {
        if (searchText.text.toString() != state.query) searchText.setTextAndPlaceCursorAtEnd(state.query)
    }
    // Closing the search drops its query, so the results do not linger under the breadcrumbs reading
    // as the contents of a folder they are mostly not in. Picking or opening a result is the
    // exception: the user is still working with those results, so they stay.
    var keepQuery by remember { mutableStateOf(false) }
    var wasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(searchState.currentValue) {
        val expanded = searchState.currentValue == SearchBarValue.Expanded
        if (wasExpanded && !expanded && !keepQuery) viewModel.closeSearch()
        if (!expanded) keepQuery = false
        wasExpanded = expanded
    }
    fun openSearch() {
        scope.launch { searchState.animateToExpanded() }
    }

    // Not mid-selection or mid-move, for the reason the new-note button hides then: the bars own
    // the screen, and a note made from under them would land somewhere nobody was looking.
    ShortcutHandler(ShortcutContext.LIBRARY, enabled = !state.selecting && moving == null) { action ->
        when (action) {
            ShortcutAction.SEARCH -> true.also { openSearch() }
            ShortcutAction.NEW_INK_NOTE -> true.also {
                viewModel.createNote("", NoteKind.INK, intoRoot = false, onOpenNote)
            }
            ShortcutAction.NEW_TEXT_NOTE -> true.also {
                viewModel.createNote("", NoteKind.TEXT, intoRoot = false, onOpenNote)
            }
            else -> false
        }
    }

    // A message that came up from the store already has its own words; one the view model raised
    // itself arrives as a resource id, because that layer has no `Context` to resolve it with.
    val errorMessage = state.error ?: state.errorRes?.let { res ->
        val arg = state.errorArg
        if (arg != null) stringResource(res, arg) else stringResource(res)
    }

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
                haptics.confirm()
                viewModel.createFolder(name)
            },
        )
    }

    state.pendingDelete?.let { items ->
        ConfirmDeleteDialog(
            items = items,
            onDismiss = viewModel::cancelDelete,
            onConfirm = {
                haptics.confirm()
                viewModel.confirmDelete()
            },
        )
    }

    // Every dialog here has a field that takes focus, and when the dialog goes the focus goes back
    // to whatever had it — the search field, which expands the moment it is focused. So closing a
    // dialog left the browser looking like a search with the keyboard up over it. It has to run
    // *after* the dialog has left the composition, which is why it is an effect.
    val focusManager = LocalFocusManager.current
    val dialogOpen = renaming || newFolder || state.pendingDelete != null
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen) focusManager.clearFocus()
    }

    // The menu takes no focus, so without this back would dismiss the whole screen behind it.
    BackHandler(enabled = newMenuOpen) { newMenuOpen = false }
    BackHandler(enabled = !newMenuOpen && state.selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !newMenuOpen && !state.selecting && moving != null) { viewModel.cancelMove() }
    // Last: only once nothing transient is open does back mean "leave this folder".
    BackHandler(enabled = !newMenuOpen && !state.selecting && moving == null && state.canGoUp) {
        viewModel.goUp()
    }

    // Tapping the Notes tab again: back to the top, with the big title unfolded again.
    LaunchedEffect(reselected) {
        if (reselected == 0) return@LaunchedEffect
        scrollBehavior.state.heightOffset = 0f
        if (state.layout == LibraryLayout.CARDS) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0)
    }

    val context = LocalContext.current
    val importingLabel = stringResource(R.string.library_importing_pdf)
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importPdf(context.contentResolver, uri, context.cacheDir.resolve("import"), intoRoot = false, onOpenNote)
    }

    val handlers = LibraryItemHandlers(
        onOpen = { item ->
            when {
                item is LibraryItem.Folder -> viewModel.openFolder(item)
                // While moving, a note is scenery: the only thing to do here is find a folder.
                moving != null -> Unit
                // The full-screen search is a window of its own and would stay over the editor, so
                // it is put away at once before the note opens.
                searchExpanded -> {
                    keepQuery = true
                    scope.launch {
                        searchState.snapTo(0f)
                        onOpenNote(item.ref)
                    }
                }
                else -> onOpenNote(item.ref)
            }
        },
        onToggle = { item ->
            // Selecting from the search collapses it: the selection bars take over the screen,
            // and the results stay — the query is kept — so the list underneath is the one that
            // was just being read.
            if (searchExpanded) {
                keepQuery = true
                scope.launch { searchState.animateToCollapsed() }
            }
            viewModel.toggleSelection(item)
        },
        selecting = state.selecting,
        moving = moving != null,
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val importErrorMessage = state.importError?.let { stringResource(it) }
    LaunchedEffect(importErrorMessage) {
        importErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(importErrorMessage)
        viewModel.dismissImportError()
    }
    // A delete is a move to the trash, and the moment right after it is when a mistaken one is
    // noticed: the way back is offered there and then, not only from the trash screen.
    val trashedCount = state.lastTrashed?.size
    val trashedMessage = trashedCount?.let { pluralStringResource(R.plurals.library_trashed, it, it) }
    val undoLabel = stringResource(R.string.library_undo)
    LaunchedEffect(state.lastTrashed) {
        trashedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(trashedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.forgetLastDelete()
    }

    val inputField: @Composable () -> Unit = {
        SearchInputField(
            searchState = searchState,
            text = searchText,
            placeholder = stringResource(R.string.library_search_in_x, state.folderLabel()),
            onCollapse = { scope.launch { searchState.animateToCollapsed() } },
            onClear = { searchText.setTextAndPlaceCursorAtEnd("") },
        )
    }

    EntranceScope {
        Scaffold(
            modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                AnimatedContent(
                    targetState = state.selecting,
                    transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                    label = "topBar",
                ) { selecting ->
                    if (selecting) {
                        SelectionTopBar(count = selected.size, onClear = viewModel::clearSelection)
                    } else {
                        LibraryTopBar(
                            title = state.folderLabel(),
                            subtitle = librarySubtitle(state),
                            canGoUp = state.canGoUp,
                            onGoUp = viewModel::goUp,
                            onSearch = ::openSearch,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { padding ->
            val layoutDirection = LocalLayoutDirection.current
            // Room at the foot for the new-note button, or the floating toolbars, so the last card
            // is never parked underneath either.
            val contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 112.dp,
            )
            val header: @Composable () -> Unit = {
                LibraryHeader(
                    state = state,
                    searchBar = {
                        SearchBar(
                            state = searchState,
                            inputField = inputField,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    },
                    importingLabel = importingLabel,
                    onCrumb = viewModel::goToCrumb,
                    onSort = viewModel::setSort,
                    onFilter = viewModel::setFilter,
                    onTag = viewModel::toggleTag,
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> HeaderAbove(contentPadding, header) {
                        LoadingIndicator(Modifier.size(64.dp))
                    }

                    errorMessage != null -> HeaderAbove(contentPadding, header) {
                        EmptyState(
                            icon = NotesIcons.Error,
                            polygon = MaterialShapes.SoftBurst,
                            title = stringResource(R.string.library_error_title),
                            body = errorMessage,
                            actions = {
                                androidx.compose.material3.FilledTonalButton(onClick = viewModel::refresh) {
                                    Text(stringResource(R.string.library_retry))
                                }
                            },
                        )
                    }

                    state.visibleItems.isEmpty() -> HeaderAbove(contentPadding, header) {
                        val (icon, polygon) = emptyLook(state)
                        EmptyState(
                            icon = icon,
                            polygon = polygon,
                            title = stringResource(
                                if (state.isSearching) R.string.library_no_matches else R.string.library_empty_title,
                            ),
                            body = when {
                                state.isSearching -> stringResource(
                                    R.string.library_no_matches_in_x,
                                    state.folderLabel(),
                                    state.query,
                                )

                                state.filter != LibraryFilter.ALL -> stringResource(state.filter.emptyMessage)

                                else -> stringResource(R.string.library_empty_hint)
                            },
                        )
                    }

                    state.layout == LibraryLayout.CARDS -> LibraryCards(
                        items = state.visibleItems,
                        selection = state.selection,
                        handlers = handlers,
                        loadPreview = viewModel,
                        renderOrder = state.renderOrder,
                        dateFormat = dateFormat,
                        showPath = state.isSearching,
                        contentPadding = contentPadding,
                        state = gridState,
                        header = header,
                    )

                    else -> LibraryList(
                        items = state.visibleItems,
                        selection = state.selection,
                        handlers = handlers,
                        loadPreview = viewModel,
                        renderOrder = state.renderOrder,
                        dateFormat = dateFormat,
                        showPath = state.isSearching,
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + 112.dp,
                        ),
                        state = listState,
                        header = { Box(Modifier.padding(horizontal = 12.dp)) { header() } },
                    )
                }

                // Under the menu but over the list: tapping anywhere else closes the menu, which is
                // the only dismissal a menu with no focus of its own can offer.
                AnimatedVisibility(visible = newMenuOpen, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { newMenuOpen = false },
                    )
                }

                // Nothing new is made mid-selection or mid-move; the toolbars own the screen then.
                NewNoteFab(
                    open = newMenuOpen,
                    visible = !state.selecting && moving == null,
                    onOpenChange = { newMenuOpen = it },
                    onCreate = { kind ->
                        haptics.confirm()
                        viewModel.createNote("", kind, intoRoot = false, onOpenNote)
                    },
                    onCreateFolder = { newFolder = true },
                    onImportPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = padding.calculateBottomPadding()),
                )

                val toolbarModifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + FloatingToolbarDefaults.ScreenOffset)
                FloatingBar(visible = state.selecting && moving == null, modifier = toolbarModifier) {
                    SelectionToolbar(
                        count = selected.size,
                        onRename = { renaming = true },
                        onMove = viewModel::beginMove,
                        onDelete = viewModel::askDelete,
                    )
                }
                FloatingBar(visible = moving != null, modifier = toolbarModifier) {
                    // Held over the exit animation, when the move is already over.
                    val move = remember(moving) { moving } ?: return@FloatingBar
                    MoveToolbar(
                        count = move.items.size,
                        destination = state.folderLabel(),
                        enabled = canMoveInto(move, state.path),
                        onCancel = viewModel::cancelMove,
                        onConfirm = {
                            haptics.confirm()
                            viewModel.confirmMoveHere()
                        },
                    )
                }
            }
        }
    }

    ExpandedFullScreenSearchBar(state = searchState, inputField = inputField) {
        SearchResults(state = state, handlers = handlers, loadPreview = viewModel, dateFormat = dateFormat)
    }
}

/** A floating toolbar rising from the bottom edge when it is needed and sinking back after. */
@Composable
private fun FloatingBar(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it * 2 } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it * 2 } + fadeOut(motion.fastEffectsSpec()),
    ) { content() }
}

/**
 * The header above a screen with nothing to scroll — loading, an error, nothing here — with the
 * message centred in the room left below it. The header stays, because the filter in it is often
 * the reason the screen is empty and has to stay within reach to be undone.
 */
@Composable
private fun HeaderAbove(padding: PaddingValues, header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    Column(
        Modifier.fillMaxSize().padding(
            top = padding.calculateTopPadding(),
            start = padding.calculateStartPadding(layoutDirection),
            end = padding.calculateEndPadding(layoutDirection),
        ),
    ) {
        header()
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(bottom = padding.calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** The search field, in the pill and full screen alike. */
@Composable
private fun SearchInputField(
    searchState: SearchBarState,
    text: androidx.compose.foundation.text.input.TextFieldState,
    placeholder: String,
    onCollapse: () -> Unit,
    onClear: () -> Unit,
) {
    val expanded = searchState.currentValue == SearchBarValue.Expanded
    SearchBarDefaults.InputField(
        textFieldState = text,
        searchBarState = searchState,
        onSearch = {},
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = {
            if (expanded) {
                IconButton(onClick = onCollapse, shapes = IconButtonDefaults.shapes()) {
                    Icon(NotesIcons.Back, contentDescription = stringResource(R.string.library_close_search))
                }
            } else {
                Icon(NotesIcons.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            if (text.text.isNotEmpty()) {
                IconButton(onClick = onClear, shapes = IconButtonDefaults.shapes()) {
                    Icon(NotesIcons.Close, contentDescription = stringResource(R.string.library_clear_search))
                }
            }
        },
    )
}

/**
 * What the full-screen search shows: the results, each saying where it lives, or a hint.
 *
 * Scoped to the folder the user is standing in, and it says so. A search that silently covered the
 * whole library while the breadcrumbs said "Work" would be answering a question nobody asked.
 */
@Composable
private fun ColumnScope.SearchResults(
    state: LibraryUiState,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
) {
    // Only inside a folder. At the root the search covers everything, which is what a search bar
    // with no folder above it already means.
    if (state.canGoUp) {
        Text(
            text = stringResource(R.string.library_search_scope_x, state.folderLabel()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
    when {
        !state.isSearching -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            EmptyState(
                icon = NotesIcons.Search,
                polygon = MaterialShapes.Cookie7Sided,
                title = stringResource(R.string.library_search_hint_title),
                body = stringResource(R.string.library_search_hint),
            )
        }

        state.visibleItems.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            EmptyState(
                icon = NotesIcons.SearchOff,
                polygon = MaterialShapes.Sunny,
                title = stringResource(R.string.library_no_matches),
                body = stringResource(R.string.library_no_matches_in_x, state.folderLabel(), state.query),
            )
        }

        else -> LibraryList(
            items = state.visibleItems,
            selection = state.selection,
            handlers = handlers,
            loadPreview = loadPreview,
            renderOrder = state.renderOrder,
            dateFormat = dateFormat,
            // Results can come from any folder beneath this one, so each says where it lives.
            showPath = true,
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        )
    }
}

/**
 * Everything above the notes that scrolls with them: the search pill, where the user is, what kind
 * of thing is shown and in what order, the tags, and an import in progress.
 */
@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    searchBar: @Composable () -> Unit,
    importingLabel: String,
    onCrumb: (Int) -> Unit,
    onSort: (NoteSort) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onTag: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        searchBar()
        Breadcrumbs(state.crumbs, onCrumb)
        ConnectedToggleRow(
            options = listOf(
                ToggleOption(LibraryFilter.ALL, stringResource(R.string.library_filter_all), NotesIcons.AllItems, NotesIcons.AllItemsFill),
                ToggleOption(LibraryFilter.FOLDERS, stringResource(R.string.library_filter_folders_short), NotesIcons.Folder, NotesIcons.FolderFill),
                ToggleOption(LibraryFilter.TEXT, stringResource(R.string.library_filter_text_short), NotesIcons.TextNote, NotesIcons.TextNoteFill),
                ToggleOption(LibraryFilter.INK, stringResource(R.string.library_filter_ink_short), NotesIcons.InkNote, NotesIcons.InkNoteFill),
            ),
            selected = state.filter,
            onSelect = onFilter,
            modifier = Modifier.padding(horizontal = 4.dp),
            dense = true,
        )
        SortAndTags(state, onSort, onTag)
        if (state.importing) {
            AppLinearProgress(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = importingLabel },
            )
        }
    }
}

/** The sort order as a chip that opens its choices, followed by one chip per tag. */
@Composable
private fun SortAndTags(state: LibraryUiState, onSort: (NoteSort) -> Unit, onTag: (String) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { sortOpen = true },
                label = { Text(state.sort.label()) },
                leadingIcon = { Icon(NotesIcons.Sort, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                trailingIcon = { Icon(NotesIcons.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
            AppMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                AppMenuGroup {
                    for (sort in NoteSort.entries) {
                        AppMenuChoiceItem(
                            text = sort.label(),
                            selected = sort == state.sort,
                            onClick = {
                                sortOpen = false
                                onSort(sort)
                            },
                        )
                    }
                }
            }
        }
        for (tag in state.allTags) {
            val active = tag in state.activeTags
            FilterChip(
                selected = active,
                onClick = { onTag(tag) },
                label = { Text(tag) },
                leadingIcon = {
                    Icon(
                        if (active) NotesIcons.TagFill else NotesIcons.Tag,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/** The line under the title: how many notes and folders are here, or how many results. */
@Composable
private fun librarySubtitle(state: LibraryUiState): String? {
    if (state.isLoading) return null
    if (state.isSearching) {
        val n = state.visibleItems.size
        return pluralStringResource(R.plurals.library_count_results, n, n)
    }
    val notes = state.items.count { it is LibraryItem.Note }
    val folders = state.items.count { it is LibraryItem.Folder }
    val notesText = pluralStringResource(R.plurals.library_count_notes, notes, notes)
    val foldersText = pluralStringResource(R.plurals.library_count_folders, folders, folders)
    return when {
        notes == 0 && folders == 0 -> stringResource(R.string.library_count_empty)
        folders == 0 -> notesText
        notes == 0 -> foldersText
        else -> "$notesText  ·  $foldersText"
    }
}

/** Which glyph and shape greet an empty screen, so each kind of "nothing" looks a little different. */
@Composable
private fun emptyLook(state: LibraryUiState): Pair<ImageVector, RoundedPolygon> = when {
    state.isSearching -> NotesIcons.SearchOff to MaterialShapes.Sunny
    state.filter == LibraryFilter.FOLDERS -> NotesIcons.Folder to MaterialShapes.Clover4Leaf
    state.filter == LibraryFilter.TEXT -> NotesIcons.TextNote to MaterialShapes.Clover4Leaf
    state.filter == LibraryFilter.INK -> NotesIcons.InkNote to MaterialShapes.Clover4Leaf
    else -> NotesIcons.BlankNote to MaterialShapes.Cookie12Sided
}

@Composable
private fun NoteSort.label(): String = stringResource(
    when (this) {
        NoteSort.MODIFIED_DESC -> R.string.library_sort_modified_desc
        NoteSort.MODIFIED_ASC -> R.string.library_sort_modified_asc
        NoteSort.TITLE_ASC -> R.string.library_sort_title_asc
        NoteSort.TITLE_DESC -> R.string.library_sort_title_desc
        NoteSort.CREATED_DESC -> R.string.library_sort_created_desc
    },
)

/**
 * "Nothing of this kind is here" as a whole sentence per filter.
 *
 * The obvious shape — one sentence with the filter's name dropped into it — only reads in English,
 * where lowercasing the label happens to produce a grammatical noun phrase.
 */
private val LibraryFilter.emptyMessage: Int
    @StringRes get() = when (this) {
        // The empty state is only reached with a filter applied, so ALL never asks for a message.
        LibraryFilter.ALL, LibraryFilter.FOLDERS -> R.string.library_filter_empty_folders
        LibraryFilter.TEXT -> R.string.library_filter_empty_text
        LibraryFilter.INK -> R.string.library_filter_empty_ink
    }

/**
 * The current folder's name for display. The root's is looked up here rather than taken from the
 * state, which is held across a configuration change and would keep the language it was made in.
 */
@Composable
private fun LibraryUiState.folderLabel(): String =
    if (canGoUp) folderName else stringResource(R.string.library_root_folder)
