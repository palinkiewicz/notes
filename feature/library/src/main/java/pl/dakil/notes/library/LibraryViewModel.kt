package pl.dakil.notes.library

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.data.NoteIndex
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.NoteRepository.Companion.childPath
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.NoteStore
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.model.Sheet

/** One level of the folder hierarchy, kept as a stack so "up" is always unambiguous. */
@Immutable
data class Crumb(val ref: StoreRef, val name: String)

@Immutable
data class LibraryUiState(
    val crumbs: List<Crumb> = emptyList(),
    val items: List<LibraryItem> = emptyList(),
    val allTags: List<String> = emptyList(),
    val activeTags: Set<String> = emptySet(),
    val sort: NoteSort = NoteSort.MODIFIED_DESC,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val layout: LibraryLayout = LibraryLayout.CARDS,
    val query: String = "",
    val searchResults: List<LibraryItem>? = null,
    val selection: Set<StoreRef> = emptySet(),
    val moving: MoveRequest? = null,
    /** Items the user has asked to delete and not yet confirmed. */
    val pendingDelete: List<LibraryItem>? = null,
    val isLoading: Boolean = true,
    /**
     * A failure message that came up from the store or the file format.
     *
     * Those layers are pure JVM and have no resources, so their text arrives already written. Any
     * message this view model produces itself uses [errorRes] instead, which the screen resolves.
     */
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
    /** The one substitution [errorRes] may take — the name of the item a failure was about. */
    val errorArg: String? = null,
) {
    val current: StoreRef? get() = crumbs.lastOrNull()?.ref

    /**
     * The current folder as a logical path from the root — `""`, `"Work"`, `"Work/Q3"`.
     *
     * The root crumb is a label rather than a folder ("Notes"), so it is dropped: the path has to
     * be what the scanner built the index with, and the scanner starts counting below the root.
     */
    val path: String get() = crumbs.drop(1).joinToString("/") { it.name }

    val folderName: String get() = crumbs.lastOrNull()?.name.orEmpty()
    val canGoUp: Boolean get() = crumbs.size > 1

    /** Search results replace the folder listing while a query is active. */
    val visibleItems: List<LibraryItem> get() = searchResults ?: items
    val isSearching: Boolean get() = searchResults != null
    val selecting: Boolean get() = selection.isNotEmpty()
    val selectedItems: List<LibraryItem> get() = visibleItems.filter { it.ref in selection }
}

/**
 * Drives the note browser.
 *
 * Listing comes from the SQLite index rather than from the files, so opening a folder of hundreds
 * of notes never deserialises a single stroke. The index is refreshed against the store in the
 * background, and because it is a derived cache a stale row is a cosmetic problem rather than a
 * correctness one.
 *
 * Folders are the exception: they are read straight from the store on every navigation. Indexing
 * them would put a second answer to "what folders are there" next to the one the filesystem already
 * gives for free, and a listing of one directory is a single cheap call.
 */
class LibraryViewModel(
    private val store: NoteStore,
    private val index: NoteIndex,
    private val repository: NoteRepository,
    settings: SettingsRepository,
    /** What the root of the library is called on screen. A label, not a folder on disk. */
    private val rootFolderName: String,
    /** What a note created without a name is called. */
    private val untitledName: String,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState(layout = settings.read().libraryLayout))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    /**
     * Parsed first pages, keyed by ref and the file timestamp they were read at.
     *
     * A card shows what is actually drawn on the note, and the only place that lives is the file —
     * the index deliberately holds no geometry. Bounded because a library can be long and a sheet
     * of dense handwriting is not small; the cards ask for one at a time as they scroll into view.
     */
    private val previews = object : LinkedHashMap<String, Pair<Long, Sheet>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Sheet>>) =
            size > PREVIEW_CACHE_SIZE
    }

    init {
        @OptIn(FlowPreview::class)
        queryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)

        settings.settings
            .map { it.libraryLayout }
            .distinctUntilChanged()
            .onEach { layout -> _state.update { it.copy(layout = layout) } }
            .launchIn(viewModelScope)
    }

    fun start() {
        viewModelScope.launch {
            val root = store.root()
            if (root == null) {
                _state.update {
                    it.copy(isLoading = false, errorRes = R.string.library_error_no_root)
                }
                return@launch
            }
            _state.update { it.copy(crumbs = listOf(Crumb(root, rootFolderName))) }
            refresh()
            // The whole tree, once, so a search from the root reaches a note in a folder nobody has
            // opened yet. Unchanged files are only stat'ed, so this stays cheap after the first run.
            repository.refreshIndex(root, "", recursive = true)
            reload()
        }
    }

    fun refresh() {
        val current = _state.value.current ?: return
        val path = _state.value.path
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.refreshIndex(current, path)
            reload()
        }
    }

    private suspend fun reload() {
        val state = _state.value
        val current = state.current ?: return
        val entries = runCatching { store.list(current) }.getOrDefault(emptyList())
        val notes = index.list(state.path, state.sort, state.activeTags)
        val items = LibraryListing.build(
            folders = entries.filter { it.isDirectory },
            notes = notes,
            path = state.path,
            filter = state.filter,
            sort = state.sort,
        )
        val tags = index.allTags()
        _state.update {
            it.copy(
                items = items,
                allTags = tags,
                // A row that has gone cannot stay selected, or the app bar counts things that are
                // no longer there and offers to delete them again.
                selection = it.selection.intersect(items.mapTo(HashSet()) { item -> item.ref }),
                isLoading = false,
                error = null,
            )
        }
    }

    // ---- Navigation ----------------------------------------------------------------------------

    fun openFolder(folder: LibraryItem.Folder) {
        _state.update {
            it.copy(
                crumbs = it.crumbs + Crumb(folder.ref, folder.name),
                selection = emptySet(),
                isLoading = true,
            )
        }
        refresh()
    }

    fun goUp() {
        if (!_state.value.canGoUp) return
        _state.update { it.copy(crumbs = it.crumbs.dropLast(1), selection = emptySet(), isLoading = true) }
        refresh()
    }

    fun goToCrumb(index: Int) {
        _state.update { it.copy(crumbs = it.crumbs.take(index + 1), selection = emptySet(), isLoading = true) }
        refresh()
    }

    // ---- Searching and filtering ---------------------------------------------------------------

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    /**
     * Drops the query when the search bar closes.
     *
     * Without this, collapsing the bar with text still in it left the results rendered underneath
     * the breadcrumbs, where they read as the contents of a folder they are mostly not in.
     */
    fun closeSearch() = setQuery("")

    private suspend fun runSearch(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = null) }
            return
        }
        val state = _state.value
        val results = index.search(query, state.path).map { LibraryItem.Note(it) }
        _state.update { it.copy(searchResults = results, selection = emptySet()) }
    }

    fun toggleTag(tag: String) {
        _state.update { current ->
            val active = current.activeTags.toMutableSet()
            if (!active.add(tag)) active.remove(tag)
            current.copy(activeTags = active)
        }
        viewModelScope.launch { reload() }
    }

    fun setSort(sort: NoteSort) {
        _state.update { it.copy(sort = sort) }
        viewModelScope.launch { reload() }
    }

    fun setFilter(filter: LibraryFilter) {
        _state.update { it.copy(filter = filter) }
        viewModelScope.launch { reload() }
    }

    // ---- Selection -----------------------------------------------------------------------------

    fun toggleSelection(item: LibraryItem) = _state.update {
        val selection = it.selection.toMutableSet()
        if (!selection.add(item.ref)) selection.remove(item.ref)
        it.copy(selection = selection)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // ---- Mutations -----------------------------------------------------------------------------

    fun createNote(title: String, kind: NoteKind, onCreated: (StoreRef) -> Unit) {
        val parent = _state.value.current ?: return
        val path = _state.value.path
        viewModelScope.launch {
            repository.create(parent, title.ifBlank { untitledName }, kind, path = path).fold(
                onSuccess = { ref ->
                    reload()
                    onCreated(ref)
                },
                onFailure = { cause -> fail(cause, R.string.library_error_create_note) },
            )
        }
    }

    fun createFolder(name: String) {
        val parent = _state.value.current ?: return
        viewModelScope.launch {
            runCatching { store.createDirectory(parent, name) }
                .onSuccess { reload() }
                .onFailure { cause -> fail(cause, R.string.library_error_create_folder) }
        }
    }

    /** Renames the single selected item, folder or note, and leaves selection behind. */
    fun renameSelected(newName: String) {
        val item = _state.value.selectedItems.singleOrNull() ?: return
        viewModelScope.launch {
            val result = when (item) {
                is LibraryItem.Folder -> repository.renameFolder(
                    item.ref,
                    newName,
                    childPath(item.path, item.name),
                )

                is LibraryItem.Note -> repository.rename(item.ref, newName, item.path)
            }
            result.onFailure { cause -> fail(cause, R.string.library_error_rename) }
            clearSelection()
            if (item is LibraryItem.Folder) {
                // The rename dropped the folder's whole subtree from the index; only a recursive
                // scan puts back the notes that are more than one level down.
                val current = _state.value.current
                if (current != null) repository.refreshIndex(current, _state.value.path, recursive = true)
            }
            reload()
        }
    }

    fun askDelete() {
        val items = _state.value.selectedItems
        if (items.isNotEmpty()) _state.update { it.copy(pendingDelete = items) }
    }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val items = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null, selection = emptySet()) }
        viewModelScope.launch {
            for (item in items) {
                repository.delete(item.ref, item.path, (item as? LibraryItem.Folder)?.name)
                    .onFailure { cause -> fail(cause, R.string.library_error_delete_x, item.name) }
            }
            reload()
        }
    }

    // ---- Moving --------------------------------------------------------------------------------

    /**
     * Starts a move, leaving the browser navigable so the user can go and find the destination.
     *
     * A picker dialog would have to grow its own tree view of the same folders that are already on
     * screen; letting the library itself be the picker means there is one folder browser in the app
     * and it behaves the same both times.
     */
    fun beginMove() {
        val state = _state.value
        val items = state.selectedItems
        val parent = state.current ?: return
        if (items.isEmpty()) return
        viewModelScope.launch {
            // Items found by a search can live anywhere; each has to be detached from its own
            // folder, so those are resolved to real refs before the browsing starts.
            val parents = items.associate { it.ref to (resolveFolder(it.path) ?: parent) }
            _state.update {
                it.copy(
                    moving = MoveRequest(items, parents),
                    selection = emptySet(),
                    query = "",
                    searchResults = null,
                )
            }
        }
    }

    fun cancelMove() = _state.update { it.copy(moving = null) }

    fun confirmMoveHere() {
        val state = _state.value
        val request = state.moving ?: return
        val target = state.current ?: return
        val toPath = state.path
        _state.update { it.copy(moving = null, isLoading = true) }
        viewModelScope.launch {
            for (item in request.items) {
                val from = request.parents[item.ref] ?: continue
                repository.moveTo(
                    ref = item.ref,
                    fromParent = from,
                    toParent = target,
                    fromPath = item.path,
                    toPath = toPath,
                    directoryName = (item as? LibraryItem.Folder)?.name,
                ).onFailure { cause -> fail(cause, R.string.library_error_move_x, item.name) }
            }
            // The destination is rescanned rather than patched: a moved folder brought a whole
            // subtree of index rows with it, and those were dropped rather than rewritten.
            repository.refreshIndex(target, toPath, recursive = true)
            reload()
        }
    }

    /** Walks the store from the root along [path]. Returns null if any segment has gone. */
    private suspend fun resolveFolder(path: String): StoreRef? {
        var ref = store.root() ?: return null
        if (path.isEmpty()) return ref
        for (segment in path.split('/')) {
            ref = store.child(ref, segment) ?: return null
        }
        return ref
    }

    // ---- Previews ------------------------------------------------------------------------------

    /**
     * The first page of an ink note, for a card to draw.
     *
     * Returns null rather than throwing for a note that cannot be read: a card with blank paper on
     * it is a better answer than a crash, and the note itself still opens.
     */
    suspend fun loadPreview(note: LibraryItem.Note): Sheet? {
        if (note.summary.kind != NoteKind.INK) return null
        val key = note.ref.value
        val stampedAt = note.modifiedAt
        synchronized(previews) {
            previews[key]?.let { (stamp, sheet) -> if (stamp == stampedAt) return sheet }
        }
        val sheet = repository.load(note.ref).getOrNull()?.sheet ?: return null
        synchronized(previews) { previews[key] = stampedAt to sheet }
        return sheet
    }

    fun dismissError() =
        _state.update { it.copy(error = null, errorRes = null, errorArg = null) }

    /**
     * Records a failure.
     *
     * A message that came up from the store says something specific about which file failed and why,
     * so it wins; [fallback] is what the user sees when the cause has nothing to say.
     */
    private fun fail(cause: Throwable, @StringRes fallback: Int, arg: String? = null) =
        _state.update {
            val message = cause.message
            if (message != null) {
                it.copy(error = message, errorRes = null, errorArg = null)
            } else {
                it.copy(error = null, errorRes = fallback, errorArg = arg)
            }
        }

    companion object {
        /** Long enough to avoid a query per keystroke, short enough to feel live. */
        const val SEARCH_DEBOUNCE_MS = 180L

        /** Roughly two screens of cards, so scrolling back up never re-reads a file. */
        private const val PREVIEW_CACHE_SIZE = 48

        /**
         * [rootFolderName] and [untitledName] are passed in already translated: this view model has
         * no `Context`, and both end up as text the user reads.
         */
        fun factory(
            store: NoteStore,
            index: NoteIndex,
            repository: NoteRepository,
            settings: SettingsRepository,
            rootFolderName: String,
            untitledName: String,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(
                    store,
                    index,
                    repository,
                    settings,
                    rootFolderName,
                    untitledName,
                ) as T
        }
    }
}
