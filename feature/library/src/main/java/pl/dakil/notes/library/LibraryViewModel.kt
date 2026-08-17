package pl.dakil.notes.library

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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.data.NoteIndex
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.NoteStore
import pl.dakil.notes.data.NoteSummary
import pl.dakil.notes.data.StoreEntry
import pl.dakil.notes.data.StoreRef

/** One level of the folder hierarchy, kept as a stack so "up" is always unambiguous. */
@Immutable
data class Crumb(val ref: StoreRef, val name: String)

@Immutable
data class LibraryUiState(
    val crumbs: List<Crumb> = emptyList(),
    val folders: List<StoreEntry> = emptyList(),
    val notes: List<NoteSummary> = emptyList(),
    val allTags: List<String> = emptyList(),
    val activeTags: Set<String> = emptySet(),
    val sort: NoteSort = NoteSort.MODIFIED_DESC,
    val query: String = "",
    val searchResults: List<NoteSummary>? = null,
    val selectedNote: StoreRef? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val current: StoreRef? get() = crumbs.lastOrNull()?.ref
    val canGoUp: Boolean get() = crumbs.size > 1
    /** Search results replace the folder listing while a query is active. */
    val visibleNotes: List<NoteSummary> get() = searchResults ?: notes
    val isSearching: Boolean get() = searchResults != null
}

/**
 * Drives the note browser.
 *
 * Listing comes from the SQLite index rather than from the files, so opening a folder of hundreds
 * of notes never deserialises a single stroke. The index is refreshed against the store in the
 * background, and because it is a derived cache a stale row is a cosmetic problem rather than a
 * correctness one.
 */
class LibraryViewModel(
    private val store: NoteStore,
    private val index: NoteIndex,
    private val repository: NoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        @OptIn(FlowPreview::class)
        queryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)
    }

    fun start() {
        viewModelScope.launch {
            val root = store.root()
            if (root == null) {
                _state.update { it.copy(isLoading = false, error = "No note folder is configured yet.") }
                return@launch
            }
            _state.update { it.copy(crumbs = listOf(Crumb(root, "Notes"))) }
            refresh()
        }
    }

    fun refresh() {
        val current = _state.value.current ?: return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.refreshIndex(current)
            reload()
        }
    }

    private suspend fun reload() {
        val current = _state.value.current ?: return
        val entries = runCatching { store.list(current) }.getOrDefault(emptyList())
        val folders = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val notes = index.list(current, _state.value.sort, _state.value.activeTags)
        val tags = index.allTags()
        _state.update {
            it.copy(
                folders = folders,
                notes = notes,
                allTags = tags,
                isLoading = false,
                error = null,
            )
        }
    }

    // ---- Navigation ----------------------------------------------------------------------------

    fun openFolder(entry: StoreEntry) {
        _state.update { it.copy(crumbs = it.crumbs + Crumb(entry.ref, entry.name), isLoading = true) }
        refresh()
    }

    fun goUp() {
        if (!_state.value.canGoUp) return
        _state.update { it.copy(crumbs = it.crumbs.dropLast(1), isLoading = true) }
        refresh()
    }

    fun goToCrumb(index: Int) {
        _state.update { it.copy(crumbs = it.crumbs.take(index + 1), isLoading = true) }
        refresh()
    }

    fun selectNote(ref: StoreRef?) = _state.update { it.copy(selectedNote = ref) }

    // ---- Filtering -----------------------------------------------------------------------------

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    private suspend fun runSearch(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = null) }
            return
        }
        val results = index.search(query)
        _state.update { it.copy(searchResults = results) }
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

    // ---- Mutations -----------------------------------------------------------------------------

    fun createNote(title: String, kind: NoteKind, onCreated: (StoreRef) -> Unit) {
        val parent = _state.value.current ?: return
        viewModelScope.launch {
            repository.create(parent, title.ifBlank { "Untitled" }, kind).fold(
                onSuccess = { ref ->
                    reload()
                    onCreated(ref)
                },
                onFailure = { cause ->
                    _state.update { it.copy(error = cause.message ?: "Could not create the note") }
                },
            )
        }
    }

    fun createFolder(name: String) {
        val parent = _state.value.current ?: return
        viewModelScope.launch {
            runCatching { store.createDirectory(parent, name) }
                .onSuccess { reload() }
                .onFailure { cause ->
                    _state.update { it.copy(error = cause.message ?: "Could not create the folder") }
                }
        }
    }

    fun deleteNote(ref: StoreRef) {
        viewModelScope.launch {
            repository.delete(ref)
            if (_state.value.selectedNote == ref) selectNote(null)
            reload()
        }
    }

    fun renameNote(ref: StoreRef, title: String) {
        viewModelScope.launch {
            repository.rename(ref, title)
            reload()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        /** Long enough to avoid a query per keystroke, short enough to feel live. */
        const val SEARCH_DEBOUNCE_MS = 180L

        fun factory(store: NoteStore, index: NoteIndex, repository: NoteRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(store, index, repository) as T
            }
    }
}
