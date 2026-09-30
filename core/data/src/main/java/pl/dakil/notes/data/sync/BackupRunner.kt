package pl.dakil.notes.data.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.NoteStore
import pl.dakil.notes.data.SafNoteStore
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.StoreListing
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.sync.ArchiveEntry
import pl.dakil.notes.sync.LibraryArchive
import pl.dakil.notes.sync.Retention
import pl.dakil.notes.sync.SyncPlanner
import java.io.ByteArrayInputStream

sealed interface BackupResult {
    data class Done(val fileName: String, val files: Int, val pruned: Int) : BackupResult
    data class Failed(val reason: String) : BackupResult
}

sealed interface RestoreResult {
    data class Done(val restored: Int) : RestoreResult
    data class Failed(val reason: String) : RestoreResult
}

/**
 * Writes the whole library to a `.zip` wherever the user pointed, and reads one back.
 *
 * The destination is a SAF tree, which is the entire reason this is cheap: "back up to Nextcloud",
 * "…to Proton Drive", "…to an SD card", "…to a USB stick" are all the same code, because each of
 * those ships a `DocumentsProvider` and the picker treats them alike. No per-provider integration,
 * no credentials, no network permission.
 */
class BackupRunner(
    private val context: Context,
    private val store: NoteStore,
    private val repository: NoteRepository,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun backUpNow(): BackupResult = withContext(io) {
        val destinationUri = settings.read().backupDestination
            ?: return@withContext BackupResult.Failed("No backup folder chosen")
        val destination = SafNoteStore(context, Uri.parse(destinationUri), io)
        val destinationRoot = destination.root()
            ?: return@withContext BackupResult.Failed("The backup folder could not be opened")

        val root = store.root() ?: return@withContext BackupResult.Failed("No library")
        val entries = ArrayList<ArchiveEntry>()
        if (!collect(root, "", entries)) {
            // A partial archive presented as a backup is worse than none: it is the file someone
            // restores from after losing the original.
            return@withContext BackupResult.Failed("The library could not be read in full")
        }

        val name = Retention.nameFor(clock())
        val target = destination.newChild(destinationRoot, name, "application/zip")
        runCatching {
            destination.write(target) { out -> LibraryArchive.write(entries, out) }
        }.onFailure { return@withContext BackupResult.Failed(it.message ?: "Could not write the archive") }

        val pruned = prune(destination, destinationRoot)
        settings.setLastBackupAt(clock())
        BackupResult.Done(name, entries.size, pruned)
    }

    /** Reads every file under the library root, refusing rather than skipping what it cannot. */
    private suspend fun collect(dir: StoreRef, path: String, out: MutableList<ArchiveEntry>): Boolean {
        val entries = when (val listing = store.listChecked(dir)) {
            is StoreListing.Ok -> listing.entries
            is StoreListing.Unavailable -> return false
        }
        for (entry in entries) {
            val childPath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
            if (SyncPlanner.isIgnored(childPath)) continue
            if (entry.isDirectory) {
                if (!collect(entry.ref, childPath, out)) return false
                continue
            }
            // Everything under the root, not only the two note kinds: it is the user's folder, and
            // a backup that silently omitted their attachments would not be a backup.
            val bytes = runCatching { store.read(entry.ref) { it.readBytes() } }.getOrNull() ?: return false
            out += ArchiveEntry(childPath) { ByteArrayInputStream(bytes) }
        }
        return true
    }

    private suspend fun prune(destination: NoteStore, root: StoreRef): Int {
        val keep = settings.read().backupKeep
        if (keep <= 0) return 0
        val existing = when (val listing = destination.listChecked(root)) {
            is StoreListing.Ok -> listing.entries
            is StoreListing.Unavailable -> return 0
        }
        val archives = existing.mapNotNull { entry -> Retention.parse(entry.name)?.let { it to entry.ref } }
        var pruned = 0
        for (name in Retention.prune(archives.map { it.first }, keep)) {
            val ref = archives.firstOrNull { it.first.fileName == name.fileName }?.second ?: continue
            if (runCatching { destination.delete(ref) }.isSuccess) pruned++
        }
        return pruned
    }

    /**
     * Reads an archive back into the library.
     *
     * Never overwrites: a name already taken steps aside to ` (2)`, the same rule a new note
     * follows. Restoring is something people do when they are already having a bad day, and one
     * that quietly replaced the notes they still had would make it worse.
     */
    suspend fun restoreFrom(archiveUri: Uri): RestoreResult = withContext(io) {
        val root = store.root() ?: return@withContext RestoreResult.Failed("No library")
        var restored = 0
        runCatching {
            context.contentResolver.openInputStream(archiveUri)?.use { input ->
                LibraryArchive.read(input) { path, stream ->
                    val bytes = stream.readBytes()
                    kotlinx.coroutines.runBlocking {
                        val parent = ensureDirectory(root, path.substringBeforeLast('/', ""))
                        if (parent != null) {
                            val ref = store.newChild(parent, path.substringAfterLast('/'), "application/octet-stream")
                            store.write(ref) { out -> out.write(bytes) }
                            restored++
                        }
                    }
                }
            } ?: return@withContext RestoreResult.Failed("The archive could not be opened")
        }.onFailure { return@withContext RestoreResult.Failed(it.message ?: "The archive could not be read") }

        repository.refreshIndex(root, "", recursive = true)
        RestoreResult.Done(restored)
    }

    private suspend fun ensureDirectory(root: StoreRef, path: String): StoreRef? {
        if (path.isEmpty()) return root
        var current = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            current = store.child(current, segment)
                ?: runCatching { store.createDirectory(current, segment) }.getOrNull()
                ?: return null
        }
        return current
    }
}
