package pl.dakil.notes.sync.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonWriter
import pl.dakil.notes.sync.RemoteBackend
import pl.dakil.notes.sync.RemoteCapabilities
import pl.dakil.notes.sync.RemoteEntry
import pl.dakil.notes.sync.RemoteId
import pl.dakil.notes.sync.RemoteListing
import pl.dakil.notes.sync.RemotePath
import pl.dakil.notes.sync.RemoteVersion
import pl.dakil.notes.sync.RemoteWrite
import pl.dakil.notes.sync.TokenProvider
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** One `files` resource, before paths are worked out. */
internal data class DriveFile(
    val id: String,
    val name: String,
    val parents: List<String>,
    val isFolder: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val version: String,
    val createdTime: String,
)

/**
 * Google Drive over the REST v3 API, with no Google Play Services anywhere.
 *
 * The scope is `drive.file`, which sees only files this app created — that is, its own sync folder.
 * The alternative, `drive.appdata`, would dodge verification entirely but puts the files somewhere
 * the user cannot see, which is the opposite of the point: these are meant to be real `.md` and
 * `.daknote` files, in real folders, that anyone can browse and download from drive.google.com.
 */
class DriveBackend(
    private val tokens: TokenProvider,
    /** The folder name at the top of My Drive. */
    private val folderName: String = "DakNote",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteBackend {

    override val backendId = "drive"

    override val capabilities = RemoteCapabilities(
        // Drive may ignore `If-Match`, so a write is verified rather than trusted; see `update`.
        conditionalWrite = false,
        // A fileId survives both rename and move, which makes remote rename detection exact and free.
        idSurvivesMove = true,
        // Two files really can share a name in one folder.
        duplicateNamesPossible = true,
    )

    private companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val FIELDS = "id,name,parents,mimeType,size,modifiedTime,version,createdTime"
    }

    @Volatile
    private var rootId: String? = null

    private suspend fun auth(): Map<String, String> {
        val bearer = tokens.bearer() ?: throw HttpException(401, "Not signed in")
        return mapOf("Authorization" to "Bearer $bearer")
    }

    override suspend fun ensureRoot(): RemoteId = withContext(io) {
        rootId?.let { return@withContext RemoteId(it) }
        val escaped = folderName.replace("'", "\\'")
        val query = "mimeType='$FOLDER_MIME' and name='$escaped' and 'root' in parents and trashed=false"
        val found = Http.get("$FILES?q=${Http.encode(query)}&fields=files(id)", auth())
        if (found.isSuccess) {
            val id = JsonReader.parseObject(found.text()).array("files")
                ?.items?.filterIsInstance<JsonObject>()?.firstOrNull()?.string("id")
            if (!id.isNullOrEmpty()) {
                rootId = id
                return@withContext RemoteId(id)
            }
        }
        val metadata = JsonWriter.write(
            JsonObject.EMPTY.with("name", folderName).with("mimeType", FOLDER_MIME),
            pretty = false,
        ).toByteArray(Charsets.UTF_8)
        val created = Http.request(
            "POST", "$FILES?fields=id",
            auth() + mapOf("Content-Type" to "application/json; charset=UTF-8"),
            body = { out -> out.write(metadata); out.flush() },
            contentLength = metadata.size.toLong(),
        )
        if (!created.isSuccess) throw HttpException(created.code, "Could not create the Drive folder")
        val id = JsonReader.parseObject(created.text()).string("id")
        rootId = id
        RemoteId(id)
    }

    override suspend fun listTree(): RemoteListing = withContext(io) {
        val root = ensureRoot().value
        val files = ArrayList<DriveFile>()
        var pageToken: String? = null
        var complete = true
        var failure: String? = null

        // One flat sweep of everything this app created, then paths are reconstructed from the
        // parent chain. A request per folder would be a round trip per folder; this is a round trip
        // per thousand files.
        do {
            val url = buildString {
                append(FILES).append("?q=").append(Http.encode("trashed=false"))
                append("&pageSize=1000&fields=nextPageToken,files($FIELDS)")
                pageToken?.let { append("&pageToken=").append(Http.encode(it)) }
            }
            val response = runCatching { Http.get(url, auth()) }.getOrElse {
                complete = false
                failure = it.message
                break
            }
            if (!response.isSuccess) {
                // Reported as incomplete rather than short: a listing that stopped early and one
                // that found nothing are the same bytes, and the planner deletes on the second.
                complete = false
                failure = "files.list answered ${response.code}"
                break
            }
            val body = JsonReader.parseObject(response.text())
            body.array("files")?.items?.filterIsInstance<JsonObject>()?.forEach { files += it.toDriveFile() }
            pageToken = body.string("nextPageToken").ifEmpty { null }
        } while (pageToken != null)

        val byId = files.associateBy { it.id }
        val entries = ArrayList<RemoteEntry>()
        val duplicates = ArrayList<RemoteEntry>()
        val taken = HashMap<String, DriveFile>()

        // Deterministic where two siblings share a name: oldest first, the rest reported. Picking
        // "the newest" would let a note flip its content between passes.
        for (file in files.sortedWith(compareBy({ it.createdTime }, { it.id }))) {
            val path = pathOf(file, byId, root) ?: continue
            val entry = RemoteEntry(
                id = RemoteId(file.id),
                path = RemotePath(path),
                isDirectory = file.isFolder,
                sizeBytes = file.size,
                modifiedAt = file.modifiedAt,
                version = RemoteVersion(file.version),
            )
            val previous = taken.put(path, file)
            if (previous == null) entries += entry else duplicates += entry
        }
        RemoteListing(entries, duplicates, complete, failure)
    }

    /** Walks parents up to the sync root. Null means the file is not under it. */
    private fun pathOf(file: DriveFile, byId: Map<String, DriveFile>, root: String): String? {
        val segments = ArrayList<String>()
        var current: DriveFile? = file
        var guard = 0
        while (current != null && guard++ < 64) {
            if (current.id == root) return segments.asReversed().joinToString("/")
            val parent = current.parents.firstOrNull() ?: return null
            segments += current.name
            if (parent == root) return segments.asReversed().joinToString("/")
            current = byId[parent] ?: return null
        }
        return null
    }

    override suspend fun <T> read(id: RemoteId, body: (InputStream) -> T): T = withContext(io) {
        var result: T? = null
        val response = Http.request(
            "GET", "$FILES/${id.value}?alt=media", auth(),
            onBody = { input -> result = body(input) },
        )
        if (!response.isSuccess) throw HttpException(response.code, "Download answered ${response.code}")
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    override suspend fun create(
        parent: RemotePath,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite = withContext(io) {
        val parentId = folderIdFor(parent) ?: return@withContext RemoteWrite.Failed("No such folder", false)
        val metadata = JsonObject.EMPTY
            .with("name", name)
            .with("parents", pl.dakil.notes.model.json.JsonArray.ofStrings(listOf(parentId)))
        multipart("POST", "$UPLOAD?uploadType=multipart&fields=$FIELDS", metadata, mimeType, body)
    }

    override suspend fun update(
        id: RemoteId,
        expect: RemoteVersion?,
        sizeBytes: Long,
        body: (OutputStream) -> Unit,
    ): RemoteWrite = withContext(io) {
        // Compare-and-verify rather than compare-and-swap. Drive does not guarantee `If-Match` on
        // an upload, so the version is checked immediately before writing; a race window remains,
        // and what closes it is that the engine does not advance the state record until the write
        // is confirmed, so a lost update becomes a conflict copy next pass rather than lost work.
        if (expect != null && expect.value.isNotEmpty()) {
            val current = stat(id)
            if (current != null && current.version.value != expect.value) {
                return@withContext RemoteWrite.VersionConflict(current)
            }
        }
        multipart("PATCH", "$UPLOAD/${id.value}?uploadType=media&fields=$FIELDS", null, null, body)
    }

    private suspend fun multipart(
        method: String,
        url: String,
        metadata: JsonObject?,
        mimeType: String?,
        body: (OutputStream) -> Unit,
    ): RemoteWrite {
        val payload = ByteArrayOutputStream().also { body(it) }.toByteArray()
        val headers = HashMap(auth())
        val requestBody: ByteArray
        if (metadata != null) {
            val boundary = "daknote-" + System.nanoTime().toString(16)
            headers["Content-Type"] = "multipart/related; boundary=$boundary"
            val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                JsonWriter.write(metadata, pretty = false) +
                "\r\n--$boundary\r\nContent-Type: ${mimeType ?: "application/octet-stream"}\r\n\r\n"
            val tail = "\r\n--$boundary--"
            requestBody = head.toByteArray(Charsets.UTF_8) + payload + tail.toByteArray(Charsets.UTF_8)
        } else {
            headers["Content-Type"] = mimeType ?: "application/octet-stream"
            requestBody = payload
        }
        val response = runCatching {
            Http.request(
                method, url, headers,
                body = { out -> out.write(requestBody); out.flush() },
                contentLength = requestBody.size.toLong(),
            )
        }.getOrElse { return RemoteWrite.Failed(it.message ?: "Upload failed", retryable = true) }

        return if (response.isSuccess) {
            RemoteWrite.Ok(JsonReader.parseObject(response.text()).toDriveFile().toEntry())
        } else {
            RemoteWrite.Failed("Upload answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun delete(id: RemoteId, expect: RemoteVersion?): RemoteWrite = withContext(io) {
        // Trashed, never hard-deleted. A sync bug that fills the wastebasket is recoverable; one
        // that erases a library is not.
        val metadata = JsonWriter.write(JsonObject.EMPTY.with("trashed", true), pretty = false)
            .toByteArray(Charsets.UTF_8)
        val response = Http.request(
            "PATCH", "$FILES/${id.value}",
            auth() + mapOf("Content-Type" to "application/json; charset=UTF-8"),
            body = { out -> out.write(metadata); out.flush() },
            contentLength = metadata.size.toLong(),
        )
        if (response.isSuccess || response.code == 404) {
            RemoteWrite.Ok(RemoteEntry(id, RemotePath(""), false, 0, 0, RemoteVersion("")))
        } else {
            RemoteWrite.Failed("Trash answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun createDirectory(parent: RemotePath, name: String): RemoteWrite = withContext(io) {
        val parentId = folderIdFor(parent) ?: return@withContext RemoteWrite.Failed("No such folder", false)
        val metadata = JsonWriter.write(
            JsonObject.EMPTY
                .with("name", name)
                .with("mimeType", FOLDER_MIME)
                .with("parents", pl.dakil.notes.model.json.JsonArray.ofStrings(listOf(parentId))),
            pretty = false,
        ).toByteArray(Charsets.UTF_8)
        val response = Http.request(
            "POST", "$FILES?fields=$FIELDS",
            auth() + mapOf("Content-Type" to "application/json; charset=UTF-8"),
            body = { out -> out.write(metadata); out.flush() },
            contentLength = metadata.size.toLong(),
        )
        if (response.isSuccess) {
            RemoteWrite.Ok(JsonReader.parseObject(response.text()).toDriveFile().toEntry())
        } else {
            RemoteWrite.Failed("Folder create answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun move(id: RemoteId, from: RemotePath, to: RemotePath): RemoteWrite = withContext(io) {
        val newParent = folderIdFor(RemotePath(to.parent)) ?: return@withContext RemoteWrite.Failed("No such folder", false)
        val oldParent = folderIdFor(RemotePath(from.parent))
        val metadata = JsonWriter.write(JsonObject.EMPTY.with("name", to.name), pretty = false)
            .toByteArray(Charsets.UTF_8)
        val url = buildString {
            append(FILES).append('/').append(id.value).append("?fields=$FIELDS")
            append("&addParents=").append(Http.encode(newParent))
            oldParent?.let { append("&removeParents=").append(Http.encode(it)) }
        }
        val response = Http.request(
            "PATCH", url,
            auth() + mapOf("Content-Type" to "application/json; charset=UTF-8"),
            body = { out -> out.write(metadata); out.flush() },
            contentLength = metadata.size.toLong(),
        )
        if (response.isSuccess) {
            RemoteWrite.Ok(JsonReader.parseObject(response.text()).toDriveFile().toEntry())
        } else {
            RemoteWrite.Failed("Move answered ${response.code}", retryable = response.code >= 500)
        }
    }

    override suspend fun stat(id: RemoteId): RemoteEntry? = withContext(io) {
        val response = runCatching { Http.get("$FILES/${id.value}?fields=$FIELDS", auth()) }.getOrNull()
        if (response == null || !response.isSuccess) return@withContext null
        JsonReader.parseObject(response.text()).toDriveFile().toEntry()
    }

    /** Resolves a folder path to its id, creating nothing. */
    private suspend fun folderIdFor(path: RemotePath): String? {
        val root = ensureRoot().value
        if (path.value.isEmpty()) return root
        val listing = listTree()
        return listing.entries.firstOrNull { it.isDirectory && it.path.value == path.value }?.id?.value
    }

    private fun JsonObject.toDriveFile() = DriveFile(
        id = string("id"),
        name = string("name"),
        parents = stringList("parents"),
        isFolder = string("mimeType") == FOLDER_MIME,
        size = string("size").toLongOrNull() ?: 0L,
        modifiedAt = parseRfc3339(string("modifiedTime")),
        version = string("version"),
        createdTime = string("createdTime"),
    )

    private fun DriveFile.toEntry() = RemoteEntry(
        id = RemoteId(id),
        path = RemotePath(name),
        isDirectory = isFolder,
        sizeBytes = size,
        modifiedAt = modifiedAt,
        version = RemoteVersion(version),
    )
}

/**
 * `2026-08-28T14:12:33.123Z`, by hand.
 *
 * Same reason the WebDAV date is parsed this way: a `SimpleDateFormat` without an explicit
 * `Locale.ROOT` reads the year wrong on a Buddhist-calendar device, and every remote file then
 * looks newer than every local one.
 */
internal fun parseRfc3339(raw: String): Long {
    if (raw.length < 19) return 0L
    return runCatching {
        val year = raw.substring(0, 4).toInt()
        val month = raw.substring(5, 7).toInt() - 1
        val day = raw.substring(8, 10).toInt()
        val hour = raw.substring(11, 13).toInt()
        val minute = raw.substring(14, 16).toInt()
        val second = raw.substring(17, 19).toInt()
        val millis = if (raw.length > 20 && raw[19] == '.') {
            raw.substring(20).takeWhile { it.isDigit() }.take(3).padEnd(3, '0').toInt()
        } else {
            0
        }
        val calendar = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.ROOT,
        )
        calendar.clear()
        calendar.set(year, month, day, hour, minute, second)
        calendar.timeInMillis + millis
    }.getOrDefault(0L)
}
