package pl.dakil.notes.sync

import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ArchiveEntry(val path: String, val open: () -> InputStream)

/**
 * A whole library as one `.zip`.
 *
 * Written deterministically — entries sorted, timestamps fixed — for the same reason
 * `DakNoteWriter` is: two backups of an unchanged library are then byte-identical, so a retention
 * count means something, and a backup folder that is itself synced stops re-uploading the same
 * bytes under a new name every night.
 */
object LibraryArchive {

    /** Not zero: some tools reject a 1970 entry outright, and MS-DOS time cannot express it. */
    private const val FIXED_TIME = 315_532_800_000L // 1980-01-01T00:00:00Z

    fun write(entries: List<ArchiveEntry>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (entry in entries.sortedBy { it.path }) {
                zip.putNextEntry(ZipEntry(entry.path).apply { time = FIXED_TIME })
                entry.open().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Calls [body] for each entry in turn. The stream is only valid inside the callback. */
    fun read(input: InputStream, body: (String, InputStream) -> Unit) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) body(entry.name, NonClosing(zip))
                zip.closeEntry()
            }
        }
    }

    private class NonClosing(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() = Unit
    }
}

data class ArchiveName(val takenAt: Long, val fileName: String)

/**
 * Naming and pruning the archives.
 *
 * The timestamp is formatted and parsed by hand in UTC, the same rule [ConflictNaming] follows: a
 * device set to a Buddhist calendar would otherwise write a year 543 out and then fail to recognise
 * its own backups, quietly keeping every one of them forever.
 */
object Retention {

    private const val PREFIX = "daknote-"
    private const val SUFFIX = ".zip"
    private val PATTERN = Regex("""^\Q$PREFIX\E(\d{8})-(\d{6})\Q$SUFFIX\E$""")

    fun nameFor(takenAt: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply { timeInMillis = takenAt }
        fun pad(v: Int, w: Int) = v.toString().padStart(w, '0')
        return PREFIX +
            pad(c.get(Calendar.YEAR), 4) + pad(c.get(Calendar.MONTH) + 1, 2) + pad(c.get(Calendar.DAY_OF_MONTH), 2) +
            "-" +
            pad(c.get(Calendar.HOUR_OF_DAY), 2) + pad(c.get(Calendar.MINUTE), 2) + pad(c.get(Calendar.SECOND), 2) +
            SUFFIX
    }

    fun parse(fileName: String): ArchiveName? {
        val match = PATTERN.matchEntire(fileName) ?: return null
        val d = match.groupValues[1]
        val t = match.groupValues[2]
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        c.clear()
        c.set(
            d.substring(0, 4).toInt(), d.substring(4, 6).toInt() - 1, d.substring(6, 8).toInt(),
            t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt(),
        )
        return ArchiveName(c.timeInMillis, fileName)
    }

    /**
     * Which archives to delete, keeping the [keep] newest.
     *
     * Sorted by the timestamp *in the name*, never by the order a folder listing happened to
     * return: SAF providers give no ordering guarantee at all, and pruning by listing order would
     * eventually delete the newest backup instead of the oldest.
     */
    fun prune(existing: List<ArchiveName>, keep: Int): List<ArchiveName> {
        if (keep <= 0) return emptyList()
        return existing.sortedByDescending { it.takenAt }.drop(keep)
    }
}
