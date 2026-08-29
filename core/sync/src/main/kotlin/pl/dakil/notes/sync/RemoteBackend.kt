package pl.dakil.notes.sync

import java.io.InputStream
import java.io.OutputStream

/** A path from the sync root, `/`-separated. `""` is the root itself. */
@JvmInline
value class RemotePath(val value: String) {
    val name: String get() = value.substringAfterLast('/')
    val parent: String get() = if ('/' in value) value.substringBeforeLast('/') else ""
    override fun toString(): String = value
}

/** Opaque to the planner. Drive puts a fileId here, WebDAV puts the href. */
@JvmInline
value class RemoteId(val value: String)

/** Opaque version token: Drive's `version`, WebDAV's `getetag`. Empty when the server offers none. */
@JvmInline
value class RemoteVersion(val value: String)

data class RemoteEntry(
    val id: RemoteId,
    val path: RemotePath,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    /** The *remote's* clock. Advisory — used to pick a winner, never to detect a change. */
    val modifiedAt: Long,
    val version: RemoteVersion,
)

data class RemoteCapabilities(
    /** Whether a failed precondition is guaranteed to fail the write. Drive: false. */
    val conditionalWrite: Boolean,
    /** Whether a move preserves [RemoteId]. Drive: true. WebDAV: false — the href is the path. */
    val idSurvivesMove: Boolean,
    /** Whether two siblings may share a name. Drive: true. */
    val duplicateNamesPossible: Boolean,
)

/**
 * Everything under the sync root, and whether that is actually everything.
 *
 * [complete] is the contract that stops a failed listing being read as "the user deleted it all" —
 * the same mistake `SafNoteStore.list` used to make locally, with the same consequence.
 */
data class RemoteListing(
    val entries: List<RemoteEntry>,
    /** Siblings that resolved to a path already taken. Never synced; reported instead. */
    val duplicates: List<RemoteEntry> = emptyList(),
    val complete: Boolean = true,
    val failure: String? = null,
)

sealed interface RemoteWrite {
    data class Ok(val entry: RemoteEntry) : RemoteWrite

    /** Someone else wrote since the version we held. Replan this path. */
    data class VersionConflict(val current: RemoteEntry?) : RemoteWrite

    data class Failed(val reason: String, val retryable: Boolean) : RemoteWrite
}

/**
 * What the engine needs from a remote, and no more.
 *
 * Deliberately small, and shaped so Drive's opaque file ids and WebDAV's paths both fit behind it:
 * the planner is written against [RemotePath] alone, because a logical path is the one handle a
 * `.md` file, a `.daknote`, a Drive fileId and a WebDAV href all share.
 */
interface RemoteBackend {

    /** Stable key for the state file. `Locale.ROOT` lower case, per the house rule. */
    val backendId: String

    val capabilities: RemoteCapabilities

    /** Creates the sync root if it is not there, and returns it. */
    suspend fun ensureRoot(): RemoteId

    suspend fun listTree(): RemoteListing

    suspend fun <T> read(id: RemoteId, body: (InputStream) -> T): T

    suspend fun create(
        parent: RemotePath,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite

    suspend fun update(
        id: RemoteId,
        expect: RemoteVersion?,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite

    suspend fun delete(id: RemoteId, expect: RemoteVersion?): RemoteWrite

    suspend fun createDirectory(parent: RemotePath, name: String): RemoteWrite

    suspend fun move(id: RemoteId, from: RemotePath, to: RemotePath): RemoteWrite

    /** Re-reads one entry, so a backend without conditional writes can verify before overwriting. */
    suspend fun stat(id: RemoteId): RemoteEntry?
}

/** Supplies the bearer token, and is told when the server rejected one. */
interface TokenProvider {
    suspend fun bearer(): String?
    suspend fun invalidate()
}
