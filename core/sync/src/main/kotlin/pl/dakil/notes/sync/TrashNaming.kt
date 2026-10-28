package pl.dakil.notes.sync

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * How the library's trash is laid out on disk.
 *
 * ```
 * .daknote-trash/
 *     20261028T205512.345Z Work%2FQ3/      ← deleted at…, from Work/Q3
 *         Plan.daknote
 *     20261029T080000.000Z/                 ← deleted from the root
 *         Recipes/                          ← a folder, with everything that was in it
 * ```
 *
 * Everything the trash knows is in the names, never in a file beside them. Sync carries folders and
 * notes and nothing else, so an `info.json` saying where an item came from would reach the next
 * device without the item, or the item without it; a folder name always travels with what is in it.
 * The same layout is what lets the trash live inside the library at all, on any backend, local or
 * SAF, with nothing app-private to fall out of step with it.
 *
 * Hidden by its dot, so another file browser does not show it among the notes, and named for this
 * app, so the thirty-day purge can never mistake some other tool's `.trash` for its own.
 */
object TrashNaming {

    const val DIR = ".daknote-trash"

    /** How long a deleted item is kept before it is gone for good. */
    const val RETENTION_DAYS = 30

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** One batch of items deleted together: when, and from which folder of the library. */
    data class Entry(val deletedAt: Long, val originPath: String)

    /**
     * The folder a batch deleted at [deletedAt] from [originPath] goes in.
     *
     * The instant in UTC to the millisecond, formatted by hand for the reason [ConflictNaming] is:
     * a Thai or Persian locale would otherwise write a calendar this could not read back. The path
     * is percent-encoded so its slashes stay inside one name.
     */
    fun entryName(deletedAt: Long, originPath: String): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            timeInMillis = deletedAt
        }
        val stamp = buildString {
            append(pad(calendar.get(Calendar.YEAR), 4))
            append(pad(calendar.get(Calendar.MONTH) + 1, 2))
            append(pad(calendar.get(Calendar.DAY_OF_MONTH), 2))
            append('T')
            append(pad(calendar.get(Calendar.HOUR_OF_DAY), 2))
            append(pad(calendar.get(Calendar.MINUTE), 2))
            append(pad(calendar.get(Calendar.SECOND), 2))
            append('.')
            append(pad(calendar.get(Calendar.MILLISECOND), 3))
            append('Z')
        }
        return if (originPath.isEmpty()) stamp else "$stamp ${encode(originPath)}"
    }

    /** The batch [name] describes, or null for a folder in the trash this app did not make. */
    fun parseEntry(name: String): Entry? {
        val space = name.indexOf(' ')
        val stamp = if (space < 0) name else name.substring(0, space)
        val match = STAMP.matchEntire(stamp) ?: return null
        val (y, mo, d, h, mi, s, ms) = match.destructured
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            set(Calendar.MILLISECOND, ms.toInt())
        }
        val origin = if (space < 0) "" else decode(name.substring(space + 1)) ?: return null
        return Entry(calendar.timeInMillis, origin)
    }

    /** Whether a batch deleted at [deletedAt] is past keeping at [now]. */
    fun isExpired(deletedAt: Long, now: Long): Boolean = now - deletedAt >= RETENTION_DAYS * DAY_MS

    /**
     * Whole days a batch deleted at [deletedAt] has left at [now], rounded up, so the last day reads
     * as one day and not as none.
     */
    fun daysLeft(deletedAt: Long, now: Long): Int {
        val left = deletedAt + RETENTION_DAYS * DAY_MS - now
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    private val STAMP = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})\.(\d{3})Z""")

    private fun encode(path: String): String = buildString {
        for (ch in path) {
            when (ch) {
                '%' -> append("%25")
                '/' -> append("%2F")
                else -> append(ch)
            }
        }
    }

    private fun decode(text: String): String? = buildString {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%') {
                val code = text.substring(i + 1, minOf(i + 3, text.length)).uppercase(Locale.ROOT)
                when (code) {
                    "25" -> append('%')
                    "2F" -> append('/')
                    else -> return null
                }
                i += 3
            } else {
                append(ch)
                i++
            }
        }
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
