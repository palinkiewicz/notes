package pl.dakil.notes.sync

import java.io.InputStream
import java.io.OutputStream

/**
 * The device side of a sync, as the engine needs it.
 *
 * An interface rather than a `NoteStore` because the engine must go through the layer that owns the
 * write lock and the search index, not around it. Implemented in `:core:data` on top of
 * `NoteRepository`, which is what makes a pulled file land with the index, the save lock and the
 * stamp bookkeeping all in step instead of three reimplementations of each.
 */
interface LocalMirror {
    suspend fun list(): LocalListing
    suspend fun <T> read(path: String, body: (InputStream) -> T): T
    suspend fun write(path: String, sizeBytes: Long, body: (OutputStream) -> Unit): Boolean
    suspend fun delete(path: String, expectHash: String): Boolean
    suspend fun move(from: String, to: String): Boolean
    suspend fun createDirectory(path: String): Boolean
    suspend fun copyAside(from: String, to: String): Boolean
    suspend fun stat(path: String): LocalEntry?
    /** Notes the editor currently has open. Pushed, never pulled. */
    suspend fun openPaths(): Set<String>
    /** Drains any debounced autosave, so a note edited seconds ago is uploaded as the user left it. */
    suspend fun awaitIdle()
    /** Rebuilds the search index after files changed underneath it. */
    suspend fun reindex()
}

interface SyncStateStore {
    suspend fun load(key: String): SyncState?
    suspend fun save(key: String, state: SyncState)
}

data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val moved: Int = 0,
    val conflicts: List<String> = emptyList(),
    val refusals: List<SyncRefusal> = emptyList(),
    val failure: String? = null,
    val shouldRetry: Boolean = false,
) {
    val changed: Int get() = uploaded + downloaded + deleted + moved
}

/**
 * Applies a plan, one action at a time, writing state after each.
 *
 * The ordering the planner produced is load-bearing and is not re-sorted here: every create and
 * update runs before every delete, so an interrupted pass leaves a library with too much in it
 * rather than too little. State is flushed as it goes for the same reason — a job killed at the ten
 * minute mark resumes from what it finished instead of replanning against stale bookkeeping.
 */
class SyncEngine(
    private val local: LocalMirror,
    private val remote: RemoteBackend,
    private val stateStore: SyncStateStore,
    private val deviceId: String,
    private val localRoot: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val stateKey: String get() = "${remote.backendId}-${localRoot.hashCode().toUInt().toString(16)}"

    suspend fun run(
        direction: SyncDirection = SyncDirection.TWO_WAY,
        confirmDeletes: Boolean = false,
    ): SyncOutcome {
        return runCatching { sync(direction, confirmDeletes) }.getOrElse { cause ->
            SyncOutcome(failure = cause.message ?: "Sync failed", shouldRetry = true)
        }
    }

    private suspend fun sync(direction: SyncDirection, confirmDeletes: Boolean): SyncOutcome {
        local.awaitIdle()
        remote.ensureRoot()

        val loaded = stateStore.load(stateKey)
        // State taken against a different library root describes files that are not these files.
        // Starting fresh is safe — a first sync deletes nothing — while adopting it is not.
        var state = when {
            loaded == null -> SyncState(backendId = remote.backendId, localRoot = localRoot, deviceId = deviceId)
            loaded.localRoot != localRoot ->
                SyncState(backendId = remote.backendId, localRoot = localRoot, deviceId = deviceId)
            else -> loaded
        }

        val localListing = local.list()
        val remoteListing = remote.listTree()

        val plan = SyncPlanner.plan(
            localListing,
            remoteListing,
            state,
            SyncPolicy(
                now = clock(),
                deviceId = deviceId,
                direction = direction,
                clockSkewMs = state.clockSkewMs,
                pinnedPaths = local.openPaths(),
                deleteGuardConfirmed = confirmDeletes,
            ),
        )

        var uploaded = 0
        var downloaded = 0
        var deleted = 0
        var moved = 0
        val conflicts = ArrayList<String>()
        var touchedFiles = false

        // Free bookkeeping first: identical bytes on both sides cost nothing but a record.
        for (record in plan.adopt) state = state.copy(notes = state.notes + (record.path to record))
        for (path in plan.forget) state = state.copy(notes = state.notes - path)

        for (action in plan.actions) {
            val applied = runCatching { apply(action, state, conflicts) }.getOrElse { cause ->
                // One failing note must not abandon the rest of the library.
                Applied(state, failure = cause.message)
            }
            state = applied.state
            when (applied.kind) {
                Kind.UPLOAD -> { uploaded++; touchedFiles = true }
                Kind.DOWNLOAD -> { downloaded++; touchedFiles = true }
                Kind.DELETE -> { deleted++; touchedFiles = true }
                Kind.MOVE -> { moved++; touchedFiles = true }
                Kind.NONE -> Unit
            }
            // Written as it goes, not at the end: a half-applied plan whose state was never saved
            // replans from bookkeeping that no longer matches the disk.
            stateStore.save(stateKey, state)
        }

        state = state.copy(lastSyncAt = clock())
        stateStore.save(stateKey, state)
        if (touchedFiles) local.reindex()

        return SyncOutcome(
            uploaded = uploaded,
            downloaded = downloaded,
            deleted = deleted,
            moved = moved,
            conflicts = conflicts,
            refusals = plan.refusals,
        )
    }

    private enum class Kind { UPLOAD, DOWNLOAD, DELETE, MOVE, NONE }

    private data class Applied(val state: SyncState, val kind: Kind = Kind.NONE, val failure: String? = null)

    private suspend fun apply(
        action: SyncAction,
        state: SyncState,
        conflicts: MutableList<String>,
    ): Applied = when (action) {

        is SyncAction.PreserveThen -> {
            // The losing version is copied aside *before* anything overwrites it, and locally
            // rather than only on the remote — the user looks at their device, not at a server.
            val preserved = when (action.loser) {
                Side.REMOTE -> preserveRemote(action)
                Side.LOCAL -> local.copyAside(action.path, action.copyPath)
            }
            if (preserved) conflicts += action.copyPath
            apply(action.then, state, conflicts)
        }

        is SyncAction.CreateRemoteFolder -> {
            val result = remote.createDirectory(RemotePath(action.path).let { RemotePath(it.parent) }, RemotePath(action.path).name)
            if (result is RemoteWrite.Ok) {
                Applied(state.copy(folders = state.folders + (action.path to result.entry.id.value)), Kind.NONE)
            } else {
                Applied(state)
            }
        }

        is SyncAction.CreateLocalFolder -> {
            local.createDirectory(action.path)
            Applied(state)
        }

        is SyncAction.Upload -> {
            val entry = local.stat(action.path)
            if (entry == null) {
                Applied(state)
            } else {
                val bytes = local.read(action.path) { it.readBytes() }
                val path = RemotePath(action.path)
                val result = if (action.remoteId != null) {
                    remote.update(RemoteId(action.remoteId), action.expect?.let(::RemoteVersion), bytes.size.toLong()) {
                        it.write(bytes); it.flush()
                    }
                } else {
                    remote.create(RemotePath(path.parent), path.name, mimeTypeOf(path.name), bytes.size.toLong()) {
                        it.write(bytes); it.flush()
                    }
                }
                when (result) {
                    is RemoteWrite.Ok -> Applied(
                        state.copy(
                            notes = state.notes + (action.path to recordOf(action.path, entry, result.entry)),
                            clockSkewMs = learnSkew(state, result.entry),
                        ),
                        Kind.UPLOAD,
                    )
                    // Not advanced: the next pass sees both sides differing from the record and
                    // writes a conflict copy instead of forcing this write over someone else's.
                    is RemoteWrite.VersionConflict -> Applied(state)
                    is RemoteWrite.Failed -> Applied(state, failure = result.reason)
                }
            }
        }

        is SyncAction.Download -> {
            val bytes = remote.read(action.remote.id) { it.readBytes() }
            val ok = local.write(action.path, bytes.size.toLong()) { it.write(bytes); it.flush() }
            if (!ok) {
                Applied(state)
            } else {
                // Read back before the record is advanced. A truncated write then matches no
                // record, so the next pass treats it as a conflict rather than uploading the
                // truncation over a good remote copy.
                val entry = local.stat(action.path)
                if (entry == null || entry.sizeBytes != bytes.size.toLong()) {
                    Applied(state, Kind.DOWNLOAD)
                } else {
                    Applied(
                        state.copy(notes = state.notes + (action.path to recordOf(action.path, entry, action.remote))),
                        Kind.DOWNLOAD,
                    )
                }
            }
        }

        is SyncAction.MoveRemote -> {
            val result = remote.move(RemoteId(action.remoteId), RemotePath(action.from), RemotePath(action.path))
            if (result is RemoteWrite.Ok) {
                val record = state.notes[action.from]
                val moved = record?.copy(
                    path = action.path,
                    remoteId = result.entry.id.value,
                    remoteVersion = result.entry.version.value,
                )
                Applied(
                    state.copy(notes = state.notes - action.from + listOfNotNull(moved?.let { action.path to it })),
                    Kind.MOVE,
                )
            } else {
                Applied(state)
            }
        }

        is SyncAction.MoveLocal -> {
            if (local.move(action.from, action.path)) {
                val record = state.notes[action.from]?.copy(path = action.path)
                Applied(
                    state.copy(notes = state.notes - action.from + listOfNotNull(record?.let { action.path to it })),
                    Kind.MOVE,
                )
            } else {
                Applied(state)
            }
        }

        is SyncAction.DeleteRemote -> {
            val result = remote.delete(RemoteId(action.remoteId), action.expect?.let(::RemoteVersion))
            if (result is RemoteWrite.Ok) {
                Applied(state.copy(notes = state.notes - action.path), Kind.DELETE)
            } else {
                Applied(state)
            }
        }

        is SyncAction.DeleteLocal -> {
            if (local.delete(action.path, action.expectHash)) {
                Applied(state.copy(notes = state.notes - action.path), Kind.DELETE)
            } else {
                Applied(state)
            }
        }

        is SyncAction.DeleteRemoteFolder -> {
            remote.delete(RemoteId(action.remoteId), null)
            Applied(state.copy(folders = state.folders - action.path))
        }

        is SyncAction.DeleteLocalFolder -> {
            local.delete(action.path, "")
            Applied(state.copy(folders = state.folders - action.path))
        }
    }

    private suspend fun preserveRemote(action: SyncAction.PreserveThen): Boolean {
        val entry = (action.then as? SyncAction.Upload)?.remoteId?.let(::RemoteId) ?: return false
        return runCatching {
            val bytes = remote.read(entry) { it.readBytes() }
            local.write(action.copyPath, bytes.size.toLong()) { it.write(bytes); it.flush() }
        }.getOrDefault(false)
    }

    /**
     * Folds what the server stamped on an upload into a running estimate of the clock difference.
     *
     * No probe file and no extra request: the entry that comes back from a write already carries
     * the remote's idea of "now". A quarter-weight average keeps one odd sample from moving it far.
     */
    private fun learnSkew(state: SyncState, entry: RemoteEntry): Long {
        if (entry.modifiedAt <= 0L) return state.clockSkewMs
        val sample = entry.modifiedAt - clock()
        // Two machines' clocks differ by seconds, or minutes on a bad day. A sample beyond a day
        // is not skew, it is a timestamp this app failed to parse — WebDAV answers with dates in
        // half a dozen shapes, and Drive can hand back a zero. Folding one of those into the
        // estimate would swamp it and hand every future conflict to whichever side the broken
        // number favoured, so it is discarded rather than averaged in.
        if (sample > MAX_SKEW_MS || sample < -MAX_SKEW_MS) return state.clockSkewMs
        val blended = state.clockSkewMs + (sample - state.clockSkewMs) / 4
        return blended.coerceIn(-MAX_SKEW_MS, MAX_SKEW_MS)
    }

    private fun recordOf(path: String, local: LocalEntry, remote: RemoteEntry) = NoteRecord(
        path = path,
        noteId = local.noteId,
        localModified = local.modifiedAt,
        localSize = local.sizeBytes,
        contentHash = local.contentHash,
        remoteId = remote.id.value,
        remoteVersion = remote.version.value,
        remoteModified = remote.modifiedAt,
        remoteSize = remote.sizeBytes,
    )

    private fun mimeTypeOf(name: String): String =
        pl.dakil.notes.format.NoteKind.of(name)?.mimeType ?: "application/octet-stream"

    private companion object {
        const val MAX_SKEW_MS = 24L * 60 * 60 * 1000
    }
}
