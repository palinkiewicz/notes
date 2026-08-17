package pl.dakil.notes.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.dakil.notes.format.DakNote
import pl.dakil.notes.format.DakNoteFormatException
import pl.dakil.notes.format.DakNoteReader
import pl.dakil.notes.format.DakNoteWriter
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.NoteMeta
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageSize

/** What the UI needs to know about the save state, so it can be honest rather than reassuring. */
sealed interface SaveState {
    data object Idle : SaveState
    data object Pending : SaveState
    data object Saving : SaveState
    data class Saved(val atMs: Long) : SaveState
    data class Failed(val message: String) : SaveState
    /** The note declares a `minReaderVersion` this build does not implement. */
    data object ReadOnly : SaveState
}

/**
 * Loads, saves and lists notes of both kinds, on top of whichever [NoteStore] is configured.
 *
 * Autosave is debounced rather than immediate: a stroke lands every few milliseconds while drawing,
 * and serialising the document on each one would stall the input thread. The debounce collapses a
 * burst of edits into one write once the hand pauses.
 *
 * Both note kinds share that one pipeline — one debounce, one write lock, one [saveState] — because
 * only one note is ever open, and two independent savers would race on the same file the moment a
 * future build lets a note change kind.
 */
class NoteRepository(
    private val store: NoteStore,
    private val index: NoteIndex,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val writeLock = Mutex()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: Flow<SaveState> = _saveState.asStateFlow()

    /** What is waiting to be written. One document, either kind. */
    private sealed interface PendingSave {
        val ref: StoreRef

        data class Ink(override val ref: StoreRef, val note: Note) : PendingSave
        data class Text(override val ref: StoreRef, val markdown: String) : PendingSave
    }

    private val saveRequests = MutableSharedFlow<PendingSave>(
        replay = 1,
        // Only the latest state of a note matters; older ones are already superseded.
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            saveRequests
                .debounce(AUTOSAVE_DEBOUNCE_MS)
                .conflate()
                .collect { pending -> performSave(pending) }
        }
    }

    // ---- Loading -------------------------------------------------------------------------------

    suspend fun load(ref: StoreRef): Result<Note> = runCatching {
        store.read(ref) { DakNoteReader.read(it) }
    }.onSuccess { note ->
        _saveState.value = if (note.readOnly) SaveState.ReadOnly else SaveState.Idle
    }.recoverCatching { cause ->
        throw when (cause) {
            is DakNoteFormatException, is StoreException -> cause
            else -> StoreException("Could not open the note", cause)
        }
    }

    /** Reads a plain `.md` note. There is no format to parse — the bytes are the document. */
    suspend fun loadMarkdown(ref: StoreRef): Result<String> = runCatching {
        store.read(ref) { it.readBytes().toString(Charsets.UTF_8) }
    }.onSuccess {
        _saveState.value = SaveState.Idle
    }.recoverCatching { cause ->
        throw if (cause is StoreException) cause else StoreException("Could not open the note", cause)
    }

    suspend fun create(
        parent: StoreRef,
        title: String,
        kind: NoteKind = NoteKind.INK,
        size: PageSize = PageSize.A4,
        background: PageBackground = PageBackground.DEFAULT,
    ): Result<StoreRef> = runCatching {
        val ref = store.newChild(parent, "${title.sanitizeFileName()}.${kind.extension}", kind.mimeType)
        when (kind) {
            NoteKind.INK -> writeInk(
                ref,
                DakNote.newNote(title = title, size = size, background = background, now = clock()),
            )
            // Deliberately empty rather than seeded with "# $title": the file name is already the
            // title, and a heading the user did not type is one they have to delete.
            NoteKind.TEXT -> writeText(ref, "")
        }
        ref
    }

    // ---- Saving --------------------------------------------------------------------------------

    /**
     * Queues [note] to be written to [ref] once edits stop for [AUTOSAVE_DEBOUNCE_MS].
     *
     * Safe to call on every keystroke and every finished stroke.
     */
    fun requestSave(ref: StoreRef, note: Note) {
        if (note.readOnly) {
            _saveState.value = SaveState.ReadOnly
            return
        }
        _saveState.value = SaveState.Pending
        saveRequests.tryEmit(PendingSave.Ink(ref, note))
    }

    /** Safe to call on every keystroke; see [requestSave]. */
    fun requestSaveMarkdown(ref: StoreRef, markdown: String) {
        _saveState.value = SaveState.Pending
        saveRequests.tryEmit(PendingSave.Text(ref, markdown))
    }

    /** Forces an immediate write — used when the editor is leaving the foreground. */
    suspend fun flush(ref: StoreRef, note: Note): Result<Unit> {
        if (note.readOnly) {
            _saveState.value = SaveState.ReadOnly
            return Result.success(Unit)
        }
        return performSave(PendingSave.Ink(ref, note))
    }

    suspend fun flushMarkdown(ref: StoreRef, markdown: String): Result<Unit> =
        performSave(PendingSave.Text(ref, markdown))

    private suspend fun performSave(pending: PendingSave): Result<Unit> = writeLock.withLock {
        _saveState.value = SaveState.Saving
        runCatching {
            when (pending) {
                is PendingSave.Ink -> writeInk(pending.ref, pending.note)
                is PendingSave.Text -> writeText(pending.ref, pending.markdown)
            }
        }.onSuccess {
            _saveState.value = SaveState.Saved(clock())
        }.onFailure { cause ->
            _saveState.value = SaveState.Failed(cause.message ?: "Could not save")
        }
    }

    private suspend fun writeInk(ref: StoreRef, note: Note) {
        val stamped = note.copy(
            meta = note.meta.copy(modified = clock(), revision = note.meta.revision + 1),
        )
        store.write(ref) { out -> DakNoteWriter.write(stamped, out) }
        index.put(ref, stamped)
    }

    /**
     * Writes a `.md` note.
     *
     * Nothing is stamped, because there is nowhere to stamp it: no manifest, no revision counter.
     * The file's own timestamp is the modification time, which is also what any other editor
     * touching the same file would leave behind.
     */
    private suspend fun writeText(ref: StoreRef, markdown: String) {
        store.write(ref) { out -> out.write(markdown.toByteArray(Charsets.UTF_8)) }
        indexText(ref, markdown, store.metadata(ref))
    }

    private suspend fun indexText(ref: StoreRef, markdown: String, entry: StoreEntry?) {
        val name = entry?.name ?: ref.value.substringAfterLast('/')
        val modified = entry?.modifiedAt ?: clock()
        index.put(
            ref = ref,
            kind = NoteKind.TEXT,
            // A plain Markdown file has no id of its own; its location is the only stable handle.
            noteId = ref.value,
            title = NoteKind.titleOf(name),
            tags = emptyList(),
            created = modified,
            modified = modified,
            body = markdown,
            fileModified = modified,
            fileSize = entry?.sizeBytes ?: markdown.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    // ---- Library -------------------------------------------------------------------------------

    /**
     * Scans [dir] and refreshes the index.
     *
     * Only the manifest is parsed for entries whose size and timestamp are unchanged, so opening
     * the library does not deserialise every stroke on disk.
     */
    suspend fun refreshIndex(dir: StoreRef): Result<Int> = runCatching {
        var count = 0
        val seen = HashSet<String>()
        for (entry in store.list(dir)) {
            if (entry.isDirectory) continue
            val kind = NoteKind.of(entry.name) ?: continue
            // Must happen before the isCurrent shortcut: retainOnly below purges everything the
            // scan did not report, current or not.
            seen += entry.ref.value
            if (index.isCurrent(entry.ref, entry.modifiedAt, entry.sizeBytes)) continue
            when (kind) {
                NoteKind.INK -> {
                    val note = runCatching { store.read(entry.ref) { DakNoteReader.read(it) } }
                        .getOrNull() ?: continue
                    index.put(entry.ref, note, entry.modifiedAt, entry.sizeBytes)
                }

                NoteKind.TEXT -> {
                    val markdown = runCatching {
                        store.read(entry.ref) { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull() ?: continue
                    indexText(entry.ref, markdown, entry)
                }
            }
            count++
        }
        index.retainOnly(dir, seen)
        count
    }

    /**
     * Renames the file behind [ref], keeping whatever kind it already is.
     *
     * The extension comes from the existing name rather than a constant: renaming a `.md` note must
     * not silently turn it into something no reader can open.
     */
    suspend fun rename(ref: StoreRef, newTitle: String): Result<StoreRef> = runCatching {
        val current = ref.value.substringAfterLast('/')
        val kind = NoteKind.of(current) ?: NoteKind.INK
        val moved = store.move(ref, StoreRef("${newTitle.sanitizeFileName()}.${kind.extension}"))
        index.move(ref, moved)
        moved
    }

    suspend fun delete(ref: StoreRef): Result<Unit> = runCatching {
        store.delete(ref)
        index.remove(ref)
    }

    fun meta(note: Note): NoteMeta = note.meta

    companion object {
        /**
         * Long enough that a burst of strokes collapses into one write, short enough that a user
         * who backgrounds the app right after writing does not notice a gap.
         */
        const val AUTOSAVE_DEBOUNCE_MS = 1_500L
    }
}
