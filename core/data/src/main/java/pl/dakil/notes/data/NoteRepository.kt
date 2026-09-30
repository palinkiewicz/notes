package pl.dakil.notes.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import pl.dakil.notes.format.FrontmatterCodec
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.NoteMeta
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.sync.ConflictNaming
import java.util.concurrent.ConcurrentHashMap

/** What the UI needs to know about the save state, so it can be honest rather than reassuring. */
sealed interface SaveState {
    data object Idle : SaveState
    data object Pending : SaveState
    data object Saving : SaveState
    data class Saved(val atMs: Long) : SaveState
    data class Failed(val message: String) : SaveState
    /** The note declares a `minReaderVersion` this build does not implement. */
    data object ReadOnly : SaveState

    /**
     * The file changed underneath the editor, and the version found there was kept aside under
     * [copyName] before this save went over it.
     *
     * Reported rather than silently resolved: once the library lives in a folder Syncthing or a
     * cloud client also writes to, this stops being a theoretical race, and a user who is not told
     * has no way to know a copy is waiting for them.
     */
    data class Conflicted(val copyName: String) : SaveState
}

/**
 * What the repository last saw on disk for a note, so it can tell whether anyone else has been
 * there since.
 *
 * Size and timestamp rather than a hash: this runs immediately before every save, and re-reading
 * the whole note to hash it would put a file read on the path of every keystroke's debounce.
 */
data class FileStamp(val modifiedAt: Long, val sizeBytes: Long)

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
    /** Names the conflict copies this device makes; see [ConflictNaming]. */
    private val deviceId: () -> String = { "LOCAL" },
) {

    private val writeLock = Mutex()

    /**
     * The stamp each open note had when this repository last read or wrote it.
     *
     * Not guarded by [writeLock], because [load] runs outside it — the editor opens a note without
     * taking the save lock, and making it wait behind an unrelated in-flight write would stall the
     * screen for no reason. A concurrent map is enough: entries are independent and the only
     * comparison that matters happens inside the lock.
     */
    private val lastKnown = ConcurrentHashMap<String, FileStamp>()

    /**
     * Where the note being saved has moved to since its write was queued, as `from to to`.
     *
     * A rename can land in the gap between the last keystroke and the debounce firing, and the
     * queued write still carries the ref the note had then. Writing to it would recreate the old
     * file, content and all, next to the renamed one. One entry is enough because [saveRequests]
     * conflates: there is never more than one write waiting. Guarded by [writeLock].
     */
    private var pendingMove: Pair<StoreRef, StoreRef>? = null

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: Flow<SaveState> = _saveState.asStateFlow()

    private val _openRef = MutableStateFlow<StoreRef?>(null)

    /**
     * The note an editor currently has open, or null.
     *
     * Sync pushes it and never pulls it: writing under an open document either loses the edits in
     * flight or is clobbered by the autosave a second and a half later.
     */
    val openRef: StateFlow<StoreRef?> = _openRef.asStateFlow()

    fun noteOpened(ref: StoreRef) { _openRef.value = ref }

    fun noteClosed(ref: StoreRef) { _openRef.compareAndSet(ref, null) }

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
        remember(ref)
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
        remember(ref)
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
        path: String = "",
    ): Result<StoreRef> = runCatching {
        val ref = store.newChild(parent, "${title.sanitizeFileName()}.${kind.extension}", kind.mimeType)
        when (kind) {
            NoteKind.INK -> writeInk(
                ref,
                DakNote.newNote(title = title, size = size, background = background, now = clock()),
                path,
            )
            // Deliberately empty rather than seeded with "# $title": the file name is already the
            // title, and a heading the user did not type is one they have to delete.
            NoteKind.TEXT -> writeText(ref, "", path)
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
        val ref = pendingMove?.takeIf { it.first == pending.ref }?.second ?: pending.ref
        // Applied or superseded either way: nothing older than this write is still queued behind
        // it, so keeping the redirect could only misdirect a save of some future note.
        pendingMove = null
        var preserved: String? = null
        runCatching {
            preserved = preserveIfChangedUnderUs(ref)
            when (pending) {
                is PendingSave.Ink -> writeInk(ref, pending.note)
                is PendingSave.Text -> writeText(ref, pending.markdown)
            }
        }.onSuccess {
            val copy = preserved
            _saveState.value = if (copy != null) SaveState.Conflicted(copy) else SaveState.Saved(clock())
        }.onFailure { cause ->
            _saveState.value = SaveState.Failed(cause.message ?: "Could not save")
        }
    }

    /**
     * Copies the version on disk aside if it is not the one this repository last saw.
     *
     * Before this existed a save wrote unconditionally, so anything that touched the file between
     * opening the note and the debounce firing was overwritten without a word. That was survivable
     * while notes lived in app-private storage, where nothing else could reach them. It is not
     * survivable in a folder the user chose — which is the whole point of letting them choose one.
     *
     * Returns the name of the copy that was made, or null when nothing had changed.
     *
     * A save is **not** abandoned when the copy cannot be placed: the note the user is typing in
     * has to be able to reach the disk. The external version is left where it is instead, and the
     * failure is reported, which loses nothing that was not already only on disk.
     */
    private suspend fun preserveIfChangedUnderUs(ref: StoreRef): String? {
        val known = lastKnown[ref.value] ?: return null
        val current = store.metadata(ref) ?: return null
        if (current.modifiedAt == known.modifiedAt && current.sizeBytes == known.sizeBytes) return null

        val name = ConflictNaming.nameFor(current.name, clock(), deviceId())
        val target = store.siblingOf(ref, name) ?: return null
        return runCatching {
            val bytes = store.read(ref) { it.readBytes() }
            store.write(target) { out -> out.write(bytes) }
            store.metadata(target)?.name ?: name
        }.getOrNull()
    }

    /** Records what is on disk now, so the next save can tell whether anyone else has been there. */
    private suspend fun remember(ref: StoreRef) {
        val entry = store.metadata(ref) ?: return
        lastKnown[ref.value] = FileStamp(entry.modifiedAt, entry.sizeBytes)
    }

    private suspend fun writeInk(ref: StoreRef, note: Note, path: String? = null) {
        val stamped = note.copy(
            meta = note.meta.copy(modified = clock(), revision = note.meta.revision + 1),
        )
        store.write(ref) { out -> DakNoteWriter.write(stamped, out) }
        remember(ref)
        index.put(ref, stamped, path ?: index.pathOf(ref))
    }

    /**
     * Writes a `.md` note.
     *
     * Nothing is stamped, because there is nowhere to stamp it: no manifest, no revision counter.
     * The file's own timestamp is the modification time, which is also what any other editor
     * touching the same file would leave behind.
     */
    private suspend fun writeText(ref: StoreRef, markdown: String, path: String? = null) {
        store.write(ref) { out -> out.write(markdown.toByteArray(Charsets.UTF_8)) }
        remember(ref)
        indexText(ref, markdown, store.metadata(ref), path ?: index.pathOf(ref))
    }

    private suspend fun indexText(ref: StoreRef, markdown: String, entry: StoreEntry?, path: String) {
        val name = entry?.name ?: ref.value.substringAfterLast('/')
        val modified = entry?.modifiedAt ?: clock()
        // A `.md` note keeps its tags in YAML frontmatter, because it has no manifest to keep them
        // in and frontmatter is what every other Markdown tool already reads.
        val parsed = FrontmatterCodec.parse(markdown)
        index.put(
            ref = ref,
            path = path,
            kind = NoteKind.TEXT,
            // A plain Markdown file has no id of its own; its location is the only stable handle.
            noteId = ref.value,
            title = NoteKind.titleOf(name),
            tags = parsed.tags,
            created = modified,
            modified = modified,
            // The block is metadata, not prose: indexing it would make every tagged note a hit for
            // the word "tags".
            body = parsed.body,
            fileModified = modified,
            fileSize = entry?.sizeBytes ?: markdown.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    // ---- Applying a sync ------------------------------------------------------------------------

    /**
     * Runs [body] under the same write lock every save takes.
     *
     * Per file operation, never for a whole pass: holding it across a network round trip would put
     * autosave — and every rename, move and tag edit, which share the lock — behind the Wi-Fi.
     */
    private suspend fun <T> underWriteLock(body: suspend () -> T): T = writeLock.withLock { body() }

    /**
     * Waits for any debounced save to land.
     *
     * Called once at the start of a sync so a note the user edited seconds before the job fired is
     * uploaded as they left it, rather than a version and a half.
     */
    suspend fun awaitIdle(timeoutMs: Long = 5_000L) {
        val deadline = clock() + timeoutMs
        while (_saveState.value is SaveState.Pending && clock() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        // Taking the lock is the actual wait: whatever the debounce started is holding it.
        writeLock.withLock { }
    }

    /** Overwrites an existing note with bytes pulled from a remote. */
    suspend fun applyRemoteWrite(ref: StoreRef, body: (java.io.OutputStream) -> Unit): Boolean =
        underWriteLock {
            runCatching {
                store.write(ref, body)
                remember(ref)
            }.isSuccess
        }

    /**
     * Creates a note at an **exact** name.
     *
     * Not [NoteStore.newChild], which steps aside to `Foo (2).md` when the name is taken — right for
     * a note the user is making, catastrophic for a pull, where it would silently fork one note into
     * two that then sync against each other forever.
     */
    suspend fun applyRemoteCreate(
        parent: StoreRef,
        name: String,
        body: (java.io.OutputStream) -> Unit,
    ): StoreRef? = underWriteLock {
        runCatching {
            val existing = store.child(parent, name)
            val ref = existing ?: store.newChild(parent, name, mimeTypeOf(name))
            // `createDocument` is allowed to alter the display name, so it is read back rather than
            // assumed: a note that landed somewhere else is not the note that was pulled.
            store.write(ref, body)
            val landed = store.metadata(ref)
            if (landed != null && landed.name != name) return@runCatching null
            remember(ref)
            ref
        }.getOrNull()
    }

    suspend fun applyRemoteDelete(ref: StoreRef): Boolean = underWriteLock {
        runCatching {
            store.delete(ref)
            lastKnown.remove(ref.value)
            index.remove(ref)
        }.isSuccess
    }

    suspend fun applyRemoteMove(
        ref: StoreRef,
        fromParent: StoreRef,
        toParent: StoreRef,
        newName: String,
    ): StoreRef? = underWriteLock {
        runCatching {
            val moved = if (fromParent == toParent) ref else store.moveTo(ref, fromParent, toParent)
            val renamed = if (store.metadata(moved)?.name != newName) {
                store.move(moved, StoreRef(newName))
            } else {
                moved
            }
            index.move(ref, renamed, index.pathOf(renamed))
            remember(renamed)
            renamed
        }.getOrNull()
    }

    private fun mimeTypeOf(name: String): String =
        NoteKind.of(name)?.mimeType ?: "application/octet-stream"

    // ---- Library -------------------------------------------------------------------------------

    /**
     * Scans [dir] and refreshes the index, optionally descending into its subfolders.
     *
     * Only the manifest is parsed for entries whose size and timestamp are unchanged, so opening
     * the library does not deserialise every stroke on disk — and so a whole-tree pass costs little
     * more than a directory walk once the first one has run.
     *
     * [path] is [dir]'s logical path from the library root, which the walk carries down to its
     * children. The scanner is the only thing that knows it: a ref cannot be asked, because a SAF
     * ref is an opaque document URI.
     *
     * [recursive] is what search needs. A folder-scoped search is a search of that folder *and
     * everything under it*, so a note in a subfolder the user has never opened still has to be in
     * the index. Navigation asks for the shallow scan, which is instant, and the recursive one runs
     * from the root in the background.
     */
    suspend fun refreshIndex(
        dir: StoreRef,
        path: String = "",
        recursive: Boolean = false,
    ): Result<Int> = runCatching {
        val visited = HashSet<String>()
        val result = scan(dir, path, recursive, visited)
        // A folder that has gone since the last pass leaves its rows behind, and nothing in a
        // per-folder retainOnly would ever reach them.
        //
        // Only when the whole walk could be read, though: a subtree the backend refused is missing
        // from `visited` for a reason that has nothing to do with the user deleting it, and purging
        // on that evidence empties the library's index over a lapsed permission.
        if (recursive && result.complete) index.retainPaths(path, visited)
        result.count
    }

    /**
     * What a walk found, and whether it could see all of it.
     *
     * [complete] is false as soon as any directory in the subtree could not be listed. It is
     * deliberately sticky: a purge is only safe when *everything* was enumerated, so one refusal
     * anywhere disarms the purge for the whole pass rather than for one folder.
     */
    private data class ScanResult(val count: Int, val complete: Boolean)

    private suspend fun scan(
        dir: StoreRef,
        path: String,
        recursive: Boolean,
        visited: MutableSet<String>,
    ): ScanResult {
        visited += path
        // "Could not read this folder" and "this folder is empty" are the same value from `list`,
        // and the two call for opposite responses: the second means purge, the first means do not
        // touch a thing. Asking through `listChecked` is what keeps a revoked SAF grant from
        // emptying the index.
        val entries = when (val listing = store.listChecked(dir)) {
            is StoreListing.Ok -> listing.entries
            is StoreListing.Unavailable -> return ScanResult(count = 0, complete = false)
        }
        var count = 0
        var complete = true
        val seen = HashSet<String>()
        val folders = ArrayList<StoreEntry>()
        for (entry in entries) {
            if (entry.isDirectory) {
                if (recursive) folders += entry
                continue
            }
            val kind = NoteKind.of(entry.name) ?: continue
            // Must happen before the isCurrent shortcut: retainOnly below purges everything the
            // scan did not report, current or not.
            seen += entry.ref.value
            if (index.isCurrent(entry.ref, entry.modifiedAt, entry.sizeBytes)) continue
            when (kind) {
                NoteKind.INK -> {
                    val note = runCatching { store.read(entry.ref) { DakNoteReader.read(it) } }
                        .getOrNull() ?: continue
                    index.put(entry.ref, note, path, entry.modifiedAt, entry.sizeBytes)
                }

                NoteKind.TEXT -> {
                    val markdown = runCatching {
                        store.read(entry.ref) { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull() ?: continue
                    indexText(entry.ref, markdown, entry, path)
                }
            }
            count++
        }
        index.retainOnly(path, seen)
        for (folder in folders) {
            val child = scan(folder.ref, childPath(path, folder.name), recursive, visited)
            count += child.count
            if (!child.complete) complete = false
        }
        return ScanResult(count, complete)
    }

    /**
     * Renames the file behind [ref], keeping whatever kind it already is.
     *
     * The extension comes from the existing name rather than a constant: renaming a `.md` note must
     * not silently turn it into something no reader can open.
     *
     * Held under the same lock as a save, because both rewrite the note's storage and the second
     * half of this — retitling an ink note's manifest — is a read-modify-write of the file a save
     * may be in the middle of replacing.
     */
    suspend fun rename(ref: StoreRef, newTitle: String, path: String? = null): Result<StoreRef> = runCatching {
        writeLock.withLock {
            val folder = path ?: index.pathOf(ref)
            val current = ref.value.substringAfterLast('/')
            val kind = NoteKind.of(current) ?: NoteKind.INK
            val title = newTitle.sanitizeFileName()
            val moved = store.move(ref, StoreRef("$title.${kind.extension}"))
            redirectPendingSave(ref, moved)
            // The store has the last word on the name — it steps aside from a collision rather
            // than overwriting — so what goes in the manifest and the index is read back from
            // where the file actually landed, not from what was asked for.
            val landed = moved.noteTitle().ifBlank { title }
            // A `.md` note is titled by its file name, so the index row can be corrected here and
            // that is the whole rename. An ink note keeps its title in its manifest, which is where
            // the index reads it from — so that file has to be rewritten, and doing so reindexes
            // the note under its new title as a side effect. Hence the null: setting the title here
            // would either be undone a line later or, for a note this build may only read, claim a
            // title the file does not have.
            index.move(ref, moved, folder, landed.takeIf { kind == NoteKind.TEXT })
            if (kind == NoteKind.INK) retitle(moved, landed, folder)
            moved
        }
    }

    /**
     * Renames a folder, and drops the index rows beneath it.
     *
     * The rows are not rewritten because they cannot be cheaply: every descendant's path changes,
     * and the index is a cache of files that are all still on disk. The caller rescans the folder
     * afterwards, which puts them back where they now live.
     */
    suspend fun renameFolder(ref: StoreRef, newName: String, path: String): Result<StoreRef> = runCatching {
        val moved = store.move(ref, StoreRef(newName.sanitizeFileName()))
        index.removeSubtree(path)
        moved
    }

    /**
     * Moves a note or a folder from one folder to another.
     *
     * Under the write lock and redirecting a queued save for the same reason [rename] is: a note
     * autosaved a moment before being moved would otherwise be written back to the path it no
     * longer has, recreating it where it used to be.
     */
    suspend fun moveTo(
        ref: StoreRef,
        fromParent: StoreRef,
        toParent: StoreRef,
        fromPath: String,
        toPath: String,
        directoryName: String? = null,
    ): Result<StoreRef> = runCatching {
        writeLock.withLock {
            val moved = store.moveTo(ref, fromParent, toParent)
            redirectPendingSave(ref, moved)
            if (directoryName != null) {
                // Its descendants' paths all changed; the destination rescan reindexes them.
                index.removeSubtree(childPath(fromPath, directoryName))
            } else {
                index.move(ref, moved, toPath)
            }
            moved
        }
    }

    /**
     * Writes [title] into an ink note's manifest, and reindexes it under the new name.
     *
     * A note this build may only read keeps the title it has: a `minReaderVersion` gate is worth
     * more than a tidy name, and the index then goes on reporting what the file actually says.
     */
    private suspend fun retitle(ref: StoreRef, title: String, path: String) {
        val note = runCatching { store.read(ref) { DakNoteReader.read(it) } }.getOrNull() ?: return
        if (note.readOnly || note.meta.title == title) return
        writeInk(ref, note.copy(meta = note.meta.copy(title = title)), path)
    }

    /** Rewrites an ink note's tags in place, without going through the editor. */
    suspend fun setTags(ref: StoreRef, tags: List<String>, path: String? = null): Result<Unit> = runCatching {
        writeLock.withLock {
            val note = store.read(ref) { DakNoteReader.read(it) }
            if (!note.readOnly) writeInk(ref, note.copy(meta = note.meta.copy(tags = tags)), path)
        }
    }

    /** Points a write queued before the rename at the file's new home; see [pendingMove]. */
    private fun redirectPendingSave(from: StoreRef, to: StoreRef) {
        // Collapse the chain so a second rename in the same breath still lands on the current file.
        val origin = pendingMove?.takeIf { it.second == from }?.first ?: from
        pendingMove = origin to to
    }

    /**
     * Deletes a note, or a folder and everything in it.
     *
     * [path] is the folder the item sits in. [directoryName] is non-null only for a folder, and is
     * the name rather than something parsed back out of [ref]: a ref is a path in one store and an
     * opaque document URI in another, so its last segment is not reliably a name at all. With it,
     * the rows for the folder's contents go when the folder does, rather than lingering as search
     * results that open nothing.
     */
    suspend fun delete(
        ref: StoreRef,
        path: String? = null,
        directoryName: String? = null,
    ): Result<Unit> = runCatching {
        store.delete(ref)
        if (directoryName != null) {
            index.removeSubtree(childPath(path ?: index.pathOf(ref), directoryName))
        } else {
            index.remove(ref)
        }
    }

    fun meta(note: Note): NoteMeta = note.meta

    /** Every tag in use across the library, most-used first — backs the tag editor's autocomplete. */
    suspend fun allTags(): List<String> = index.allTags()

    companion object {
        /** Appends one folder name to a logical path. The root is `""`, so it grows no leading slash. */
        fun childPath(parent: String, name: String): String =
            if (parent.isEmpty()) name else "$parent/$name"

        /**
         * Long enough that a burst of strokes collapses into one write, short enough that a user
         * who backgrounds the app right after writing does not notice a gap.
         */
        const val AUTOSAVE_DEBOUNCE_MS = 1_500L
    }
}
