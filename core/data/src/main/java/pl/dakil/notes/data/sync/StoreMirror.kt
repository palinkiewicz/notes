package pl.dakil.notes.data.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.NoteStore
import pl.dakil.notes.data.StoreEntry
import pl.dakil.notes.data.StoreListing
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.DakNoteReader
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.sync.LocalEntry
import pl.dakil.notes.sync.LocalListing
import pl.dakil.notes.sync.LocalMirror
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The device side of a sync, on top of the store and the repository.
 *
 * It goes through [NoteRepository] rather than around it, so a pulled file lands with the write
 * lock, the search index and the stamp bookkeeping all in step — three things it would otherwise
 * have to reimplement, each with its own way of being subtly wrong.
 *
 * Logical paths in, [StoreRef]s out. The planner speaks paths because that is the one handle a
 * `.md`, a `.daknote`, a Drive file id and a WebDAV href all share; a SAF document URI is opaque
 * and cannot be turned into a path, so the mapping is built during [list] and kept for the pass.
 */
class StoreMirror(
    private val store: NoteStore,
    private val repository: NoteRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalMirror {

    private val refs = HashMap<String, StoreRef>()
    private val dirs = HashMap<String, StoreRef>()

    /**
     * Hashes, keyed by what the file looked like when they were taken.
     *
     * Without this every pass would read the whole library to answer "did anything change". The
     * key includes size and timestamp, so a file that was touched is re-hashed and one that was
     * not is free.
     */
    private val hashes = HashMap<String, Pair<Long, String>>()

    override suspend fun list(): LocalListing = withContext(io) {
        refs.clear()
        dirs.clear()
        val root = store.root() ?: return@withContext LocalListing(emptyList(), complete = false)
        dirs[""] = root
        val out = ArrayList<LocalEntry>()
        val complete = walk(root, "", out)
        LocalListing(out, complete)
    }

    private suspend fun walk(dir: StoreRef, path: String, out: MutableList<LocalEntry>): Boolean {
        val entries = when (val listing = store.listChecked(dir)) {
            is StoreListing.Ok -> listing.entries
            // Not "an empty folder": see StoreListing. The planner suppresses every delete when
            // this comes back false, which is the whole reason the distinction exists.
            is StoreListing.Unavailable -> return false
        }
        var complete = true
        for (entry in entries) {
            val childPath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
            if (entry.isDirectory) {
                dirs[childPath] = entry.ref
                out += LocalEntry(childPath, isDirectory = true, sizeBytes = 0, modifiedAt = entry.modifiedAt)
                if (!walk(entry.ref, childPath, out)) complete = false
                continue
            }
            refs[childPath] = entry.ref
            val kind = NoteKind.of(entry.name)
            out += LocalEntry(
                path = childPath,
                isDirectory = false,
                sizeBytes = entry.sizeBytes,
                modifiedAt = entry.modifiedAt,
                noteId = if (kind == NoteKind.INK) noteIdOf(entry) else "",
                contentHash = hashOf(entry),
            )
        }
        return complete
    }

    /** Reads only the manifest — two ZIP entries, not the whole sheet. */
    private suspend fun noteIdOf(entry: StoreEntry): String = runCatching {
        store.read(entry.ref) { DakNoteReader.readMeta(it)?.id?.raw }.orEmpty()
    }.getOrDefault("")

    private suspend fun hashOf(entry: StoreEntry): String {
        val stamp = entry.modifiedAt * 31 + entry.sizeBytes
        hashes[entry.ref.value]?.let { (cached, hash) -> if (cached == stamp) return hash }
        val hash = runCatching {
            store.read(entry.ref) { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrDefault("")
        if (hash.isNotEmpty()) hashes[entry.ref.value] = stamp to hash
        return hash
    }

    override suspend fun <T> read(path: String, body: (InputStream) -> T): T {
        val ref = refs[path] ?: throw IllegalStateException("No local file at $path")
        return store.read(ref, body)
    }

    override suspend fun write(path: String, sizeBytes: Long, body: (OutputStream) -> Unit): Boolean {
        val existing = refs[path]
        return if (existing != null) {
            repository.applyRemoteWrite(existing, body)
        } else {
            val parent = ensureDirectory(path.substringBeforeLast('/', "")) ?: return false
            val ref = repository.applyRemoteCreate(parent, path.substringAfterLast('/'), body) ?: return false
            refs[path] = ref
            true
        }
    }

    override suspend fun delete(path: String, expectHash: String): Boolean {
        val ref = refs[path] ?: dirs[path] ?: return false
        val ok = repository.applyRemoteDelete(ref)
        if (ok) {
            refs.remove(path)
            dirs.remove(path)
        }
        return ok
    }

    override suspend fun move(from: String, to: String): Boolean {
        val ref = refs[from] ?: return false
        val fromParent = dirs[from.substringBeforeLast('/', "")] ?: return false
        val toParent = ensureDirectory(to.substringBeforeLast('/', "")) ?: return false
        val moved = repository.applyRemoteMove(ref, fromParent, toParent, to.substringAfterLast('/')) ?: return false
        refs.remove(from)
        refs[to] = moved
        return true
    }

    override suspend fun createDirectory(path: String): Boolean = ensureDirectory(path) != null

    private suspend fun ensureDirectory(path: String): StoreRef? {
        if (path.isEmpty()) return dirs[""] ?: store.root()
        dirs[path]?.let { return it }
        val parent = ensureDirectory(path.substringBeforeLast('/', "")) ?: return null
        val name = path.substringAfterLast('/')
        val ref = store.child(parent, name) ?: runCatching { store.createDirectory(parent, name) }.getOrNull()
        if (ref != null) dirs[path] = ref
        return ref
    }

    override suspend fun copyAside(from: String, to: String): Boolean {
        val source = refs[from] ?: return false
        val bytes = runCatching { store.read(source) { it.readBytes() } }.getOrNull() ?: return false
        return write(to, bytes.size.toLong()) { out -> out.write(bytes); out.flush() }
    }

    override suspend fun stat(path: String): LocalEntry? {
        val ref = refs[path] ?: return null
        val entry = store.metadata(ref) ?: return null
        return LocalEntry(
            path = path,
            isDirectory = entry.isDirectory,
            sizeBytes = entry.sizeBytes,
            modifiedAt = entry.modifiedAt,
            noteId = if (NoteKind.of(entry.name) == NoteKind.INK) noteIdOf(entry) else "",
            contentHash = hashOf(entry),
        )
    }

    override suspend fun openPaths(): Set<String> {
        val open = repository.openRef.value ?: return emptySet()
        return refs.entries.firstOrNull { it.value == open }?.let { setOf(it.key) } ?: emptySet()
    }

    override suspend fun awaitIdle() = repository.awaitIdle()

    override suspend fun reindex() {
        val root = store.root() ?: return
        repository.refreshIndex(root, "", recursive = true)
    }
}
