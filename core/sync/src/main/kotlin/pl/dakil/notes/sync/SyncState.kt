package pl.dakil.notes.sync

import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonValue
import pl.dakil.notes.model.json.JsonWriter

/**
 * What the last successful sync left behind for one note.
 *
 * [contentHash] is the authority for "did this change"; [localModified] and [localSize] are only a
 * cheap pre-filter so an unchanged file is never re-read, and [remoteModified] only ever breaks a
 * tie. Timestamps are not evidence of change: SAF providers report seconds, some report zero, and
 * two devices' clocks disagree.
 */
data class NoteRecord(
    val path: String,
    /** `NoteMeta.id` for a `.daknote`; empty for a `.md`, which has nowhere to keep one. */
    val noteId: String,
    val localModified: Long,
    val localSize: Long,
    val contentHash: String,
    val remoteId: String,
    val remoteVersion: String,
    val remoteModified: Long,
    val remoteSize: Long,
)

/**
 * The one thing in this feature that is a source of truth rather than a cache.
 *
 * It must not live in `NoteIndex`, whose `onUpgrade` *and* `onDowngrade` both drop and recreate
 * because it is a declared disposable cache. Losing this does not cost a rebuild, it costs
 * correctness: without a record there is no way to tell "deleted" from "never seen", so every
 * remote file looks new, deleted notes come back, and every changed file looks like a first-sync
 * collision.
 *
 * There are deliberately **no tombstones**. A record *is* one: a path is deleted on a side exactly
 * when a record exists and that side's complete listing does not contain it. The multi-device case
 * works without shared state — the device that has not synced yet still holds both the file and the
 * record, and reads the missing remote copy as the deletion it is.
 */
data class SyncState(
    val version: Int = VERSION,
    val backendId: String = "",
    val remoteRoot: String = "",
    /** The library root these records were taken against; a different one is not adopted. */
    val localRoot: String = "",
    val deviceId: String = "",
    val lastSyncAt: Long = 0L,
    /** Learned from what the server stamps on an upload. Positive means the remote runs ahead. */
    val clockSkewMs: Long = 0L,
    /** Directory path to remote id, for folders that existed on both sides. */
    val folders: Map<String, String> = emptyMap(),
    val notes: Map<String, NoteRecord> = emptyMap(),
    val unknown: JsonObject = JsonObject.EMPTY,
) {
    /** Secondary index for rename matching. Only `.daknote`s appear here. */
    val byNoteId: Map<String, NoteRecord> by lazy {
        notes.values.filter { it.noteId.isNotEmpty() }.associateBy { it.noteId }
    }

    companion object {
        const val VERSION = 1
    }
}

/**
 * Reads and writes [SyncState] with the house JSON DOM.
 *
 * Unknown top-level keys are carried through in order, the same forward-compatibility rule the
 * `.daknote` container follows — a newer build's state file opened by an older one must not lose
 * the newer build's bookkeeping.
 */
object SyncStateCodec {

    private val KNOWN = setOf(
        "version", "backend", "remoteRoot", "localRoot", "deviceId",
        "lastSyncAt", "clockSkewMs", "folders", "notes",
    )

    fun write(state: SyncState): String {
        var root = JsonObject.EMPTY
            .with("version", state.version)
            .with("backend", state.backendId)
            .with("remoteRoot", state.remoteRoot)
            .with("localRoot", state.localRoot)
            .with("deviceId", state.deviceId)
            .with("lastSyncAt", state.lastSyncAt)
            .with("clockSkewMs", state.clockSkewMs)

        var folders = JsonObject.EMPTY
        // Sorted so the file is byte-stable between runs that changed nothing — the same property
        // `DakNoteWriter` keeps, and for the same reason: this file may itself sit in a synced folder.
        for (path in state.folders.keys.sorted()) folders = folders.with(path, state.folders.getValue(path))
        root = root.with("folders", folders)

        val notes = state.notes.keys.sorted().map { path ->
            val r = state.notes.getValue(path)
            JsonObject.EMPTY
                .with("path", r.path)
                .with("noteId", r.noteId)
                .with("localModified", r.localModified)
                .with("localSize", r.localSize)
                .with("hash", r.contentHash)
                .with("remoteId", r.remoteId)
                .with("remoteVersion", r.remoteVersion)
                .with("remoteModified", r.remoteModified)
                .with("remoteSize", r.remoteSize) as JsonValue
        }
        root = root.with("notes", JsonArray(notes))
        return JsonWriter.write(root.withDefaults(state.unknown))
    }

    /**
     * Reads a state file, or returns null if it cannot be trusted.
     *
     * Null rather than a best effort on purpose. A half-read state is worse than none: an empty
     * state makes the next pass a first sync, which by construction deletes nothing, while a state
     * missing half its records would read those notes as deleted on both sides.
     */
    fun read(text: String): SyncState? {
        val root = runCatching { JsonReader.parseObject(text) }.getOrNull() ?: return null
        val version = root.int("version", -1)
        // A file from a future version is not guessed at, for the same reason `minReaderVersion`
        // gates the container: bookkeeping half-understood is bookkeeping that deletes things.
        if (version != SyncState.VERSION) return null

        val folders = LinkedHashMap<String, String>()
        root.obj("folders")?.forEach { path, id ->
            (id as? pl.dakil.notes.model.json.JsonString)?.let { folders[path] = it.value }
        }

        val notes = LinkedHashMap<String, NoteRecord>()
        root.array("notes")?.items?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val path = o.string("path")
            if (path.isEmpty()) return@forEach
            notes[path] = NoteRecord(
                path = path,
                noteId = o.string("noteId"),
                localModified = o.long("localModified"),
                localSize = o.long("localSize"),
                contentHash = o.string("hash"),
                remoteId = o.string("remoteId"),
                remoteVersion = o.string("remoteVersion"),
                remoteModified = o.long("remoteModified"),
                remoteSize = o.long("remoteSize"),
            )
        }

        var unknown = JsonObject.EMPTY
        root.forEach { key, value -> if (key !in KNOWN) unknown = unknown.with(key, value) }

        return SyncState(
            version = version,
            backendId = root.string("backend"),
            remoteRoot = root.string("remoteRoot"),
            localRoot = root.string("localRoot"),
            deviceId = root.string("deviceId"),
            lastSyncAt = root.long("lastSyncAt"),
            clockSkewMs = root.long("clockSkewMs"),
            folders = folders,
            notes = notes,
            unknown = unknown,
        )
    }
}
