package pl.dakil.notes.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [NoteStore] over the Storage Access Framework, so a user's notes can live in a folder they
 * chose — including one that Syncthing, Dropbox or a USB drive already syncs.
 *
 * Built directly on [DocumentsContract] rather than `androidx.documentfile`. `DocumentFile` issues
 * a separate content-provider query per attribute per file, which makes listing a folder of a few
 * hundred notes visibly slow; querying the columns we need in one cursor pass does not.
 */
class SafNoteStore(
    private val context: Context,
    private val treeUri: Uri?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NoteStore {

    override val capabilities = StoreCapabilities(
        // DocumentsContract.renameDocument gives replace-by-rename, though whether it is truly
        // atomic is up to the provider.
        atomicReplace = true,
        // SAF has no change notification worth relying on; the library refreshes on resume.
        watch = false,
        revisions = false,
        remote = false,
    )

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun root(): StoreRef? = treeUri?.let {
        StoreRef(DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)).toString())
    }

    private fun uriOf(ref: StoreRef): Uri = Uri.parse(ref.value)

    private fun childrenUri(dir: StoreRef): Uri {
        val uri = uriOf(dir)
        return DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
    }

    override suspend fun list(dir: StoreRef): List<StoreEntry> = withContext(io) {
        val out = ArrayList<StoreEntry>()
        queryChildren(dir)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idCol)
                val mime = cursor.getStringOrNull(mimeCol)
                out += StoreEntry(
                    ref = StoreRef(
                        DocumentsContract.buildDocumentUriUsingTree(uriOf(dir), documentId).toString()
                    ),
                    name = cursor.getStringOrNull(nameCol).orEmpty(),
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L,
                    modifiedAt = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L,
                )
            }
        }
        out
    }

    private fun queryChildren(dir: StoreRef): Cursor? = try {
        resolver.query(
            childrenUri(dir),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )
    } catch (_: Exception) {
        // Providers throw a variety of things when a tree permission has been revoked; treating
        // that as "empty folder" lets the library show an actionable empty state instead of dying.
        null
    }

    override suspend fun exists(ref: StoreRef): Boolean = metadata(ref) != null

    override suspend fun metadata(ref: StoreRef): StoreEntry? = withContext(io) {
        try {
            resolver.query(
                uriOf(ref),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                StoreEntry(
                    ref = ref,
                    name = cursor.getStringOrNull(0).orEmpty(),
                    isDirectory = cursor.getStringOrNull(1) == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = if (!cursor.isNull(2)) cursor.getLong(2) else 0L,
                    modifiedAt = if (!cursor.isNull(3)) cursor.getLong(3) else 0L,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun <T> read(ref: StoreRef, body: (InputStream) -> T): T = withContext(io) {
        try {
            resolver.openInputStream(uriOf(ref))?.buffered()?.use(body)
                ?: throw StoreException("Could not open ${ref.value}")
        } catch (e: IOException) {
            throw StoreException("Could not read ${ref.value}", e)
        }
    }

    /**
     * Writes in place with mode `"rwt"`, which truncates then writes.
     *
     * Unlike the filesystem store there is no safe rename-over-the-top here: creating a temporary
     * sibling and renaming would leave debris in the user's synced folder whenever the process
     * dies. The crash-recovery journal covers the window instead.
     */
    override suspend fun write(ref: StoreRef, body: (OutputStream) -> Unit) = withContext(io) {
        try {
            resolver.openOutputStream(uriOf(ref), "rwt")?.use { out ->
                val buffered = out.buffered()
                body(NonClosingOutputStream(buffered))
                buffered.flush()
            } ?: throw StoreException("Could not open ${ref.value} for writing")
        } catch (e: IOException) {
            throw StoreException("Could not write ${ref.value}", e)
        }
    }

    override suspend fun delete(ref: StoreRef) = withContext(io) {
        try {
            if (!DocumentsContract.deleteDocument(resolver, uriOf(ref))) {
                throw StoreException("Could not delete ${ref.value}")
            }
        } catch (e: Exception) {
            throw StoreException("Could not delete ${ref.value}", e)
        }
    }

    override suspend fun move(from: StoreRef, to: StoreRef): StoreRef = withContext(io) {
        // SAF exposes rename-within-a-parent and move-between-parents as separate operations, and
        // `to` here carries the desired display name rather than a full destination URI.
        try {
            val renamed = DocumentsContract.renameDocument(resolver, uriOf(from), to.value)
                ?: throw StoreException("Could not rename ${from.value}")
            StoreRef(renamed.toString())
        } catch (e: Exception) {
            throw StoreException("Could not move ${from.value}", e)
        }
    }

    /**
     * Moves a document or folder between parents with `DocumentsContract.moveDocument`.
     *
     * This is the operation [move] deliberately is not, and the reason [fromParent] is in the
     * signature at all: the provider needs to be told which parent to detach from, and a document
     * URI has no way to say.
     *
     * A name already taken in the destination is stepped aside from by renaming first — the
     * provider would otherwise either fail or silently produce a duplicate, depending on which
     * provider it is.
     */
    override suspend fun moveTo(
        from: StoreRef,
        fromParent: StoreRef,
        toParent: StoreRef,
    ): StoreRef = withContext(io) {
        if (fromParent == toParent) return@withContext from
        try {
            val name = metadata(from)?.name.orEmpty()
            val taken = list(toParent).mapTo(HashSet()) { it.name }
            val source = if (name.isNotEmpty() && name in taken) {
                DocumentsContract.renameDocument(resolver, uriOf(from), uniqueIn(taken, name))
                    ?: throw StoreException("Could not rename ${from.value} before moving it")
            } else {
                uriOf(from)
            }
            val moved = DocumentsContract.moveDocument(resolver, source, uriOf(fromParent), uriOf(toParent))
                ?: throw StoreException("Could not move ${from.value}")
            StoreRef(moved.toString())
        } catch (e: StoreException) {
            throw e
        } catch (e: Exception) {
            throw StoreException("Could not move ${from.value}", e)
        }
    }

    override suspend fun createDirectory(parent: StoreRef, name: String): StoreRef = withContext(io) {
        create(parent, DocumentsContract.Document.MIME_TYPE_DIR, name.sanitizeFileName())
    }

    override suspend fun child(parent: StoreRef, name: String): StoreRef? =
        list(parent).firstOrNull { it.name == name }?.ref

    override suspend fun newChild(parent: StoreRef, name: String, mimeType: String): StoreRef = withContext(io) {
        val taken = list(parent).mapTo(HashSet()) { it.name }
        create(parent, mimeType, uniqueIn(taken, name.sanitizeFileName()))
    }

    /** Appends ` (2)`, ` (3)`… until the name is free, matching [uniqueName] on the file store. */
    private fun uniqueIn(taken: Set<String>, name: String): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while ("$stem ($n)$extension" in taken) n++
        return "$stem ($n)$extension"
    }

    private fun create(parent: StoreRef, mimeType: String, displayName: String): StoreRef {
        val created = DocumentsContract.createDocument(resolver, uriOf(parent), mimeType, displayName)
            ?: throw StoreException("Could not create '$displayName' in ${parent.value}")
        return StoreRef(created.toString())
    }

    /** SAF has no usable change stream; the library refreshes when it regains the foreground. */
    override fun watch(dir: StoreRef): Flow<StoreChange> = emptyFlow()

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null
}
