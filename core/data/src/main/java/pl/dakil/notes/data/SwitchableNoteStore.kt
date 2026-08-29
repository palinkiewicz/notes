package pl.dakil.notes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * A [NoteStore] that forwards to whichever backend is current, so the library's root can change
 * without the graph above it being rebuilt.
 *
 * The indirection exists because object identity has to survive the swap. [NoteRepository] takes
 * its store by constructor and holds a debounced save pipeline and a write lock behind it; handing
 * out a new repository when the user picks a folder would drop a queued write on the floor and give
 * every screen still holding the old one a store pointing at the previous root.
 *
 * [delegate] is `@Volatile` rather than locked: a swap is a single reference write, callers already
 * tolerate the root moving underneath them (that is what a swap *is*), and taking a lock on every
 * list and read to serialise against an event that happens once would cost more than it buys. An
 * operation already in flight finishes against the backend it started on, which is the behaviour a
 * lock would have given anyway.
 */
class SwitchableNoteStore(initial: NoteStore) : NoteStore {

    @Volatile
    private var delegate: NoteStore = initial

    private val _rootChanges = MutableStateFlow(0)

    /**
     * Bumped every time the backend changes, so screens holding refs from the old root can throw
     * them away.
     *
     * They have to: a [StoreRef] is only meaningful to the backend that issued it, and the library
     * keeps a stack of them for the folder it is showing. Handing a filesystem path to a SAF store
     * gets an unparseable URI and an empty folder — a library that looks like it lost everything.
     */
    val rootChanges: StateFlow<Int> = _rootChanges.asStateFlow()

    /** The backend in use, for callers that must know what kind of storage they are on. */
    val current: NoteStore get() = delegate

    fun swap(next: NoteStore) {
        delegate = next
        _rootChanges.value++
    }

    override val capabilities: StoreCapabilities get() = delegate.capabilities

    override suspend fun root(): StoreRef? = delegate.root()

    override suspend fun list(dir: StoreRef): List<StoreEntry> = delegate.list(dir)

    override suspend fun listChecked(dir: StoreRef): StoreListing = delegate.listChecked(dir)

    override suspend fun exists(ref: StoreRef): Boolean = delegate.exists(ref)

    override suspend fun metadata(ref: StoreRef): StoreEntry? = delegate.metadata(ref)

    override suspend fun <T> read(ref: StoreRef, body: (InputStream) -> T): T = delegate.read(ref, body)

    override suspend fun write(ref: StoreRef, body: (OutputStream) -> Unit) = delegate.write(ref, body)

    override suspend fun delete(ref: StoreRef) = delegate.delete(ref)

    override suspend fun move(from: StoreRef, to: StoreRef): StoreRef = delegate.move(from, to)

    override suspend fun moveTo(from: StoreRef, fromParent: StoreRef, toParent: StoreRef): StoreRef =
        delegate.moveTo(from, fromParent, toParent)

    override suspend fun createDirectory(parent: StoreRef, name: String): StoreRef =
        delegate.createDirectory(parent, name)

    override suspend fun child(parent: StoreRef, name: String): StoreRef? = delegate.child(parent, name)

    override suspend fun newChild(parent: StoreRef, name: String, mimeType: String): StoreRef =
        delegate.newChild(parent, name, mimeType)

    override suspend fun siblingOf(sibling: StoreRef, name: String): StoreRef? =
        delegate.siblingOf(sibling, name)

    override fun watch(dir: StoreRef): Flow<StoreChange> = delegate.watch(dir)
}
