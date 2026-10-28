package pl.dakil.notes.library

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.dakil.notes.data.LibraryTrash
import pl.dakil.notes.data.TrashedItem
import pl.dakil.notes.format.NoteKind

data class TrashUiState(
    val items: List<TrashedItem> = emptyList(),
    val isLoading: Boolean = true,
    /** Asked to be deleted for good, and not yet confirmed. */
    val pendingDelete: TrashedItem? = null,
    val confirmingEmpty: Boolean = false,
    /** A failure to put into words; the name it was about, if any, is [errorArg]. */
    @StringRes val errorRes: Int? = null,
    val errorArg: String? = null,
    /** The name of what was just restored, for a snackbar saying so. */
    val restored: String? = null,
)

/** Drives the trash screen; see [LibraryTrash]. */
class TrashViewModel(
    private val trash: LibraryTrash,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    fun now(): Long = clock()

    /**
     * Reads the trash again. Purging first, so an item past its thirty days is never offered for
     * restoring on a screen that has been open since before it expired.
     */
    fun load() {
        viewModelScope.launch {
            trash.purge()
            val items = runCatching { trash.list() }.getOrElse { emptyList() }
            _state.update { it.copy(items = items, isLoading = false) }
        }
    }

    fun restore(item: TrashedItem) {
        viewModelScope.launch {
            trash.restore(item).fold(
                onSuccess = { _state.update { it.copy(restored = item.displayName()) } },
                onFailure = { fail(R.string.library_error_restore_x, item.displayName()) },
            )
            load()
        }
    }

    fun askDeleteForever(item: TrashedItem) = _state.update { it.copy(pendingDelete = item) }

    fun confirmDeleteForever() {
        val item = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            trash.deleteForever(item).onFailure { fail(R.string.library_error_delete_x, item.displayName()) }
            load()
        }
    }

    fun askEmpty() = _state.update { it.copy(confirmingEmpty = true) }

    fun confirmEmpty() {
        _state.update { it.copy(confirmingEmpty = false) }
        viewModelScope.launch {
            trash.empty().onFailure { fail(R.string.trash_error_empty, null) }
            load()
        }
    }

    fun dismissDialogs() = _state.update { it.copy(pendingDelete = null, confirmingEmpty = false) }

    fun consumeMessages() = _state.update { it.copy(errorRes = null, errorArg = null, restored = null) }

    private fun fail(@StringRes res: Int, arg: String?) =
        _state.update { it.copy(errorRes = res, errorArg = arg) }

    companion object {
        fun factory(trash: LibraryTrash) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TrashViewModel(trash) as T
        }
    }
}

/** A note by its title, as the library shows it; a folder by its name. */
internal fun TrashedItem.displayName(): String = if (isDirectory) name else NoteKind.titleOf(name)
