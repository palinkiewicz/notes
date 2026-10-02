package pl.dakil.notes.editor.text

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.compose.ui.graphics.ImageBitmap
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
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.data.noteTitle
import pl.dakil.notes.editor.R
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.ImageRefsSupport
import pl.dakil.notes.editor.markdown.ImageRefs
import pl.dakil.notes.editor.markdown.ImageLoader
import pl.dakil.notes.format.FrontmatterCodec

@Immutable
data class TextNoteUiState(
    val ref: StoreRef? = null,
    val title: String = "",
    /** Raw Markdown in a monospace font, rather than the formatted view. */
    val sourceMode: Boolean = false,
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
    /**
     * Whether this note obeys the sizes and colours a Pandoc bracketed span can name.
     *
     * A user setting rather than a property of the note — see [AppSettings.pandocTextNotes]. It
     * rides in the UI state because it decides two things the screen shows: whether the formatting
     * bar offers the size and colour controls at all, and whether the field renders what they
     * write. Off, the tags are still hidden and still saved, so turning the setting on and off does
     * not edit anybody's file.
     */
    val pandoc: Boolean = false,
)

/**
 * Drives a plain `.md` note.
 *
 * Far smaller than [pl.dakil.notes.editor.NoteViewModel] because a text note has no document model
 * to keep in sync: the text field's own [TextFieldState] *is* the document. That also means undo is
 * the field's own — `TextFieldState.undoState` already merges typing into bursts, so there is no
 * reason for this screen to run an [pl.dakil.notes.editor.EditHistory] of its own.
 */
class TextNoteViewModel(
    private val repository: NoteRepository,
    settings: SettingsRepository,
) : ViewModel(), ImageRefsSupport {

    private val _state = MutableStateFlow(TextNoteUiState())
    val state: StateFlow<TextNoteUiState> = _state.asStateFlow()

    /** Replaced wholesale when a different note opens, so the undo history never crosses notes. */
    var text by mutableStateOf(TextFieldState())
        private set

    private var autosave: Job? = null

    /**
     * The oversized image payloads the field is not carrying.
     *
     * The field holds the note's body with each oversized payload standing in as its token; the
     * real bytes live here, and [expand] puts them back wherever the machinery is not welcome.
     * Cleared when a different note opens, so one note's images never bleed into another's. See
     * [ImageRefs].
     */
    private val imagePayloads = HashMap<String, String>()

    override fun expand(markdown: String): String = ImageRefs.expand(markdown, imagePayloads::get)

    override fun collapseForPaste(markdown: String): String =
        ImageRefs.collapseForPaste(markdown, imagePayloads::put)

    override suspend fun resolveImage(url: String, widthPx: Int): ImageBitmap? {
        val source = if (url.startsWith(ImageRefs.TOKEN_PREFIX)) {
            imagePayloads[url] ?: return null
        } else {
            url
        }
        return ImageLoader.load(source, widthPx)
    }

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
        body = expand(text.text.toString()),
    )

    init {
        repository.saveState
            .onEach { save -> _state.update { it.copy(saveState = save) } }
            .launchIn(viewModelScope)
        // Collected rather than read once: the setting can be changed with a note already open, and
        // a formatting bar that only notices on the next launch is one the user thinks is broken.
        settings.settings
            .onEach { app -> _state.update { it.copy(pandoc = app.pandocTextNotes) } }
            .launchIn(viewModelScope)
    }

    fun open(ref: StoreRef) {
        if (_state.value.ref == ref && !_state.value.isLoading) return
        autosave?.cancel()
        // The setting survives the reset: it is not the note's, and the collector above only emits
        // again when it changes.
        _state.update { TextNoteUiState(ref = ref, isLoading = true, pandoc = it.pandoc) }

        viewModelScope.launch {
            repository.loadMarkdown(ref).fold(
                onSuccess = { markdown ->
                    val parsed = FrontmatterCodec.parse(markdown)
                    frontmatterRemainder = parsed.remainder
                    // A different note is opening, so its images start empty rather than
                    // inheriting whatever the last one had; tokens the old map answered are gone
                    // with it.
                    imagePayloads.clear()
                    text = TextFieldState(ImageRefs.collapse(parsed.body, imagePayloads::put))
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
        autosave = snapshotFlow { text.text.toString() }
            .drop(1)
            .onEach {
                val ref = _state.value.ref ?: return@onEach
                repository.requestSaveMarkdown(ref, composed())
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

    /**
     * The note's Markdown with the payloads back in it.
     *
     * For the exporter and anywhere else the machinery is not welcome: the file, and anything
     * reading it, gets the real document, not the field's view of it. See [ImageRefs].
     */
    fun expandedText(): String = expand(text.text.toString())

    /**
     * The screen's device-image insert, with the payload taken aside before it reaches the field.
     *
     * The insert appends its reference definition after the caret it reports, and the collapse
     * only touches definitions, so the returned offsets stay true for the collapsed text.
     */
    fun insertBase64Image(
        text: String,
        start: Int,
        end: Int,
        alt: String,
        mimeType: String,
        base64Data: String,
    ): MarkdownActions.Result {
        val inserted = MarkdownActions.insertBase64Image(text, start, end, alt, mimeType, base64Data)
        return inserted.copy(text = ImageRefs.collapse(inserted.text, imagePayloads::put))
    }

    fun setSourceMode(source: Boolean) = _state.update { it.copy(sourceMode = source) }

    /** Writes immediately, for when the editor is closing rather than pausing. */
    fun flush() {
        val ref = _state.value.ref ?: return
        val markdown = composed()
        viewModelScope.launch { repository.flushMarkdown(ref, markdown) }
    }

    companion object {
        fun factory(repository: NoteRepository, settings: SettingsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TextNoteViewModel(repository, settings) as T
            }
    }
}
