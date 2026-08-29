package pl.dakil.notes.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The instant the fakes and the engine agree is "now", so no artificial skew creeps in. */
internal const val FAKE_NOW = 1_787_926_353_000L

internal fun hashOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** An in-memory device side, recording the order operations were applied in. */
internal class FakeLocalMirror(
    private val files: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
) : LocalMirror {

    val log = ArrayList<String>()
    var open: Set<String> = emptySet()
    var reindexed = 0
    var modifiedAt: Long = FAKE_NOW

    fun put(path: String, body: String) { files[path] = body.toByteArray() }
    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)
    fun paths(): Set<String> = files.keys.toSet()

    override suspend fun list() = LocalListing(
        files.map { (path, bytes) ->
            LocalEntry(path, false, bytes.size.toLong(), modifiedAt, contentHash = hashOf(bytes))
        }
    )

    override suspend fun <T> read(path: String, body: (InputStream) -> T): T =
        body(ByteArrayInputStream(files.getValue(path)))

    override suspend fun write(path: String, sizeBytes: Long, body: (OutputStream) -> Unit): Boolean {
        val out = ByteArrayOutputStream()
        body(out)
        files[path] = out.toByteArray()
        log += "write:$path"
        return true
    }

    override suspend fun delete(path: String, expectHash: String): Boolean {
        files.remove(path)
        log += "delete:$path"
        return true
    }

    override suspend fun move(from: String, to: String): Boolean {
        files.remove(from)?.let { files[to] = it }
        log += "move:$from->$to"
        return true
    }

    override suspend fun createDirectory(path: String): Boolean { log += "mkdir:$path"; return true }

    override suspend fun copyAside(from: String, to: String): Boolean {
        files[from]?.let { files[to] = it.copyOf() }
        log += "aside:$to"
        return true
    }

    override suspend fun stat(path: String): LocalEntry? = files[path]?.let {
        LocalEntry(path, false, it.size.toLong(), modifiedAt, contentHash = hashOf(it))
    }

    override suspend fun openPaths(): Set<String> = open
    override suspend fun awaitIdle() = Unit
    override suspend fun reindex() { reindexed++ }
}

/** An in-memory remote with versions, injectable failures and a settable clock. */
internal class FakeRemoteBackend : RemoteBackend {

    private val files = LinkedHashMap<String, ByteArray>()
    private val versions = LinkedHashMap<String, Int>()
    val log = ArrayList<String>()
    var listingComplete = true
    var remoteNow = FAKE_NOW
    var failWritesFor: String? = null

    override val backendId = "fake"
    override val capabilities = RemoteCapabilities(true, idSurvivesMove = true, duplicateNamesPossible = false)

    fun put(path: String, body: String) {
        files[path] = body.toByteArray()
        versions[path] = (versions[path] ?: 0) + 1
    }

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)
    fun paths(): Set<String> = files.keys.toSet()

    override suspend fun ensureRoot() = RemoteId("root")

    override suspend fun listTree() = RemoteListing(
        files.map { (path, bytes) -> entryFor(path, bytes) },
        complete = listingComplete,
    )

    private fun entryFor(path: String, bytes: ByteArray) = RemoteEntry(
        RemoteId(path), RemotePath(path), false, bytes.size.toLong(), remoteNow,
        RemoteVersion(versions[path]?.toString() ?: "1"),
    )

    override suspend fun <T> read(id: RemoteId, body: (InputStream) -> T): T =
        body(ByteArrayInputStream(files.getValue(id.value)))

    override suspend fun create(
        parent: RemotePath, name: String, mimeType: String, sizeBytes: Long, body: (OutputStream) -> Unit,
    ): RemoteWrite {
        val path = if (parent.value.isEmpty()) name else "${parent.value}/$name"
        return writeAt(path, body)
    }

    override suspend fun update(
        id: RemoteId, expect: RemoteVersion?, sizeBytes: Long, body: (OutputStream) -> Unit,
    ): RemoteWrite {
        if (expect != null && expect.value.isNotEmpty() && versions[id.value]?.toString() != expect.value) {
            return RemoteWrite.VersionConflict(files[id.value]?.let { entryFor(id.value, it) })
        }
        return writeAt(id.value, body)
    }

    private fun writeAt(path: String, body: (OutputStream) -> Unit): RemoteWrite {
        if (failWritesFor == path) return RemoteWrite.Failed("injected", retryable = true)
        val out = ByteArrayOutputStream()
        body(out)
        files[path] = out.toByteArray()
        versions[path] = (versions[path] ?: 0) + 1
        log += "put:$path"
        return RemoteWrite.Ok(entryFor(path, files.getValue(path)))
    }

    override suspend fun delete(id: RemoteId, expect: RemoteVersion?): RemoteWrite {
        files.remove(id.value)
        log += "delete:${id.value}"
        return RemoteWrite.Ok(RemoteEntry(id, RemotePath(id.value), false, 0, 0, RemoteVersion("")))
    }

    override suspend fun createDirectory(parent: RemotePath, name: String): RemoteWrite {
        val path = if (parent.value.isEmpty()) name else "${parent.value}/$name"
        log += "mkdir:$path"
        return RemoteWrite.Ok(RemoteEntry(RemoteId(path), RemotePath(path), true, 0, 0, RemoteVersion("")))
    }

    override suspend fun move(id: RemoteId, from: RemotePath, to: RemotePath): RemoteWrite {
        files.remove(id.value)?.let { files[to.value] = it }
        versions[to.value] = (versions[id.value] ?: 1)
        log += "move:${from.value}->${to.value}"
        return RemoteWrite.Ok(entryFor(to.value, files[to.value] ?: ByteArray(0)))
    }

    override suspend fun stat(id: RemoteId): RemoteEntry? = files[id.value]?.let { entryFor(id.value, it) }
}

internal class FakeStateStore : SyncStateStore {
    private val saved = LinkedHashMap<String, SyncState>()
    var saves = 0
    override suspend fun load(key: String): SyncState? = saved[key]
    override suspend fun save(key: String, state: SyncState) { saved[key] = state; saves++ }
}
