package pl.dakil.notes.editor.text

import androidx.annotation.StringRes
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
import pl.dakil.notes.editor.R
import pl.dakil.notes.format.FrontmatterCodec

@Immutable
data class TextNoteUiState(
    val ref: StoreRef? = null,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val saveState: SaveState = SaveState.Idle,
    val isLoading: Boolean = true,
    /**
     * A failure message that came up from the store or the file format.
     *
     * Those layers are pure JVM and have no resources, so their text arrives already written; when
     * a failure has nothing to say, [errorRes] carries the fallback for the screen to resolve.
     */
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
    /** Every tag in use across the library, for the tag editor's autocomplete. */
    val knownTags: List<String> = emptyList(),
)

/**
 * Drives a plain `.md` note.
 *
 * Far smaller than [pl.dakil.notes.editor.NoteViewModel] because a text note has no document model
 * to keep in sync: the editor's own `RichTextState` *is* the document. That also means undo is the
 * editor's own — the library keeps a history that merges typing into bursts, so there is no reason
 * for this screen to run an [pl.dakil.notes.editor.EditHistory] of its own.
 *
 * The state itself lives on the screen rather than here, because a `RichTextState` is Compose
 * state that has to be created in composition. What this class keeps is the body as it was loaded
 * ([loaded]) to seed it with, and the Markdown the screen hands back on every edit ([onEdited]).
 */
class TextNoteViewModel(private val repository: NoteRepository) : ViewModel() {

    private val _state = MutableStateFlow(TextNoteUiState())
    val state: StateFlow<TextNoteUiState> = _state.asStateFlow()

    /**
     * The note's body as it came off disk, for the screen to seed its editor with.
     *
     * Paired with [documentKey], which changes only when a *different* note is opened: that is what
     * the screen re-seeds on, so reloading never happens mid-typing and the caret is never yanked
     * back to the top of the note.
     */
    var loaded by mutableStateOf("")
        private set

    var documentKey by mutableStateOf(0)
        private set

    /** The body as the editor last serialised it. What [composed] writes out. */
    private var body by mutableStateOf("")

    private var autosave: Job? = null

    /**
     * Frontmatter keys this app does not read, kept aside while the note is open.
     *
     * The field holds the note's *body* and nothing else — the block above it is metadata, and a
     * caret has no business in it. That means anything in it has to be remembered here so the file
     * goes back to disk with it, which is the same promise the `.daknote` format makes about
     * unknown JSON keys and unknown ZIP entries.
     */
    private var frontmatterRemainder: List<String> = emptyList()

    /** The bytes that belong on disk: the body the user is editing, with its metadata put back on. */
    private fun composed(): String = FrontmatterCodec.render(
        tags = _state.value.tags,
        remainder = frontmatterRemainder,
        body = body,
    )

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
                    val parsed = FrontmatterCodec.parse(markdown)
                    frontmatterRemainder = parsed.remainder
                    loaded = parsed.body
                    body = parsed.body
                    documentKey++
                    _state.update {
                        it.copy(
                            title = ref.noteTitle(),
                            tags = parsed.tags,
                            isLoading = false,
                        )
                    }
                    startAutosave()
                    refreshKnownTags()
                },
                onFailure = { cause ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = cause.message,
                            errorRes = if (cause.message == null) R.string.editor_error_open else null,
                        )
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
        autosave = snapshotFlow { body }
            .drop(1)
            .onEach {
                val ref = _state.value.ref ?: return@onEach
                repository.requestSaveMarkdown(ref, composed())
            }
            .launchIn(viewModelScope)
    }

    /**
     * Takes the Markdown the editor has just serialised.
     *
     * Called on every edit from the screen, because the document lives in the editor's state and
     * this is the only way its text reaches the part of the app that can write it to disk. The
     * equality check is what keeps a caret move — which re-serialises to the same string — from
     * touching the file.
     */
    fun onEdited(markdown: String) {
        if (markdown == body) return
        body = markdown
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

    /**
     * Replaces the note's tags, which for a `.md` note means rewriting its YAML frontmatter.
     *
     * Saved explicitly rather than left to the autosave: the field's text has not changed — the
     * tags live outside it — so nothing would ever notice.
     */
    fun setTags(tags: List<String>) {
        if (tags == _state.value.tags) return
        _state.update { it.copy(tags = tags) }
        val ref = _state.value.ref ?: return
        repository.requestSaveMarkdown(ref, composed())
        refreshKnownTags()
    }

    private fun refreshKnownTags() {
        viewModelScope.launch {
            val tags = repository.allTags()
            _state.update { it.copy(knownTags = tags) }
        }
    }

    /** Writes immediately, for when the editor is closing rather than pausing. */
    fun flush() {
        val ref = _state.value.ref ?: return
        val markdown = composed()
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
