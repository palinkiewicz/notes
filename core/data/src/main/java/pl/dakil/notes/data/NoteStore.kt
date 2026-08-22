package pl.dakil.notes.data

import kotlinx.coroutines.flow.Flow
import pl.dakil.notes.format.NoteKind
import java.io.InputStream
import java.io.OutputStream

/**
 * An opaque handle to a location in a store.
 *
 * A string rather than a `File` or a `Uri` because the whole point of this layer is that the
 * editor never learns which kind of backend it is talking to. A local store puts a path here, a
 * SAF store puts a document URI, and a future cloud store can put whatever it likes.
 */
@JvmInline
value class StoreRef(val value: String) {
    override fun toString(): String = value
}

/**
 * The note's title as its storage records it: the file name without the extension.
 *
 * True of both kinds. A `.md` note has nowhere else to keep a title, and an ink note's manifest is
 * kept in step with its file name by [NoteRepository.rename] — so the name is always the answer,
 * and always the one available without opening the file.
 */
fun StoreRef.noteTitle(): String = NoteKind.titleOf(value.substringAfterLast('/'))

data class StoreEntry(
    val ref: StoreRef,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
)

/** What a backend can actually do, so callers can degrade instead of failing. */
data class StoreCapabilities(
    /** Whether a write can be made to appear atomically. Drives the temp-file-and-swap strategy. */
    val atomicReplace: Boolean = false,
    /** Whether [NoteStore.watch] emits real change events rather than nothing. */
    val watch: Boolean = false,
    /** Whether the backend keeps its own version history. */
    val revisions: Boolean = false,
    /** Whether writes cross the network, which changes how aggressive autosave should be. */
    val remote: Boolean = false,
)

sealed interface StoreChange {
    val ref: StoreRef

    data class Added(override val ref: StoreRef) : StoreChange
    data class Modified(override val ref: StoreRef) : StoreChange
    data class Removed(override val ref: StoreRef) : StoreChange
}

class StoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Byte-level storage, decoupled from what the bytes mean.
 *
 * This is the seam that makes sync a later feature rather than a rewrite. Nothing above this
 * interface knows whether a note lives in app-private storage, in a user-picked folder reached
 * through the Storage Access Framework, or one day in a Drive or Git backend — those arrive as new
 * implementations, not as changes to the editor.
 *
 * Implementations must be safe to call from any thread and must not block the caller's thread; all
 * suspending members are expected to dispatch to IO internally.
 */
interface NoteStore {

    val capabilities: StoreCapabilities

    /** The root the user has chosen for this store, or null if none is configured yet. */
    suspend fun root(): StoreRef?

    suspend fun list(dir: StoreRef): List<StoreEntry>

    suspend fun exists(ref: StoreRef): Boolean

    suspend fun metadata(ref: StoreRef): StoreEntry?

    suspend fun <T> read(ref: StoreRef, body: (InputStream) -> T): T

    /**
     * Replaces the contents of [ref], creating it if needed.
     *
     * Implementations must not leave a partially written file visible: where the backend allows
     * it, write to a sibling temporary and swap. A note truncated by a crash mid-save is the worst
     * failure this app can have.
     */
    suspend fun write(ref: StoreRef, body: (OutputStream) -> Unit)

    suspend fun delete(ref: StoreRef)

    /**
     * Renames [from] within its own parent; [to] carries the new **display name**, not a full ref.
     *
     * A name rather than a destination ref because SAF only exposes rename that way — there is no
     * "write this document at this URI" operation — and a contract only one backend can honour is
     * not a contract.
     */
    suspend fun move(from: StoreRef, to: StoreRef): StoreRef

    /**
     * Moves [from] out of [fromParent] and into [toParent], keeping its name.
     *
     * Separate from [move] because the two are separate operations in SAF, and because this one
     * needs the source's parent: `DocumentsContract.moveDocument` takes it, and a document URI
     * cannot be walked upwards to find it. The browser always knows the folder it is listing, so
     * asking for it costs the caller nothing.
     *
     * Works on directories as well as notes. Implementations must step aside from a name already
     * taken in [toParent] rather than overwriting it, exactly as [move] does.
     */
    suspend fun moveTo(from: StoreRef, fromParent: StoreRef, toParent: StoreRef): StoreRef

    suspend fun createDirectory(parent: StoreRef, name: String): StoreRef

    /** Resolves a child by name, creating nothing. */
    suspend fun child(parent: StoreRef, name: String): StoreRef?

    /**
     * Allocates a ref for a new child, avoiding a collision with an existing name.
     *
     * [mimeType] is what a backend that records one should record. A filesystem has nowhere to put
     * it and ignores it; SAF stores it on the document, and getting it wrong there means the file
     * opens in the wrong app from the system file browser.
     */
    suspend fun newChild(parent: StoreRef, name: String, mimeType: String): StoreRef

    /**
     * Change notifications for [dir]. Backends without watch support return an empty flow, which
     * callers handle by refreshing on resume instead.
     */
    fun watch(dir: StoreRef): Flow<StoreChange>
}

/**
 * Passes writes through but swallows [close], flushing instead.
 *
 * Write bodies own their stream and close it — `ZipOutputStream.use { }` in the note writer does —
 * but the store still needs the underlying descriptor open afterwards to fsync and swap it.
 */
internal class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() = delegate.flush()
}
