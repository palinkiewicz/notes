package pl.dakil.notes.editor.text

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.SaveState
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.data.noteTitle

@Immutable
data class TextNoteUiState(
    val ref: StoreRef? = null,
    val title: String = "",
    /** Raw Markdown in a monospace font, rather than the formatted view. */
    val sourceMode: Boolean = false,
    val saveState: SaveState = SaveState.Idle,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Drives a plain `.md` note.
 *
 * Far smaller than [pl.dakil.notes.editor.NoteViewModel] because a text note has no document model
 * to keep in sync: the text field's own [TextFieldState] *is* the document. That also means undo is
 * the field's own — `TextFieldState.undoState` already merges typing into bursts, so there is no
 * reason for this screen to run an [pl.dakil.notes.editor.EditHistory] of its own.
 */
class TextNoteViewModel(private val repository: NoteRepository) : ViewModel() {

    private val _state = MutableStateFlow(TextNoteUiState())
    val state: StateFlow<TextNoteUiState> = _state.asStateFlow()

    /** Replaced wholesale when a different note opens, so the undo history never crosses notes. */
    var text by mutableStateOf(TextFieldState())
        private set

    private var autosave: Job? = null

    init {
        repository.saveState
            .onEach { save -> _state.update { it.copy(saveState = save) } }
            .launchIn(viewModelScope)
    }

    fun open(ref: StoreRef) {
        if (_state.value.ref == ref && !_state.value.isLoading) return
        autosave?.cancel()
        _state.update { TextNoteUiState(ref = ref, isLoading = true) }

        viewModelScope.launch {
            repository.loadMarkdown(ref).fold(
                onSuccess = { markdown ->
                    text = TextFieldState(markdown)
                    _state.update {
                        it.copy(
                            title = ref.noteTitle(),
                            isLoading = false,
                        )
                    }
                    startAutosave()
                },
                onFailure = { cause ->
                    _state.update {
                        it.copy(isLoading = false, error = cause.message ?: "Could not open the note")
                    }
                },
            )
        }
    }

    private fun startAutosave() {
        // `drop(1)` skips the value snapshotFlow emits on subscription: that is the text just loaded
        // from disk, and writing it straight back would touch the file's timestamp for nothing.
        //
        // The ref is read per emission rather than captured, so a rename mid-session redirects the
        // next save instead of writing the note back to the name it no longer has.
        autosave = snapshotFlow { text.text.toString() }
            .drop(1)
            .onEach { markdown ->
                val ref = _state.value.ref ?: return@onEach
                repository.requestSaveMarkdown(ref, markdown)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Renames the note.
     *
     * A `.md` note is titled by its file name and nothing else, so this is the whole of it — but
     * the ref changes with the name, and [onRenamed] hands the new one to whoever is holding it.
     */
    fun rename(title: String, onRenamed: (StoreRef) -> Unit = {}) {
        val current = _state.value
        val ref = current.ref ?: return
        val wanted = title.trim()
        if (wanted.isEmpty() || wanted == current.title) return
        viewModelScope.launch {
            // Silent on failure by design: the title on screen still says what the file is called,
            // and `error` on this screen replaces the note with a message.
            repository.rename(ref, wanted).onSuccess { moved ->
                // The store settles the final name; a collision means it is not the one asked for.
                _state.update { it.copy(ref = moved, title = moved.noteTitle()) }
                onRenamed(moved)
            }
        }
    }

    fun setSourceMode(source: Boolean) = _state.update { it.copy(sourceMode = source) }

    /** Writes immediately, for when the editor is closing rather than pausing. */
    fun flush() {
        val ref = _state.value.ref ?: return
        val markdown = text.text.toString()
        viewModelScope.launch { repository.flushMarkdown(ref, markdown) }
    }

    companion object {
        fun factory(repository: NoteRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TextNoteViewModel(repository) as T
        }
    }
}
