package pl.dakil.notes.sync.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.sync.RemoteBackend
import pl.dakil.notes.sync.RemoteCapabilities
import pl.dakil.notes.sync.RemoteEntry
import pl.dakil.notes.sync.RemoteId
import pl.dakil.notes.sync.RemoteListing
import pl.dakil.notes.sync.RemotePath
import pl.dakil.notes.sync.RemoteVersion
import pl.dakil.notes.sync.RemoteWrite
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** What the user typed into the WebDAV form. */
data class WebDavAccount(
    /** The collection the notes live in, e.g. `https://cloud.example.com/remote.php/dav/files/me/Notes`. */
    val baseUrl: String,
    val username: String,
    val password: String,
)

/**
 * WebDAV over `HttpURLConnection`, for Nextcloud, ownCloud, Synology, mailbox.org, Koofr and any
 * self-hosted server.
 *
 * The cheapest real cloud tier there is and the least encumbered: no client id to register, no
 * verification to pass, no account with anybody. It is implemented before Drive on purpose — it
 * exercises the whole engine without an OAuth flow standing in the way of every test.
 */
class WebDavBackend(
    private val account: WebDavAccount,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteBackend {

    override val backendId = "webdav"

    override val capabilities = RemoteCapabilities(
        // `If-Match` is part of the spec and honoured by every server worth using.
        conditionalWrite = true,
        // The href *is* the path, so a move invalidates the id by definition.
        idSurvivesMove = false,
        duplicateNamesPossible = false,
    )

    private val root = account.baseUrl.trimEnd('/')

    private val rootPath: String = WebDavXml.pathOf(root).trimEnd('/')

    private val auth: String = "Basic " + Base64.getEncoder()
        .encodeToString("${account.username}:${account.password}".toByteArray(Charsets.UTF_8))

    private fun headers(extra: Map<String, String> = emptyMap()) =
        mapOf("Authorization" to auth) + extra

    private fun urlFor(path: RemotePath): String =
        if (path.value.isEmpty()) root
        else root + "/" + path.value.split('/').joinToString("/") { Http.encodePathSegment(it) }

    override suspend fun ensureRoot(): RemoteId = withContext(io) {
        // MKCOL on an existing collection answers 405, which is a success for our purposes.
        val response = Http.request("MKCOL", root, headers())
        if (!response.isSuccess && response.code != 405) {
            throw HttpException(response.code, "Could not open ${account.baseUrl}")
        }
        RemoteId(rootPath)
    }

    override suspend fun listTree(): RemoteListing = withContext(io) {
        val entries = ArrayList<RemoteEntry>()
        val queue = ArrayDeque(listOf(RemotePath("")))
        val seen = HashSet<String>()
        var complete = true
        var failure: String? = null

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (!seen.add(dir.value)) continue
            // `Depth: 1` per directory rather than `infinity`: most servers disable infinity, and a
            // request that 403s on a deep tree would look exactly like an empty one.
            val response = runCatching {
                Http.request(
                    "PROPFIND", urlFor(dir),
                    headers(mapOf("Depth" to "1", "Content-Type" to "application/xml")),
                    body = { out -> out.write(PROPFIND_BODY.toByteArray(Charsets.UTF_8)); out.flush() },
                    contentLength = PROPFIND_BODY.toByteArray(Charsets.UTF_8).size.toLong(),
                )
            }.getOrElse {
                complete = false
                failure = it.message
                continue
            }
            if (response.code != 207) {
                complete = false
                failure = "PROPFIND ${dir.value} answered ${response.code}"
                continue
            }
            for (resource in WebDavXml.parseMultiStatus(response.text())) {
                val path = relativePath(resource.href) ?: continue
                if (path.isEmpty() || path == dir.value) continue
                val entry = RemoteEntry(
                    id = RemoteId(WebDavXml.pathOf(resource.href)),
                    path = RemotePath(path),
                    isDirectory = resource.isCollection,
                    sizeBytes = resource.contentLength,
                    modifiedAt = resource.lastModified,
                    version = RemoteVersion(resource.etag),
                )
                entries += entry
                if (entry.isDirectory) queue.addLast(entry.path)
            }
        }
        RemoteListing(entries, complete = complete, failure = failure)
    }

    /** Turns an href into a path relative to the sync root, or null if it is outside it. */
    private fun relativePath(href: String): String? {
        val decoded = java.net.URLDecoder.decode(WebDavXml.pathOf(href), "UTF-8").trimEnd('/')
        if (!decoded.startsWith(rootPath)) return null
        return decoded.removePrefix(rootPath).trim('/')
    }

    override suspend fun <T> read(id: RemoteId, body: (InputStream) -> T): T = withContext(io) {
        var result: T? = null
        val response = Http.request(
            "GET", absolute(id), headers(),
            onBody = { input -> result = body(input) },
        )
        if (!response.isSuccess) throw HttpException(response.code, "GET ${id.value} answered ${response.code}")
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    override suspend fun create(
        parent: RemotePath,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite = put(childPath(parent, name), expect = null, mimeType, sizeBytes, body)

    override suspend fun update(
        id: RemoteId,
        expect: RemoteVersion?,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite = withContext(io) {
        val path = relativePath(id.value) ?: return@withContext RemoteWrite.Failed("Outside the sync root", false)
        put(RemotePath(path), expect, "application/octet-stream", sizeBytes, body)
    }

    private suspend fun put(
        path: RemotePath,
        expect: RemoteVersion?,
        mimeType: String,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite = withContext(io) {
        val extra = HashMap<String, String>()
        extra["Content-Type"] = mimeType
        // The precondition is what turns "overwrite whatever is there" into "overwrite the version
        // I planned against"; without it a write can silently lose an edit made since the listing.
        expect?.value?.takeIf { it.isNotEmpty() }?.let { extra["If-Match"] = "\"$it\"" }

        val response = runCatching {
            Http.request("PUT", urlFor(path), headers(extra), body = body, contentLength = sizeBytes)
        }.getOrElse { return@withContext RemoteWrite.Failed(it.message ?: "PUT failed", retryable = true) }

        when {
            response.code == 412 -> RemoteWrite.VersionConflict(stat(RemoteId(pathOf(path))))
            response.isSuccess -> {
                // Servers differ on whether a PUT answers with an ETag, so the entry is read back
                // rather than assumed. Getting the version wrong here means re-downloading the file
                // on the next pass.
                val entry = stat(RemoteId(pathOf(path)))
                if (entry != null) RemoteWrite.Ok(entry)
                else RemoteWrite.Ok(
                    RemoteEntry(RemoteId(pathOf(path)), path, false, sizeBytes, 0L, RemoteVersion(""))
                )
            }
            else -> RemoteWrite.Failed("PUT answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun delete(id: RemoteId, expect: RemoteVersion?): RemoteWrite = withContext(io) {
        val extra = expect?.value?.takeIf { it.isNotEmpty() }?.let { mapOf("If-Match" to "\"$it\"") } ?: emptyMap()
        val response = Http.request("DELETE", absolute(id), headers(extra))
        when {
            response.code == 412 -> RemoteWrite.VersionConflict(stat(id))
            // Already gone is the outcome that was wanted.
            response.isSuccess || response.code == 404 -> RemoteWrite.Ok(
                RemoteEntry(id, RemotePath(relativePath(id.value).orEmpty()), false, 0, 0, RemoteVersion(""))
            )
            else -> RemoteWrite.Failed("DELETE answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun createDirectory(parent: RemotePath, name: String): RemoteWrite = withContext(io) {
        val path = childPath(parent, name)
        val response = Http.request("MKCOL", urlFor(path), headers())
        if (response.isSuccess || response.code == 405) {
            RemoteWrite.Ok(RemoteEntry(RemoteId(pathOf(path)), path, true, 0, 0, RemoteVersion("")))
        } else {
            RemoteWrite.Failed("MKCOL answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun move(id: RemoteId, from: RemotePath, to: RemotePath): RemoteWrite = withContext(io) {
        val response = Http.request(
            "MOVE", absolute(id),
            // `Overwrite: F` so a move can never destroy something already at the destination.
            headers(mapOf("Destination" to urlFor(to), "Overwrite" to "F")),
        )
        if (response.isSuccess) {
            RemoteWrite.Ok(stat(RemoteId(pathOf(to))) ?: RemoteEntry(RemoteId(pathOf(to)), to, false, 0, 0, RemoteVersion("")))
        } else {
            RemoteWrite.Failed("MOVE answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun stat(id: RemoteId): RemoteEntry? = withContext(io) {
        val response = runCatching {
            Http.request(
                "PROPFIND", absolute(id),
                headers(mapOf("Depth" to "0", "Content-Type" to "application/xml")),
                body = { out -> out.write(PROPFIND_BODY.toByteArray(Charsets.UTF_8)); out.flush() },
                contentLength = PROPFIND_BODY.toByteArray(Charsets.UTF_8).size.toLong(),
            )
        }.getOrNull() ?: return@withContext null
        if (response.code != 207) return@withContext null
        val resource = WebDavXml.parseMultiStatus(response.text()).firstOrNull() ?: return@withContext null
        val path = relativePath(resource.href) ?: return@withContext null
        RemoteEntry(
            id = RemoteId(WebDavXml.pathOf(resource.href)),
            path = RemotePath(path),
            isDirectory = resource.isCollection,
            sizeBytes = resource.contentLength,
            modifiedAt = resource.lastModified,
            version = RemoteVersion(resource.etag),
        )
    }

    private fun pathOf(path: RemotePath): String =
        if (path.value.isEmpty()) rootPath else "$rootPath/${path.value}"

    private fun absolute(id: RemoteId): String {
        val path = relativePath(id.value) ?: return root
        return urlFor(RemotePath(path))
    }

    private fun childPath(parent: RemotePath, name: String) =
        RemotePath(if (parent.value.isEmpty()) name else "${parent.value}/$name")

    private companion object {
        /** Only the four properties the engine reads; asking for `allprop` is slower and ruder. */
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
                """<d:resourcetype/><d:getetag/><d:getcontentlength/><d:getlastmodified/>""" +
                """</d:prop></d:propfind>"""
    }
}
