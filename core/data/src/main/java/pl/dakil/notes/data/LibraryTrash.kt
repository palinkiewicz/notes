package pl.dakil.notes.data

import pl.dakil.notes.data.NoteRepository.Companion.childPath
import pl.dakil.notes.sync.TrashNaming

/**
 * Something in the trash: a note or a folder, where it came from, and when it went.
 *
 * [entry] is the batch folder it sits in — see [TrashNaming] — which is also the parent a move back
 * out has to name.
 */
data class TrashedItem(
    val ref: StoreRef,
    val name: String,
    val isDirectory: Boolean,
    val entry: StoreRef,
    val deletedAt: Long,
    /** The folder it was deleted from, as a library path; `""` is the root. */
    val originPath: String,
)

/**
 * The library's trash: a hidden folder at its root that a delete moves things into, and that lets
 * go of them thirty days later.
 *
 * A folder in the library rather than anywhere app-private, so it is the same on every backend —
 * the internal store and a SAF folder alike — and so sync carries it: a note deleted on the phone
 * can be brought back on the tablet. Everything about an item is in the names it is filed under;
 * see [TrashNaming] for why there is nothing else.
 *
 * Out of the index while it is in here, so a deleted note never comes up in a search, and back in
 * the moment it is restored.
 */
class LibraryTrash(
    private val store: NoteStore,
    private val indexing: Indexing,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * What the trash asks of the search index. An interface so the filing itself can be tested on
     * a plain folder, without a database.
     */
    interface Indexing {
        /** [ref] has left the library from [path]; [directoryName] is set for a folder. */
        suspend fun forget(ref: StoreRef, path: String, directoryName: String?)

        /** [folder], at [path], has something back in it. */
        suspend fun reindex(folder: StoreRef, path: String, recursive: Boolean)
    }

    /** The index the app keeps: rows dropped as an item goes, and the folder rescanned as it returns. */
    class AppIndexing(
        private val index: NoteIndex,
        private val repository: NoteRepository,
        private val viewStates: NoteViewStates?,
    ) : Indexing {
        override suspend fun forget(ref: StoreRef, path: String, directoryName: String?) {
            if (directoryName != null) {
                index.removeSubtree(childPath(path, directoryName))
            } else {
                index.remove(ref)
                viewStates?.remove(ref)
            }
        }

        override suspend fun reindex(folder: StoreRef, path: String, recursive: Boolean) {
            repository.refreshIndex(folder, path, recursive)
        }
    }

    /**
     * Moves [ref], which is in [parent] at library path [path], into the trash. [directoryName] is
     * set for a folder, as for [NoteRepository.delete].
     */
    suspend fun moveToTrash(
        ref: StoreRef,
        parent: StoreRef,
        path: String,
        directoryName: String? = null,
    ): Result<TrashedItem> = runCatching {
        val trash = trashDir(create = true) ?: throw StoreException("The library has no root to keep a trash in")
        val now = clock()
        val entryName = TrashNaming.entryName(now, path)
        val entry = store.child(trash, entryName) ?: store.createDirectory(trash, entryName)
        val moved = store.moveTo(ref, parent, entry)
        indexing.forget(ref, path, directoryName)
        TrashedItem(
            ref = moved,
            name = store.metadata(moved)?.name ?: directoryName ?: ref.fileName(),
            isDirectory = directoryName != null,
            entry = entry,
            deletedAt = now,
            originPath = path,
        )
    }

    /** Everything in the trash, most recently deleted first. */
    suspend fun list(): List<TrashedItem> {
        val trash = trashDir(create = false) ?: return emptyList()
        val items = ArrayList<TrashedItem>()
        for (batch in store.list(trash)) {
            if (!batch.isDirectory) continue
            val parsed = TrashNaming.parseEntry(batch.name) ?: continue
            for (child in store.list(batch.ref)) {
                items += TrashedItem(child.ref, child.name, child.isDirectory, batch.ref, parsed.deletedAt, parsed.originPath)
            }
        }
        return items.sortedWith(compareByDescending<TrashedItem> { it.deletedAt }.thenBy { it.name })
    }

    /**
     * Puts [item] back in the folder it was deleted from, recreating that folder if it has gone
     * since, and back into the index. Steps aside from a name taken there in the meantime, as any
     * move does, rather than overwriting what is there now.
     */
    suspend fun restore(item: TrashedItem): Result<StoreRef> = runCatching {
        val root = store.root() ?: throw StoreException("The library has no root to restore into")
        var target = root
        for (segment in item.originPath.split('/').filter { it.isNotEmpty() }) {
            target = store.child(target, segment) ?: store.createDirectory(target, segment)
        }
        val moved = store.moveTo(item.ref, item.entry, target)
        dropIfEmpty(item.entry)
        if (item.isDirectory) {
            val name = store.metadata(moved)?.name ?: item.name
            indexing.reindex(moved, childPath(item.originPath, name), recursive = true)
        } else {
            indexing.reindex(target, item.originPath, recursive = false)
        }
        moved
    }

    /** Deletes [item] for good, ahead of its thirty days. */
    suspend fun deleteForever(item: TrashedItem): Result<Unit> = runCatching {
        store.delete(item.ref)
        dropIfEmpty(item.entry)
    }

    /** Deletes everything in the trash for good. */
    suspend fun empty(): Result<Unit> = runCatching {
        trashDir(create = false)?.let { store.delete(it) }
    }

    /**
     * Lets go of every batch past its thirty days. Returns how many went.
     *
     * Only batches it can read a date from: a folder in the trash this app did not make is left
     * exactly where it is.
     */
    suspend fun purge(now: Long = clock()): Result<Int> = runCatching {
        val trash = trashDir(create = false) ?: return@runCatching 0
        var purged = 0
        for (batch in store.list(trash)) {
            if (!batch.isDirectory) continue
            val parsed = TrashNaming.parseEntry(batch.name) ?: continue
            if (TrashNaming.isExpired(parsed.deletedAt, now)) {
                store.delete(batch.ref)
                purged++
            }
        }
        purged
    }

    private suspend fun trashDir(create: Boolean): StoreRef? {
        val root = store.root() ?: return null
        return store.child(root, TrashNaming.DIR)
            ?: if (create) store.createDirectory(root, TrashNaming.DIR) else null
    }

    /** A batch folder with nothing left in it is clutter, and a folder sync would carry around. */
    private suspend fun dropIfEmpty(entry: StoreRef) {
        runCatching { if (store.list(entry).isEmpty()) store.delete(entry) }
    }
}
