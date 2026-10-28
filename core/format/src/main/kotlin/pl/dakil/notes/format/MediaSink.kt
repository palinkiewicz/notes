package pl.dakil.notes.format

import pl.dakil.notes.model.DiscardedMedia
import pl.dakil.notes.model.FileMedia
import pl.dakil.notes.model.MediaData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.CRC32

/**
 * Where the reader puts the entries too large to hold on the heap.
 *
 * Only PDFs go through it. Everything else in a note is small, and a note is read into memory
 * whole because that is what lets the writer replay it byte for byte; a PDF is the one entry that
 * can make "whole" mean tens of megabytes, read again by every path that opens a note only to look
 * at it.
 */
interface MediaSink {

    /** Whether the entry called [name] goes through this sink rather than onto the heap. */
    fun takes(name: String): Boolean

    /**
     * Takes the entry being read from [input]. [size] and [crc] are what the ZIP's local header
     * declared, or -1 when it declared nothing.
     */
    fun accept(name: String, input: InputStream, size: Long, crc: Long): MediaData

    companion object {
        /** Nothing is spooled: every entry is read onto the heap, as it always was. */
        val InMemory: MediaSink = object : MediaSink {
            override fun takes(name: String): Boolean = false
            override fun accept(name: String, input: InputStream, size: Long, crc: Long): MediaData =
                throw IllegalStateException("InMemory takes no entries")
        }

        /**
         * Large entries are read past and dropped, for the index scan, which wants a note's words
         * and not its PDF. A note read this way cannot be written back; see [DiscardedMedia].
         */
        val Discard: MediaSink = object : MediaSink {
            override fun takes(name: String): Boolean = isLarge(name)
            override fun accept(name: String, input: InputStream, size: Long, crc: Long): MediaData =
                DiscardedMedia(size, crc)
        }

        /** The entries kept off the heap: PDFs, by extension. */
        fun isLarge(name: String): Boolean =
            name.startsWith(DakNote.DIR_MEDIA) && name.lowercase(Locale.ROOT).endsWith(".pdf")
    }
}

/**
 * Spools large entries into [dir], one file per distinct content.
 *
 * The file is named by the entry's own name — itself named by content — plus the size and checksum
 * the ZIP declared for it. So opening a note whose PDF is already here costs a directory lookup, not
 * a copy: the bytes are read past, and the ZIP checks them against its checksum on the way. Opening
 * the same note again, or another note holding the same PDF, finds the same file.
 *
 * A file is written under a temporary name and renamed into place, so a crash halfway through a copy
 * leaves a stray temporary rather than a truncated PDF under a name that claims to be whole.
 */
class FileMediaSpool(
    private val dir: File,
    /** Applied once, before the spool is first used; see [trim]. Null keeps everything. */
    private val policy: TrimPolicy? = null,
) : MediaSink {

    /** How much of the spool to keep: see [trim]. */
    data class TrimPolicy(val maxAgeMs: Long, val maxBytes: Long, val clock: () -> Long = System::currentTimeMillis)

    @Volatile
    private var trimmed = policy == null

    override fun takes(name: String): Boolean = MediaSink.isLarge(name)

    override fun accept(name: String, input: InputStream, size: Long, crc: Long): MediaData {
        trimOnce()
        dir.mkdirs()
        if (size >= 0 && crc >= 0) {
            val known = File(dir, fileName(name, size, crc))
            if (known.isFile && known.length() == size) {
                known.setLastModified(System.currentTimeMillis())
                return FileMedia(known, size, crc)
            }
        }
        val temp = File.createTempFile(TEMP_PREFIX, ".part", dir)
        try {
            val checksum = CRC32()
            var total = 0L
            FileOutputStream(temp).use { out ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) throw IOException("$name exceeds $MAX_ENTRY_BYTES bytes")
                    checksum.update(chunk, 0, n)
                    out.write(chunk, 0, n)
                }
                out.fd.sync()
            }
            val target = File(dir, fileName(name, total, checksum.value))
            if (target.isFile && target.length() == total) {
                temp.delete()
                target.setLastModified(System.currentTimeMillis())
            } else if (!temp.renameTo(target)) {
                throw IOException("Could not move $name into the media spool")
            }
            return FileMedia(target, total, checksum.value)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /**
     * Writes [input] into the spool as the media entry [name] would be, for an attachment that has
     * not been through a note yet — a PDF just imported.
     */
    fun store(name: String, input: InputStream): MediaData = accept(name, input, -1, -1)

    /**
     * Deletes spooled files nobody has opened for [maxAgeMs], then the oldest until the folder is
     * under [maxBytes], and any temporary left by a copy that never finished.
     *
     * Only before any note is open — a file in use is never one this can tell apart from an
     * abandoned one — which is why a [policy] runs it at first use rather than on a timer.
     */
    /**
     * The first use in a process is the one moment no file here can belong to an open note, so the
     * trim waits for it rather than racing a note being reopened on another thread.
     */
    private fun trimOnce() {
        if (trimmed) return
        synchronized(this) {
            if (trimmed) return
            policy?.let { trim(it.clock(), it.maxAgeMs, it.maxBytes) }
            trimmed = true
        }
    }

    fun trim(now: Long, maxAgeMs: Long, maxBytes: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val kept = ArrayList<File>()
        for (file in files) {
            if (file.name.startsWith(TEMP_PREFIX) || now - file.lastModified() > maxAgeMs) file.delete()
            else kept += file
        }
        var total = kept.sumOf { it.length() }
        for (file in kept.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun fileName(entry: String, size: Long, crc: Long): String {
        val base = entry.substringAfterLast('/')
        val stem = base.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val ext = base.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
        return "$stem-${crc.toString(16)}-$size.$ext"
    }

    private companion object {
        const val TEMP_PREFIX = "spool-"

        /** A gigabyte: far past any PDF a note-taker will be handed, short of filling the disk. */
        const val MAX_ENTRY_BYTES = 1L shl 30
    }
}
