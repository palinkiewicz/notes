package pl.dakil.notes.editor.text

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.compose.ui.text.TextRange
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
import pl.dakil.notes.data.NoteViewState
import pl.dakil.notes.data.NoteViewStates
import pl.dakil.notes.data.SaveState
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.ExportOptions
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.data.noteTitle
import pl.dakil.notes.editor.R
import pl.dakil.notes.data.Attachments
import pl.dakil.notes.data.ImageImport
import pl.dakil.notes.data.ImageInsertMode
import pl.dakil.notes.data.ImportedImage
import pl.dakil.notes.editor.markdown.DataUriImages
import pl.dakil.notes.editor.markdown.DefaultImageSource
import pl.dakil.notes.editor.markdown.ImageSource
import pl.dakil.notes.editor.markdown.MarkdownActions
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
    /** The user's saved palette, offered by the format bar's colour popup. */
    val savedColors: List<Int> = emptyList(),
    /** What the image button does when tapped. A user setting, see [ImageInsertMode]. */
    val imageInsertMode: ImageInsertMode = ImageInsertMode.DIALOG,
    /**
     * An image the user picked could not be added. Transient — shown once and dismissed — and not
     * [error], which replaces the whole editor: a picture that did not arrive is no reason to take
     * the note away.
     */
    val imageFailed: Boolean = false,
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
    private val settings: SettingsRepository,
    private val viewStates: NoteViewStates,
) : ViewModel(), ImageSource {

    private val _state = MutableStateFlow(TextNoteUiState())
    val state: StateFlow<TextNoteUiState> = _state.asStateFlow()

    /** Replaced wholesale when a different note opens, so the undo history never crosses notes. */
    var text by mutableStateOf(TextFieldState())
        private set

    private var autosave: Job? = null

    /**
     * The bytes behind an image in this note.
     *
     * `attachments/…` is relative to the folder the note is in, which only the note knows; every
     * other destination means the same wherever it is read.
     */
    override suspend fun bytes(url: String): ByteArray? {
        if (Attachments.fileNameOf(url) != null) {
            val ref = _state.value.ref ?: return null
            return repository.readAttachment(ref, url)
        }
        return DefaultImageSource.bytes(url)
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
        body = text.text.toString(),
    )

    init {
        repository.saveState
            .onEach { save -> _state.update { it.copy(saveState = save) } }
            .launchIn(viewModelScope)
        // Collected rather than read once: the setting can be changed with a note already open, and
        // a formatting bar that only notices on the next launch is one the user thinks is broken.
        settings.settings
            .onEach { app ->
                _state.update {
                    it.copy(
                        pandoc = app.pandocTextNotes,
                        imageInsertMode = app.imageInsertMode,
                        savedColors = app.savedColors,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /** How far down the open note was left, or null when that is not being remembered. */
    fun savedScroll(): Int? {
        val ref = _state.value.ref ?: return null
        if (!settings.read().rememberTextPosition) return null
        return (viewStates.get(ref) as? NoteViewState.Text)?.scroll
    }

    /** Records how far down the open note is — and nothing at all with the setting off. */
    fun rememberScroll(scroll: Int) {
        val ref = _state.value.ref ?: return
        if (settings.read().rememberTextPosition) viewStates.put(ref, NoteViewState.Text(scroll))
    }

    /** The export sheet's choices as last left, shared with ink notes. */
    fun exportOptions(): ExportOptions = settings.readExportOptions()

    fun rememberExportOptions(options: ExportOptions) = settings.writeExportOptions(options)

    fun open(ref: StoreRef) {
        if (_state.value.ref == ref && !_state.value.isLoading) return
        autosave?.cancel()
        // The setting survives the reset: it is not the note's, and the collector above only emits
        // again when it changes.
        _state.update {
            TextNoteUiState(ref = ref, isLoading = true, pandoc = it.pandoc, imageInsertMode = it.imageInsertMode)
        }

        viewModelScope.launch {
            repository.loadMarkdown(ref).fold(
                onSuccess = { markdown ->
                    val parsed = FrontmatterCodec.parse(markdown)
                    frontmatterRemainder = parsed.remainder
                    val body = withoutEmbeddedImages(ref, parsed.body)
                    text = TextFieldState(body)
                    _state.update {
                        it.copy(
                            title = ref.noteTitle(),
                            tags = parsed.tags,
                            isLoading = false,
                        )
                    }
                    if (body === parsed.body) {
                        // The frontmatter is parsed and rendered back, so the composed note need
                        // not be the file's exact bytes; it is still the file, and closing it
                        // unread must not write it.
                        repository.markUnchanged(ref, composed())
                    } else {
                        // Its images have just moved out to files; the note has to follow them.
                        repository.requestSaveMarkdown(ref, composed())
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
     * Moves images an older build embedded as base64 out into the attachments folder.
     *
     * Returns [body] itself — the same instance — when there was nothing to move, which is how the
     * caller tells "untouched" from "rewritten". A payload that cannot be stored stays where it is:
     * the note still shows it, and the next open tries again.
     */
    private suspend fun withoutEmbeddedImages(ref: StoreRef, body: String): String {
        val found = DataUriImages.find(body)
        if (found.isEmpty()) return body
        val links = HashMap<String, String>()
        for (image in found) {
            val bytes = image.bytes() ?: continue
            repository.addAttachment(ref, ImageImport.keep(bytes, image.mimeType))
                .onSuccess { links[image.uri] = it }
        }
        return if (links.isEmpty()) body else DataUriImages.replace(body, links)
    }

    /**
     * Stores [image] beside the note and puts it at the caret, on a line of its own.
     *
     * The caret is read when the file has been written, not when the picker closed: the user can
     * go on typing while a large photo is copied, and the picture lands where the caret is then.
     */
    fun insertImage(image: ImportedImage) {
        val ref = _state.value.ref ?: return
        viewModelScope.launch {
            repository.addAttachment(ref, image).fold(
                onSuccess = { link -> insertImageLink(alt = "", url = link) },
                onFailure = { imageFailed() },
            )
        }
    }

    /** A picked image could not be read or stored; see [TextNoteUiState.imageFailed]. */
    fun imageFailed() = _state.update { it.copy(imageFailed = true) }

    fun dismissImageFailure() = _state.update { it.copy(imageFailed = false) }

    /** Puts `![alt](url)` at the caret, on a line of its own. */
    fun insertImageLink(alt: String, url: String) {
        val field = text
        val result = MarkdownActions.insertImage(
            field.text.toString(),
            field.selection.start,
            field.selection.end,
            alt,
            url,
        )
        field.edit {
            replace(0, length, result.text)
            selection = TextRange(result.selectionStart, result.selectionEnd)
        }
    }

    fun setSourceMode(source: Boolean) = _state.update { it.copy(sourceMode = source) }

    /** Writes immediately, for when the editor is closing rather than pausing. */
    /** Writes immediately; the job completes once the file and the index both have the note. */
    fun flush(): Job? {
        val ref = _state.value.ref ?: return null
        val markdown = composed()
        return viewModelScope.launch { repository.flushMarkdown(ref, markdown) }
    }

    companion object {
        fun factory(
            repository: NoteRepository,
            settings: SettingsRepository,
            viewStates: NoteViewStates,
        ) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TextNoteViewModel(repository, settings, viewStates) as T
            }
    }
}
