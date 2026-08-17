package pl.dakil.notes.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [NoteStore] over the ordinary file system.
 *
 * Used for app-private storage and for any directory the app has direct filesystem access to. It
 * is also the store the tests run against, since it needs no Android framework beyond `FileObserver`.
 */
class FileNoteStore(
    private val rootDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NoteStore {

    override val capabilities = StoreCapabilities(
        atomicReplace = true,
        watch = true,
        revisions = false,
        remote = false,
    )

    override suspend fun root(): StoreRef = StoreRef(rootDir.absolutePath)

    private fun fileOf(ref: StoreRef) = File(ref.value)

    override suspend fun list(dir: StoreRef): List<StoreEntry> = withContext(io) {
        val children = fileOf(dir).listFiles() ?: return@withContext emptyList()
        children.map { it.toEntry() }
    }

    override suspend fun exists(ref: StoreRef): Boolean = withContext(io) { fileOf(ref).exists() }

    override suspend fun metadata(ref: StoreRef): StoreEntry? = withContext(io) {
        fileOf(ref).takeIf { it.exists() }?.toEntry()
    }

    override suspend fun <T> read(ref: StoreRef, body: (InputStream) -> T): T = withContext(io) {
        try {
            fileOf(ref).inputStream().buffered().use(body)
        } catch (e: IOException) {
            throw StoreException("Could not read ${ref.value}", e)
        }
    }

    /**
     * Writes through a sibling temporary and renames over the target.
     *
     * `rename` on the same filesystem is atomic, so a reader either sees the whole previous
     * version or the whole new one — never a half-written note, whatever happens mid-save.
     */
    override suspend fun write(ref: StoreRef, body: (OutputStream) -> Unit) = withContext(io) {
        val target = fileOf(ref)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                val buffered = out.buffered()
                // The body owns its stream and will close it (ZipOutputStream.use does), but the
                // file descriptor must stay open long enough to fsync it.
                body(NonClosingOutputStream(buffered))
                buffered.flush()
                out.fd.sync() // Durability: the rename is pointless if the data is still in cache.
            }
            if (!temp.renameTo(target)) {
                // Cross-device or an exotic filesystem; fall back to a copy.
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: IOException) {
            temp.delete()
            throw StoreException("Could not write ${ref.value}", e)
        }
    }

    override suspend fun delete(ref: StoreRef) = withContext(io) {
        val file = fileOf(ref)
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!ok && file.exists()) throw StoreException("Could not delete ${ref.value}")
    }

    override suspend fun move(from: StoreRef, to: StoreRef): StoreRef = withContext(io) {
        val source = fileOf(from)
        // `to` is a display name, not a path — resolving it as one would drop the note into the
        // process's working directory instead of renaming it in place.
        val target = File(source.parentFile, to.value.sanitizeFileName())
        if (target != source && !source.renameTo(target)) {
            throw StoreException("Could not rename ${from.value}")
        }
        StoreRef(target.absolutePath)
    }

    override suspend fun createDirectory(parent: StoreRef, name: String): StoreRef = withContext(io) {
        val dir = File(fileOf(parent), name.sanitizeFileName())
        if (!dir.exists() && !dir.mkdirs()) throw StoreException("Could not create ${dir.absolutePath}")
        StoreRef(dir.absolutePath)
    }

    override suspend fun child(parent: StoreRef, name: String): StoreRef? = withContext(io) {
        File(fileOf(parent), name).takeIf { it.exists() }?.let { StoreRef(it.absolutePath) }
    }

    // The filesystem has no place to record a MIME type; the extension in `name` carries it.
    override suspend fun newChild(parent: StoreRef, name: String, mimeType: String): StoreRef = withContext(io) {
        StoreRef(File(fileOf(parent), uniqueName(fileOf(parent), name.sanitizeFileName())).absolutePath)
    }

    /**
     * Watches [dir] with `FileObserver`.
     *
     * Non-recursive on purpose: recursive observation costs an inotify watch per directory and a
     * note library can be deep. Callers refresh a folder when they navigate into it.
     */
    override fun watch(dir: StoreRef): Flow<StoreChange> = callbackFlow {
        val target = fileOf(dir)
        val observer = object : android.os.FileObserver(target, MASK) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                val ref = StoreRef(File(target, name).absolutePath)
                val change = when {
                    event and (CREATE or MOVED_TO) != 0 -> StoreChange.Added(ref)
                    event and (DELETE or MOVED_FROM) != 0 -> StoreChange.Removed(ref)
                    event and CLOSE_WRITE != 0 -> StoreChange.Modified(ref)
                    else -> return
                }
                trySend(change)
            }
        }
        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }

    private fun File.toEntry() = StoreEntry(
        ref = StoreRef(absolutePath),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0L else length(),
        modifiedAt = lastModified(),
    )

    private companion object {
        const val MASK = android.os.FileObserver.CREATE or
            android.os.FileObserver.DELETE or
            android.os.FileObserver.MOVED_TO or
            android.os.FileObserver.MOVED_FROM or
            android.os.FileObserver.CLOSE_WRITE
    }
}

/** Strips characters that are illegal or awkward in a file name on Android or a sync target. */
fun String.sanitizeFileName(fallback: String = "Untitled"): String {
    val cleaned = buildString(length) {
        for (c in this@sanitizeFileName) {
            append(if (c in ILLEGAL_FILENAME_CHARS || c.code < 0x20) ' ' else c)
        }
    }.trim().trimEnd('.')
    return cleaned.ifEmpty { fallback }.take(120)
}

private const val ILLEGAL_FILENAME_CHARS = "/\\:*?\"<>|"

/** Appends ` (2)`, ` (3)`… until the name is free, matching what users see elsewhere on Android. */
internal fun uniqueName(dir: File, name: String): String {
    if (!File(dir, name).exists()) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var n = 2
    while (File(dir, "$stem ($n)$extension").exists()) n++
    return "$stem ($n)$extension"
}
