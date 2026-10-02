package pl.dakil.notes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.model.Note

/** A row of the library list. Cheap to build — never requires opening a `.daknote`. */
data class NoteSummary(
    val ref: StoreRef,
    val noteId: String,
    val kind: NoteKind,
    val title: String,
    val tags: List<String>,
    val modifiedAt: Long,
    val createdAt: Long,
    val snippet: String,
    /** The folder this note sits in, as a logical path from the root: "", "Work", "Work/Q3". */
    val path: String = "",
)

enum class NoteSort {
    MODIFIED_DESC,
    MODIFIED_ASC,
    TITLE_ASC,
    TITLE_DESC,
    CREATED_DESC,
}

/**
 * A full-text search index over the note library.
 *
 * Deliberately built on the framework's own `SQLiteOpenHelper` rather than Room: Room would add
 * roughly a megabyte and a KSP processor for a schema of three tables that never leaves this file.
 *
 * The important property is that this index is a **derived cache, not a source of truth** — the
 * `.daknote` files are. That is what lets [onUpgrade] simply drop everything and reindex, and it is
 * the concrete reason the extensible block format needs no database migrations: a future release
 * that adds a block type changes what gets indexed, not what has to be migrated.
 */
class NoteIndex(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val helper = object : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // The index is disposable: rebuilding is always correct and always cheaper to maintain
            // than a migration path.
            dropSchema(db)
            createSchema(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            dropSchema(db)
            createSchema(db)
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notes (
              ref TEXT PRIMARY KEY,
              note_id TEXT NOT NULL,
              kind TEXT NOT NULL,
              path TEXT NOT NULL,
              title TEXT NOT NULL,
              tags TEXT NOT NULL,
              created INTEGER NOT NULL,
              modified INTEGER NOT NULL,
              file_modified INTEGER NOT NULL,
              file_size INTEGER NOT NULL,
              snippet TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_notes_path ON notes(path)")
        db.execSQL("CREATE INDEX idx_notes_modified ON notes(modified)")
        // FTS4 rather than FTS5: FTS4 is present on every Android version this app supports,
        // whereas FTS5 availability varies by device.
        db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(ref, title, body, tokenize=unicode61)")
    }

    private fun dropSchema(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS notes_fts")
        db.execSQL("DROP TABLE IF EXISTS notes")
    }

    // ---- Writes --------------------------------------------------------------------------------

    suspend fun put(
        ref: StoreRef,
        note: Note,
        path: String,
        fileModified: Long = note.meta.modified,
        fileSize: Long = 0L,
    ) = put(
        ref = ref,
        path = path,
        kind = NoteKind.INK,
        noteId = note.meta.id.raw,
        title = note.meta.title,
        tags = note.meta.tags,
        created = note.meta.created,
        modified = note.meta.modified,
        body = note.plainText(),
        fileModified = fileModified,
        fileSize = fileSize,
    )

    /**
     * The primitive every other write goes through.
     *
     * Spelled out in fields rather than taking a [Note], because a `.md` file has no manifest and so
     * no [pl.dakil.notes.model.NoteMeta] to hand over — its title is its file name and its id is its
     * path.
     *
     * [path] is the note's folder as a logical path from the library root, and has to be passed in
     * rather than parsed out of [ref]: a filesystem ref is a path and a SAF ref is a document URI
     * whose last segment is an opaque id, so there is no string surgery that is right for both.
     */
    suspend fun put(
        ref: StoreRef,
        path: String,
        kind: NoteKind,
        noteId: String,
        title: String,
        tags: List<String>,
        created: Long,
        modified: Long,
        body: String,
        fileModified: Long,
        fileSize: Long,
    ) = withContext(io) {
        val searchable = indexedBody(body)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "notes", null,
                ContentValues().apply {
                    put("ref", ref.value)
                    put("note_id", noteId)
                    put("kind", kind.name)
                    put("path", path)
                    put("title", title)
                    put("tags", tags.joinToString(TAG_SEPARATOR))
                    put("created", created)
                    put("modified", modified)
                    put("file_modified", fileModified)
                    put("file_size", fileSize)
                    put("snippet", searchable.take(SNIPPET_LENGTH).replace('\n', ' ').trim())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("notes_fts", "ref = ?", arrayOf(ref.value))
            db.insert(
                "notes_fts", null,
                ContentValues().apply {
                    put("ref", ref.value)
                    put("title", title)
                    put("body", searchable + "\n" + tags.joinToString(" "))
                },
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun remove(ref: StoreRef) = withContext(io) {
        val db = helper.writableDatabase
        db.delete("notes", "ref = ?", arrayOf(ref.value))
        db.delete("notes_fts", "ref = ?", arrayOf(ref.value))
        Unit
    }

    /**
     * Follows a renamed note to its new ref, optionally under a new [title].
     *
     * The title has to be passed in rather than re-derived here, because where a note's title comes
     * from depends on its kind — a `.md` note is titled by its file name, an ink note by its
     * manifest. Skipping it would leave the old title in the index indefinitely: a rename does not
     * change the file's size or timestamp, so the next scan sees the row as current and never
     * re-reads it.
     */
    suspend fun move(
        from: StoreRef,
        to: StoreRef,
        path: String,
        title: String? = null,
    ) = withContext(io) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "notes",
                ContentValues().apply {
                    put("ref", to.value)
                    put("path", path)
                    if (title != null) put("title", title)
                },
                "ref = ?",
                arrayOf(from.value),
            )
            db.update(
                "notes_fts",
                ContentValues().apply {
                    put("ref", to.value)
                    if (title != null) put("title", title)
                },
                "ref = ?",
                arrayOf(from.value),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Unit
    }

    /** Drops index rows for notes that the latest scan of the folder at [path] did not report. */
    suspend fun retainOnly(path: String, refs: Set<String>) = withContext(io) {
        val db = helper.writableDatabase
        db.rawQuery("SELECT ref FROM notes WHERE path = ?", arrayOf(path)).use { cursor ->
            val stale = ArrayList<String>()
            while (cursor.moveToNext()) {
                val ref = cursor.getString(0)
                if (ref !in refs) stale += ref
            }
            for (ref in stale) {
                db.delete("notes", "ref = ?", arrayOf(ref))
                db.delete("notes_fts", "ref = ?", arrayOf(ref))
            }
        }
    }

    /**
     * Drops every row at [path] and below it.
     *
     * What a deleted, renamed or moved folder needs. Rewriting each descendant's path instead would
     * be more code for no gain: the rows are a cache of files that are all still there to be read,
     * and the scan that follows puts them back where they now live.
     */
    suspend fun removeSubtree(path: String) = withContext(io) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val where = subtreePredicate("path")
            db.execSQL(
                "DELETE FROM notes_fts WHERE ref IN (SELECT ref FROM notes WHERE $where)",
                subtreeArgs(path),
            )
            db.execSQL("DELETE FROM notes WHERE $where", subtreeArgs(path))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Unit
    }

    /**
     * Drops rows for every folder under [root] that [visited] does not name.
     *
     * The per-folder [retainOnly] cannot see a folder that has stopped existing — nothing scans it,
     * so nothing reports it empty — and its notes would go on turning up in search results that
     * open nothing. Only a recursive scan may call this: a shallow one visits a single folder and
     * would take the rest of the library with it.
     */
    suspend fun retainPaths(root: String, visited: Set<String>) = withContext(io) {
        val db = helper.writableDatabase
        val stale = ArrayList<String>()
        db.rawQuery(
            "SELECT DISTINCT path FROM notes WHERE " + subtreePredicate("path"),
            subtreeArgs(root),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                if (path !in visited) stale += path
            }
        }
        for (path in stale) removeSubtree(path)
        Unit
    }

    /** Where the index believes [ref] lives, or the root if it has never seen it. */
    suspend fun pathOf(ref: StoreRef): String = withContext(io) {
        helper.readableDatabase.rawQuery(
            "SELECT path FROM notes WHERE ref = ?",
            arrayOf(ref.value),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
    }

    /** True when the indexed copy already matches the file on disk, so it can be skipped. */
    suspend fun isCurrent(ref: StoreRef, fileModified: Long, fileSize: Long): Boolean = withContext(io) {
        helper.readableDatabase.rawQuery(
            "SELECT file_modified, file_size FROM notes WHERE ref = ?",
            arrayOf(ref.value),
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getLong(0) == fileModified && cursor.getLong(1) == fileSize
        }
    }

    // ---- Reads ---------------------------------------------------------------------------------

    suspend fun list(
        path: String,
        sort: NoteSort = NoteSort.MODIFIED_DESC,
        tags: Set<String> = emptySet(),
    ): List<NoteSummary> = withContext(io) {
        val order = when (sort) {
            NoteSort.MODIFIED_DESC -> "modified DESC"
            NoteSort.MODIFIED_ASC -> "modified ASC"
            NoteSort.TITLE_ASC -> "title COLLATE NOCASE ASC"
            NoteSort.TITLE_DESC -> "title COLLATE NOCASE DESC"
            NoteSort.CREATED_DESC -> "created DESC"
        }
        helper.readableDatabase.rawQuery(
            "SELECT ref, note_id, kind, title, tags, created, modified, snippet, path FROM notes " +
                "WHERE path = ? ORDER BY $order",
            arrayOf(path),
        ).use { it.toSummaries() }.filter { summary ->
            tags.isEmpty() || summary.tags.any { it in tags }
        }
    }

    /**
     * Full-text search under [path] and everything below it.
     *
     * [query] is treated as a prefix match on the final term, which is what makes results appear
     * while the user is still typing. The subtree rather than the one folder, because a folder the
     * user is standing in is a place they think of as containing its children — a search that
     * stopped at the first level would silently miss the note they filed one folder deeper. Root
     * passes `""`, which matches the whole library.
     */
    suspend fun search(
        query: String,
        path: String = "",
        limit: Int = 100,
    ): List<NoteSummary> = withContext(io) {
        val match = ftsMatchExpression(query) ?: return@withContext emptyList()
        // Defensive: the terms are stripped to word characters before they get here, so a syntax
        // error should be impossible — but a thrown query would take the library screen down with
        // it, and no results is a better answer than no screen.
        runCatching {
            helper.readableDatabase.rawQuery(
                """
                SELECT n.ref, n.note_id, n.kind, n.title, n.tags, n.created, n.modified, n.snippet, n.path
                FROM notes_fts f JOIN notes n ON n.ref = f.ref
                WHERE notes_fts MATCH ? AND ${subtreePredicate("n.path")}
                ORDER BY n.modified DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(match) + subtreeArgs(path) + limit.toString(),
            ).use { it.toSummaries() }
        }.getOrDefault(emptyList())
    }

    suspend fun allTags(): List<String> = withContext(io) {
        val counts = LinkedHashMap<String, Int>()
        helper.readableDatabase.rawQuery("SELECT tags FROM notes WHERE tags != ''", null).use { cursor ->
            while (cursor.moveToNext()) {
                for (tag in cursor.getString(0).split(TAG_SEPARATOR)) {
                    if (tag.isNotBlank()) counts[tag] = (counts[tag] ?: 0) + 1
                }
            }
        }
        counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    suspend fun clear() = withContext(io) {
        val db = helper.writableDatabase
        dropSchema(db)
        createSchema(db)
    }

    private fun android.database.Cursor.toSummaries(): List<NoteSummary> {
        val out = ArrayList<NoteSummary>(count)
        while (moveToNext()) {
            out += NoteSummary(
                ref = StoreRef(getString(0)),
                noteId = getString(1),
                // A row written by a build that knew a kind this one does not is listed as an ink
                // note rather than dropped: the file is still there and still openable.
                kind = NoteKind.entries.firstOrNull { it.name == getString(2) } ?: NoteKind.INK,
                title = getString(3),
                tags = getString(4).split(TAG_SEPARATOR).filter { it.isNotBlank() },
                createdAt = getLong(5),
                modifiedAt = getLong(6),
                snippet = getString(7),
                path = getString(8),
            )
        }
        return out
    }

    private companion object {
        const val DATABASE_NAME = "note-index.db"

        /** Bumping this rebuilds the index from the files, which are the source of truth. */
        const val VERSION = 4

        /**
         * Matches a folder path and everything beneath it.
         *
         * An empty path is the root and matches the lot, which is why the first branch is a plain
         * equality test rather than a prefix: `LIKE '/%'` would miss the root's own notes.
         */
        fun subtreePredicate(column: String) =
            "(? = '' OR $column = ? OR $column LIKE ? ESCAPE '\\')"

        /**
         * The three bindings [SUBTREE_PREDICATE] wants.
         *
         * `%` and `_` are ordinary characters in a folder name and wildcards in `LIKE`, so a folder
         * called "50%" would otherwise match half the library.
         */
        fun subtreeArgs(path: String): Array<String> {
            val escaped = path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return arrayOf(path, path, "$escaped/%")
        }

        /** ASCII unit separator: it cannot occur in a user-typed tag, so no escaping is needed. */
        const val TAG_SEPARATOR = "\u001F"
        const val SNIPPET_LENGTH = 240

        /** Matches an embedded image payload: the data URI itself, base64 and all. */
        private val PAYLOAD = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+""")

        /**
         * Takes embedded image payloads out of a body before it reaches the index.
         *
         * An image is stored as a few megabytes of base64, and every one of those bytes used to be
         * written into the FTS table: the tokenizer read the whole payload, the table grew by it,
         * and a scan that touched several such notes stalled long enough for the library to hang.
         * Nothing the user can search for lives in the payload — it is an encoding of pixels — so
         * the snippet and the searchable body both get what is left when it is gone.
         */
        private fun indexedBody(body: String) = body.replace(PAYLOAD, "")
    }
}
