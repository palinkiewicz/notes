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
 * Loads, saves and lists `.daknote` files, on top of whichever [NoteStore] is configured.
 *
 * Autosave is debounced rather than immediate: a stroke lands every few milliseconds while drawing,
 * and serialising the document on each one would stall the input thread. The debounce collapses a
 * burst of edits into one write once the hand pauses.
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

    private data class PendingSave(val ref: StoreRef, val note: Note)

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
                .collect { pending -> performSave(pending.ref, pending.note) }
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

    suspend fun create(
        parent: StoreRef,
        title: String,
        size: PageSize = PageSize.A4,
        background: PageBackground = PageBackground.DEFAULT,
    ): Result<Pair<StoreRef, Note>> = runCatching {
        val note = DakNote.newNote(title = title, size = size, background = background, now = clock())
        val fileName = "${title.sanitizeFileName()}.${DakNote.EXTENSION}"
        val ref = store.newChild(parent, fileName)
        writeNow(ref, note)
        ref to note
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
        saveRequests.tryEmit(PendingSave(ref, note))
    }

    /** Forces an immediate write — used when the editor is leaving the foreground. */
    suspend fun flush(ref: StoreRef, note: Note): Result<Unit> {
        if (note.readOnly) {
            _saveState.value = SaveState.ReadOnly
            return Result.success(Unit)
        }
        return performSave(ref, note)
    }

    private suspend fun performSave(ref: StoreRef, note: Note): Result<Unit> = writeLock.withLock {
        _saveState.value = SaveState.Saving
        runCatching {
            writeNow(ref, note)
        }.onSuccess {
            _saveState.value = SaveState.Saved(clock())
        }.onFailure { cause ->
            _saveState.value = SaveState.Failed(cause.message ?: "Could not save")
        }
    }

    private suspend fun writeNow(ref: StoreRef, note: Note) {
        val stamped = note.copy(
            meta = note.meta.copy(modified = clock(), revision = note.meta.revision + 1),
        )
        store.write(ref) { out -> DakNoteWriter.write(stamped, out) }
        index.put(ref, stamped)
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
            if (!entry.name.endsWith(".${DakNote.EXTENSION}")) continue
            seen += entry.ref.value
            if (index.isCurrent(entry.ref, entry.modifiedAt, entry.sizeBytes)) continue
            val note = runCatching { store.read(entry.ref) { DakNoteReader.read(it) } }.getOrNull()
                ?: continue
            index.put(entry.ref, note, entry.modifiedAt, entry.sizeBytes)
            count++
        }
        index.retainOnly(dir, seen)
        count
    }

    suspend fun rename(ref: StoreRef, newTitle: String): Result<StoreRef> = runCatching {
        val fileName = "${newTitle.sanitizeFileName()}.${DakNote.EXTENSION}"
        val moved = store.move(ref, StoreRef(fileName))
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
