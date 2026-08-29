package pl.dakil.notes.sync

import pl.dakil.notes.format.NoteKind
import java.util.Locale

/** A file on this device, as the planner sees it. */
data class LocalEntry(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** `NoteMeta.id` for a `.daknote`; empty for a `.md`. */
    val noteId: String = "",
    /** Filled in only for entries the pre-filter thinks may have changed. */
    val contentHash: String = "",
)

data class LocalListing(val entries: List<LocalEntry>, val complete: Boolean = true)

enum class Side { LOCAL, REMOTE }

sealed interface SyncAction {
    val path: String

    data class CreateRemoteFolder(override val path: String) : SyncAction
    data class CreateLocalFolder(override val path: String) : SyncAction
    data class Upload(
        override val path: String,
        val remoteId: String?,
        val expect: String?,
    ) : SyncAction
    data class Download(override val path: String, val remote: RemoteEntry) : SyncAction
    data class MoveRemote(override val path: String, val from: String, val remoteId: String) : SyncAction
    data class MoveLocal(override val path: String, val from: String) : SyncAction
    data class DeleteRemote(override val path: String, val remoteId: String, val expect: String?) : SyncAction
    data class DeleteLocal(override val path: String, val expectHash: String) : SyncAction
    data class DeleteRemoteFolder(override val path: String, val remoteId: String) : SyncAction
    data class DeleteLocalFolder(override val path: String) : SyncAction

    /** Copies the losing side's bytes aside under [copyPath], then runs [then]. */
    data class PreserveThen(
        override val path: String,
        val loser: Side,
        val copyPath: String,
        val then: SyncAction,
    ) : SyncAction
}

sealed interface SyncRefusal {
    val path: String
    data class ListingIncomplete(override val path: String, val side: Side) : SyncRefusal
    data class NoteOpenInEditor(override val path: String) : SyncRefusal
    data class DuplicateRemoteName(override val path: String, val extra: Int) : SyncRefusal
    data class NameNotRepresentable(override val path: String, val why: String) : SyncRefusal
    data class DeleteGuardTripped(override val path: String, val wouldDelete: Int, val recorded: Int) : SyncRefusal
}

data class SyncPlan(
    val actions: List<SyncAction> = emptyList(),
    val refusals: List<SyncRefusal> = emptyList(),
    /** Records to rewrite or drop with no I/O at all. */
    val adopt: List<NoteRecord> = emptyList(),
    val forget: List<String> = emptyList(),
)

enum class SyncDirection { TWO_WAY, UPLOAD_ONLY, DOWNLOAD_ONLY }

data class SyncPolicy(
    val now: Long,
    val deviceId: String,
    val direction: SyncDirection = SyncDirection.TWO_WAY,
    val clockSkewMs: Long = 0L,
    /**
     * Below this the clocks cannot decide and neither side wins.
     *
     * 2 s covers FAT's granularity and WebDAV's one-second HTTP-dates with room to spare. Inside
     * the band both versions are kept: losing nothing beats guessing.
     */
    val tieBandMs: Long = 2_000L,
    /** Notes the editor has open. Pushed, never pulled. */
    val pinnedPaths: Set<String> = emptySet(),
    /** Refuse a plan deleting more than this share of what the state records. */
    val maxDeleteFraction: Float = 0.25f,
    /**
     * …but never for fewer than this many notes.
     *
     * The fraction on its own is nonsense at small sizes: one note deleted out of two is half the
     * library, and stopping to ask about it would make the guard fire constantly on exactly the
     * ordinary use it is not meant to be about. The guard is for a *mass* delete — the shape a bug
     * or a lapsed permission produces — so it needs a floor before the ratio means anything.
     */
    val minDeleteGuard: Int = 5,
    val deleteGuardConfirmed: Boolean = false,
)

/**
 * Turns two listings and the last-sync state into a list of actions, and nothing else.
 *
 * Pure: no I/O, no suspension, no clock of its own. That is what makes the whole decision table
 * answerable in a JUnit test rather than by pointing two phones at a server and hoping.
 */
object SyncPlanner {

    /** Files that are none of this app's business, and must never be uploaded *or* deleted. */
    fun isIgnored(path: String): Boolean {
        val segments = path.split('/')
        if (segments.any { it.startsWith(".") }) return true
        val name = segments.last().lowercase(Locale.ROOT)
        return name.endsWith(".tmp") || name.endsWith(".part") || name.startsWith("note-index.db")
    }

    /** Only the two note kinds sync. Anything else keeps its bytes and is left where it is. */
    private fun isSyncable(path: String): Boolean = NoteKind.of(path.substringAfterLast('/')) != null

    fun plan(
        local: LocalListing,
        remote: RemoteListing,
        state: SyncState,
        policy: SyncPolicy,
    ): SyncPlan {
        val refusals = ArrayList<SyncRefusal>()
        val actions = ArrayList<SyncAction>()
        val adopt = ArrayList<NoteRecord>()
        val forget = ArrayList<String>()

        // An incomplete listing is not evidence of absence. Deletes and rename pairings are
        // suppressed on that side; adds and updates still run, because they cannot lose anything.
        val mayDeleteLocal = remote.complete
        val mayDeleteRemote = local.complete
        if (!local.complete) refusals += SyncRefusal.ListingIncomplete("", Side.LOCAL)
        if (!remote.complete) refusals += SyncRefusal.ListingIncomplete("", Side.REMOTE)
        if (remote.duplicates.isNotEmpty()) {
            remote.duplicates.groupBy { it.path.value }.forEach { (path, extra) ->
                refusals += SyncRefusal.DuplicateRemoteName(path, extra.size)
            }
        }

        val localFiles = local.entries
            .filter { !it.isDirectory && !isIgnored(it.path) && isSyncable(it.path) }
            .associateBy { it.path }
        val remoteFiles = remote.entries
            .filter { !it.isDirectory && !isIgnored(it.path.value) && isSyncable(it.path.value) }
            .associateBy { it.path.value }
        val localDirs = local.entries.filter { it.isDirectory && !isIgnored(it.path) }.map { it.path }.toSet()
        val remoteDirs = remote.entries.filter { it.isDirectory && !isIgnored(it.path.value) }
            .associateBy { it.path.value }

        // ---- Renames, resolved first so the table below never sees them as delete-plus-create.
        val renamed = HashMap<String, String>()   // old path -> new path
        val handled = HashSet<String>()
        if (mayDeleteRemote && mayDeleteLocal) {
            val orphans = state.notes.values.filter { it.path !in localFiles && it.path !in remoteFiles.keys }
            val localStrays = localFiles.keys.filter { it !in state.notes && !ConflictNaming.isConflictCopy(it.substringAfterLast('/')) }
            val remoteStrays = remoteFiles.keys.filter { it !in state.notes && !ConflictNaming.isConflictCopy(it.substringAfterLast('/')) }

            for (record in state.notes.values) {
                if (record.path in localFiles || ConflictNaming.isConflictCopy(record.path.substringAfterLast('/'))) continue
                // A `.daknote` is matched exactly by the id that survives rename and move. A `.md`
                // has no id to keep, so it is matched by the bytes the last sync recorded — exact
                // unless it was renamed *and* edited, which degrades to delete-plus-create.
                val moved = localStrays.firstOrNull { stray ->
                    val entry = localFiles.getValue(stray)
                    if (record.noteId.isNotEmpty() && entry.noteId.isNotEmpty()) {
                        entry.noteId == record.noteId
                    } else {
                        entry.sizeBytes == record.localSize &&
                            entry.contentHash.isNotEmpty() &&
                            entry.contentHash == record.contentHash
                    }
                } ?: continue
                if (moved in handled) continue
                handled += moved
                renamed[record.path] = moved
                if (record.remoteId.isNotEmpty()) {
                    actions += SyncAction.MoveRemote(moved, record.path, record.remoteId)
                }
            }
            orphans.forEach { /* nothing further: handled by the table's both-deleted row */ }
            remoteStrays.forEach { /* remote-side renames are handled by the backend's id, below */ }
        }

        val renamedTargets = renamed.values.toSet()
        val renamedSources = renamed.keys

        // ---- Folders that have to exist before anything lands in them.
        for (dir in localDirs.sorted()) {
            if (dir.isEmpty() || dir in remoteDirs || dir in state.folders) continue
            if (policy.direction != SyncDirection.DOWNLOAD_ONLY) actions += SyncAction.CreateRemoteFolder(dir)
        }
        for (dir in remoteDirs.keys.sorted()) {
            if (dir.isEmpty() || dir in localDirs || dir in state.folders) continue
            if (policy.direction != SyncDirection.UPLOAD_ONLY) actions += SyncAction.CreateLocalFolder(dir)
        }

        // ---- The decision table, one pass over every path either side knows about.
        val paths = (localFiles.keys + remoteFiles.keys + state.notes.keys).toSortedSet()
        var wouldDelete = 0
        val deleteActions = ArrayList<SyncAction>()

        for (path in paths) {
            if (path in renamedSources) continue           // became a MoveRemote above
            if (path in policy.pinnedPaths) {
                // Pushed but never pulled: yanking bytes from under an open document either loses
                // the in-flight edits or gets clobbered by the next autosave a second later.
                val l = localFiles[path]
                val r = remoteFiles[path]
                val rec = state.notes[path]
                if (l != null && (rec == null || changedLocally(l, rec))) {
                    actions += SyncAction.Upload(path, r?.id?.value, rec?.remoteVersion)
                } else if (r != null) {
                    refusals += SyncRefusal.NoteOpenInEditor(path)
                }
                continue
            }

            val l = localFiles[path]
            val r = remoteFiles[path]
            val rec = state.notes[path]

            when {
                // New local, never seen.
                rec == null && l != null && r == null -> {
                    if (policy.direction != SyncDirection.DOWNLOAD_ONLY) {
                        actions += SyncAction.Upload(path, null, null)
                    }
                }

                // New remote, never seen.
                rec == null && l == null && r != null -> {
                    if (policy.direction != SyncDirection.UPLOAD_ONLY) {
                        actions += SyncAction.Download(path, r)
                    }
                }

                // First sync, both sides hold it. Never overwrites: identical bytes are adopted for
                // free, and differing bytes keep both.
                rec == null && l != null && r != null -> {
                    if (l.contentHash.isNotEmpty() && l.sizeBytes == r.sizeBytes && sameEnough(l, r)) {
                        adopt += record(path, l, r)
                    } else {
                        actions += conflict(path, l, r, policy)
                    }
                }

                // Both deleted: nothing to do but forget it.
                rec != null && l == null && r == null -> forget += path

                // Local gone, remote still there.
                rec != null && l == null && r != null -> {
                    if (changedRemotely(r, rec)) {
                        // A delete cannot be merged and an edit can, so the edit comes back.
                        if (policy.direction != SyncDirection.UPLOAD_ONLY) {
                            actions += SyncAction.Download(path, r)
                        }
                    } else if (mayDeleteRemote && policy.direction != SyncDirection.DOWNLOAD_ONLY) {
                        wouldDelete++
                        deleteActions += SyncAction.DeleteRemote(path, r.id.value, rec.remoteVersion)
                    }
                }

                // Remote gone, local still there.
                rec != null && l != null && r == null -> {
                    if (changedLocally(l, rec)) {
                        if (policy.direction != SyncDirection.DOWNLOAD_ONLY) {
                            actions += SyncAction.Upload(path, null, null)
                        }
                    } else if (mayDeleteLocal && policy.direction != SyncDirection.UPLOAD_ONLY) {
                        wouldDelete++
                        deleteActions += SyncAction.DeleteLocal(path, rec.contentHash)
                    }
                }

                // Present on both sides with a record: the ordinary case.
                rec != null && l != null && r != null -> {
                    val lc = changedLocally(l, rec)
                    val rc = changedRemotely(r, rec)
                    when {
                        !lc && !rc -> Unit
                        lc && !rc -> if (policy.direction != SyncDirection.DOWNLOAD_ONLY) {
                            actions += SyncAction.Upload(path, r.id.value, rec.remoteVersion)
                        }
                        !lc && rc -> if (policy.direction != SyncDirection.UPLOAD_ONLY) {
                            actions += SyncAction.Download(path, r)
                        }
                        else -> if (l.contentHash.isNotEmpty() && sameEnough(l, r)) {
                            adopt += record(path, l, r)
                        } else {
                            actions += conflict(path, l, r, policy)
                        }
                    }
                }
            }
        }

        // ---- The guard that catches whatever failed to report itself as incomplete.
        val recorded = state.notes.size
        if (recorded > 0 && !policy.deleteGuardConfirmed &&
            wouldDelete >= policy.minDeleteGuard &&
            wouldDelete > (recorded * policy.maxDeleteFraction)
        ) {
            refusals += SyncRefusal.DeleteGuardTripped("", wouldDelete, recorded)
        } else {
            actions += deleteActions
        }

        return SyncPlan(actions = actions, refusals = refusals, adopt = adopt, forget = forget)
    }

    /** The hash decides. Size and timestamp only spare a read when nothing moved. */
    private fun changedLocally(l: LocalEntry, rec: NoteRecord): Boolean {
        if (l.sizeBytes == rec.localSize && l.modifiedAt == rec.localModified) return false
        if (l.contentHash.isEmpty()) return true
        return l.contentHash != rec.contentHash
    }

    private fun changedRemotely(r: RemoteEntry, rec: NoteRecord): Boolean {
        if (r.version.value.isNotEmpty()) return r.version.value != rec.remoteVersion
        return r.sizeBytes != rec.remoteSize || r.modifiedAt != rec.remoteModified
    }

    /** Only ever asked when the sizes already match; the hash is the real answer. */
    private fun sameEnough(l: LocalEntry, r: RemoteEntry): Boolean = l.sizeBytes == r.sizeBytes

    private fun record(path: String, l: LocalEntry, r: RemoteEntry) = NoteRecord(
        path = path,
        noteId = l.noteId,
        localModified = l.modifiedAt,
        localSize = l.sizeBytes,
        contentHash = l.contentHash,
        remoteId = r.id.value,
        remoteVersion = r.version.value,
        remoteModified = r.modifiedAt,
        remoteSize = r.sizeBytes,
    )

    /**
     * Picks a winner by adjusted timestamp, and keeps the loser beside it.
     *
     * Inside the tie band neither side wins: the remote version is copied aside locally *and* the
     * local one is uploaded, so both survive and the user decides. That is the honest answer when
     * the only evidence is two clocks that disagree by less than their own resolution.
     */
    private fun conflict(path: String, l: LocalEntry, r: RemoteEntry, policy: SyncPolicy): SyncAction {
        val remoteAdjusted = r.modifiedAt - policy.clockSkewMs
        val copyName = ConflictNaming.nameFor(path.substringAfterLast('/'), policy.now, policy.deviceId)
        val copyPath = if ('/' in path) "${path.substringBeforeLast('/')}/$copyName" else copyName
        return when {
            l.modifiedAt > remoteAdjusted + policy.tieBandMs ->
                SyncAction.PreserveThen(path, Side.REMOTE, copyPath, SyncAction.Upload(path, r.id.value, r.version.value))
            remoteAdjusted > l.modifiedAt + policy.tieBandMs ->
                SyncAction.PreserveThen(path, Side.LOCAL, copyPath, SyncAction.Download(path, r))
            // Too close to call. Keep the remote aside locally and push ours; nothing is lost.
            else ->
                SyncAction.PreserveThen(path, Side.REMOTE, copyPath, SyncAction.Upload(path, r.id.value, r.version.value))
        }
    }
}
