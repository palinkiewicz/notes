package pl.dakil.notes.sync

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Names for the copy kept aside when two versions of a note cannot both be the note.
 *
 * The pattern is Syncthing's, deliberately: once the library lives in a folder the user chose,
 * Syncthing may well be the thing that put the second version there, and a user who already knows
 * what `sync-conflict` means should not have to learn a second convention for the same event.
 *
 * ```
 * Standup.sync-conflict-20260828-141233-K7QF2.daknote
 * ```
 *
 * The device suffix is what makes the name survivable on more than two devices, and the seconds
 * make a second conflict on the same note in the same minute a different file rather than an
 * overwrite of the first one.
 */
object ConflictNaming {

    private const val MARKER = ".sync-conflict-"

    /**
     * Bare digits and upper-case letters, so the name can be parsed back without a locale.
     *
     * Formatted by hand rather than with `SimpleDateFormat`: a device set to a Thai locale would
     * stamp a Buddhist-era year into the file name and then fail to recognise its own output, which
     * is the same class of bug `LocaleSafeKeysTest` exists to prevent elsewhere.
     */
    private val PATTERN = Regex("""\Q$MARKER\E\d{8}-\d{6}-[A-Z0-9]+$""")

    /**
     * The name for a copy of [fileName] made at [at] on [deviceId].
     *
     * [at] is a wall-clock instant in UTC, so two devices in different zones cannot produce names
     * that sort against each other misleadingly.
     */
    fun nameFor(fileName: String, at: Long, deviceId: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            timeInMillis = at
        }
        val stamp = buildString {
            append(pad(calendar.get(Calendar.YEAR), 4))
            append(pad(calendar.get(Calendar.MONTH) + 1, 2))
            append(pad(calendar.get(Calendar.DAY_OF_MONTH), 2))
            append('-')
            append(pad(calendar.get(Calendar.HOUR_OF_DAY), 2))
            append(pad(calendar.get(Calendar.MINUTE), 2))
            append(pad(calendar.get(Calendar.SECOND), 2))
        }
        return "$stem$MARKER$stamp-$deviceId$extension"
    }

    /**
     * Whether [fileName] is one of these.
     *
     * Load-bearing for sync rather than cosmetic: a conflict copy shares its bytes with the note it
     * was copied from, so a rename matcher that works by content hash would otherwise pair the two
     * and "helpfully" move the original on top of it.
     */
    fun isConflictCopy(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        return PATTERN.containsMatchIn(stem)
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
