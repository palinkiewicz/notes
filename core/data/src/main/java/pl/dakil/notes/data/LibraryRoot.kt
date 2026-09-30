package pl.dakil.notes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.format.NoteKind
import java.io.File

/** Where the library's files actually are. */
enum class LibraryRootKind {
    /** App-private storage: nothing else on the device can see it, and it dies with the app. */
    INTERNAL,

    /** A folder the user picked, reached through the Storage Access Framework. */
    EXTERNAL,
}

/** What adopting a folder did, so the screen can say something true about it. */
sealed interface AdoptOutcome {
    /** The folder already had notes in it; nothing was copied, the index was rebuilt over them. */
    data class Joined(val noteCount: Int) : AdoptOutcome

    /** The library was copied across. [copied] files landed; the originals are still in place. */
    data class Migrated(val copied: Int) : AdoptOutcome

    data class Failed(val reason: String) : AdoptOutcome
}

/**
 * Owns the question "where does the library live", and the move between answers.
 *
 * Kept out of [NoteRepository] because it is a different lifetime: the repository is about the note
 * in hand, this is about the ground under all of them. Kept out of the settings screen because the
 * move has to be safe whether or not anybody is watching it happen.
 */
class LibraryRootController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val store: SwitchableNoteStore,
    private val index: NoteIndex,
    private val internalRoot: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    val kind: LibraryRootKind
        get() = if (store.current is SafNoteStore) LibraryRootKind.EXTERNAL else LibraryRootKind.INTERNAL

    /**
     * Points the store at the saved folder, if there is one and it is still reachable.
     *
     * A grant can be lost between runs — the user revoked it, or the volume is not mounted — and
     * the honest response is to fall back to internal storage rather than to run against a store
     * that answers nothing. Falling back does **not** clear the setting: the folder may be back
     * tomorrow, and forgetting it would silently strand the user's notes.
     */
    suspend fun restore() {
        val saved = settings.read().libraryRoot ?: return
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return
        if (!holdsPermission(uri)) return
        val saf = SafNoteStore(context, uri, io)
        // Reachability is not the same as permission: a detached SD card grants fine and lists
        // nothing. Only swap once the root has actually answered.
        if (saf.root()?.let { saf.listChecked(it) } !is StoreListing.Ok) return
        store.swap(saf)
    }

    /** Whether the persisted grant for [uri] is still held, which is what survives a reboot. */
    fun holdsPermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }

    fun takePermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /**
     * Adopts [treeUri] as the library root.
     *
     * Copy, verify, *then* switch — and never delete. If the chosen folder already holds notes it
     * is joined rather than written into, which is what makes pointing the app at an existing
     * Syncthing folder work. The app-private copy is left where it is either way: removing it is a
     * separate thing the user asks for once they can see their notes in the new place.
     */
    suspend fun adopt(treeUri: Uri): AdoptOutcome = withContext(io) {
        val destination = SafNoteStore(context, treeUri, io)
        val destinationRoot = destination.root()
            ?: return@withContext AdoptOutcome.Failed("The folder could not be opened")

        val existing = when (val listing = destination.listChecked(destinationRoot)) {
            is StoreListing.Ok -> listing.entries
            is StoreListing.Unavailable -> return@withContext AdoptOutcome.Failed(listing.reason)
        }

        val outcome = if (existing.any { it.isDirectory || isNote(it.name) }) {
            AdoptOutcome.Joined(existing.count { isNote(it.name) })
        } else {
            val source = FileNoteStore(internalRoot, io)
            runCatching { copyTree(source, source.root(), destination, destinationRoot) }
                .fold({ AdoptOutcome.Migrated(it) }, { AdoptOutcome.Failed(it.message ?: "Copy failed") })
        }
        if (outcome is AdoptOutcome.Failed) return@withContext outcome

        settings.setLibraryRoot(treeUri.toString())
        store.swap(destination)
        // The index is keyed by ref, and every ref just changed from a path to a document URI.
        // Nothing in it survives the move, so it is dropped rather than migrated — which is what
        // being a derived cache is for.
        index.clear()
        outcome
    }

    /**
     * Goes back to app-private storage, leaving the chosen folder untouched.
     *
     * Deliberately not a "migrate back": the files in the user's folder are theirs, and a revert
     * that emptied it would be the app taking the notes away again.
     */
    suspend fun revertToInternal() = withContext(io) {
        settings.setLibraryRoot(null)
        store.swap(FileNoteStore(internalRoot.apply { mkdirs() }, io))
        index.clear()
    }

    /** Copies every file and folder under [fromDir] into [toDir], verifying each write. */
    private suspend fun copyTree(
        from: NoteStore,
        fromDir: StoreRef,
        to: NoteStore,
        toDir: StoreRef,
    ): Int {
        var copied = 0
        val entries = when (val listing = from.listChecked(fromDir)) {
            is StoreListing.Ok -> listing.entries
            // Refusing to go on is the point: a partial copy that then became the library would
            // look exactly like a library the user had lost half of.
            is StoreListing.Unavailable -> throw StoreException(listing.reason)
        }
        for (entry in entries) {
            if (entry.isDirectory) {
                val child = to.child(toDir, entry.name) ?: to.createDirectory(toDir, entry.name)
                copied += copyTree(from, entry.ref, to, child)
                continue
            }
            val bytes = from.read(entry.ref) { it.readBytes() }
            val target = to.newChild(toDir, entry.name, mimeTypeOf(entry.name))
            to.write(target) { out -> out.write(bytes) }
            val landed = to.metadata(target)
            if (landed == null || landed.sizeBytes != bytes.size.toLong()) {
                throw StoreException("${entry.name} did not copy across intact")
            }
            copied++
        }
        return copied
    }

    /** Anything this app does not own keeps its bytes and loses its type; see [NoteKind.of]. */
    private fun mimeTypeOf(name: String): String =
        NoteKind.of(name)?.mimeType ?: "application/octet-stream"

    private fun isNote(name: String): Boolean = NoteKind.of(name) != null
}
