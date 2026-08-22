package pl.dakil.notes.model

import pl.dakil.notes.model.json.JsonObject

@JvmInline
value class NoteId(val raw: String) {
    override fun toString(): String = raw
}

/**
 * Everything in `manifest.json`.
 *
 * [id], [revision] and [modified] exist specifically to make a future sync provider possible
 * without touching the format: [id] is stable across rename and move so a remote copy can be
 * recognised after the user reorganises their folders, and [revision] gives a cheap
 * happened-before test for conflict detection.
 */
data class NoteMeta(
    val id: NoteId,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val created: Long = 0L,
    val modified: Long = 0L,
    val revision: Long = 0L,
    /** How the note opens: as discrete pages, or as one continuous scroll. */
    val view: ViewMode = ViewMode.PAGED,
    val unknown: JsonObject = JsonObject.EMPTY,
)

/**
 * A whole `.daknote` document in memory: metadata plus one continuous [Sheet].
 *
 * [readOnly] is set when the file declares a `minReaderVersion` above what this build implements.
 * The editor honours it by refusing to save, which is what makes preserving unknown content safe:
 * an older build can never silently truncate a file it only partly understands.
 *
 * [foreignEntries] holds every ZIP entry the reader did not recognise, byte for byte, so the
 * writer can put them back.
 */
data class Note(
    val meta: NoteMeta,
    val sheet: Sheet = Sheet(),
    val readOnly: Boolean = false,
    val foreignEntries: Map<String, ByteArray> = emptyMap(),
) {
    /** The text handed to the search indexer: every box's words, in reading order. */
    fun plainText(): String = sheet.textInReadingOrder()

    fun withSheet(sheet: Sheet): Note = copy(sheet = sheet)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Note) return false
        if (meta != other.meta || sheet != other.sheet || readOnly != other.readOnly) return false
        if (foreignEntries.keys != other.foreignEntries.keys) return false
        return foreignEntries.all { (k, v) -> v.contentEquals(other.foreignEntries[k]) }
    }

    override fun hashCode(): Int {
        var h = meta.hashCode()
        h = 31 * h + sheet.hashCode()
        h = 31 * h + readOnly.hashCode()
        h = 31 * h + foreignEntries.keys.hashCode()
        return h
    }
}
