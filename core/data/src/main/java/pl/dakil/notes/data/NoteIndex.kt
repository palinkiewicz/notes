package pl.dakil.notes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.model.Note

/** A row of the library list. Cheap to build — never requires opening a `.daknote`. */
data class NoteSummary(
    val ref: StoreRef,
    val noteId: String,
    val title: String,
    val tags: List<String>,
    val modifiedAt: Long,
    val createdAt: Long,
    val snippet: String,
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
              parent TEXT NOT NULL,
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
        db.execSQL("CREATE INDEX idx_notes_parent ON notes(parent)")
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
        fileModified: Long = note.meta.modified,
        fileSize: Long = 0L,
    ) = withContext(io) {
        val body = note.plainText()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "notes", null,
                ContentValues().apply {
                    put("ref", ref.value)
                    put("note_id", note.meta.id.raw)
                    put("parent", parentOf(ref.value))
                    put("title", note.meta.title)
                    put("tags", note.meta.tags.joinToString(TAG_SEPARATOR))
                    put("created", note.meta.created)
                    put("modified", note.meta.modified)
                    put("file_modified", fileModified)
                    put("file_size", fileSize)
                    put("snippet", body.take(SNIPPET_LENGTH).replace('\n', ' ').trim())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("notes_fts", "ref = ?", arrayOf(ref.value))
            db.insert(
                "notes_fts", null,
                ContentValues().apply {
                    put("ref", ref.value)
                    put("title", note.meta.title)
                    put("body", body + "\n" + note.meta.tags.joinToString(" "))
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

    suspend fun move(from: StoreRef, to: StoreRef) = withContext(io) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("ref", to.value)
            put("parent", parentOf(to.value))
        }
        db.update("notes", values, "ref = ?", arrayOf(from.value))
        db.update("notes_fts", ContentValues().apply { put("ref", to.value) }, "ref = ?", arrayOf(from.value))
        Unit
    }

    /** Drops index rows for notes that no longer exist under [dir]. */
    suspend fun retainOnly(dir: StoreRef, refs: Set<String>) = withContext(io) {
        val db = helper.writableDatabase
        val parent = dir.value
        db.rawQuery("SELECT ref FROM notes WHERE parent = ?", arrayOf(parent)).use { cursor ->
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
        parent: StoreRef,
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
            "SELECT ref, note_id, title, tags, created, modified, snippet FROM notes " +
                "WHERE parent = ? ORDER BY $order",
            arrayOf(parent.value),
        ).use { it.toSummaries() }.filter { summary ->
            tags.isEmpty() || summary.tags.any { it in tags }
        }
    }

    /**
     * Full-text search. [query] is treated as a prefix match on the final term, which is what makes
     * results appear while the user is still typing.
     */
    suspend fun search(query: String, limit: Int = 100): List<NoteSummary> = withContext(io) {
        val match = buildMatchExpression(query) ?: return@withContext emptyList()
        helper.readableDatabase.rawQuery(
            """
            SELECT n.ref, n.note_id, n.title, n.tags, n.created, n.modified, n.snippet
            FROM notes_fts f JOIN notes n ON n.ref = f.ref
            WHERE notes_fts MATCH ?
            ORDER BY n.modified DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(match, limit.toString()),
        ).use { it.toSummaries() }
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
                title = getString(2),
                tags = getString(3).split(TAG_SEPARATOR).filter { it.isNotBlank() },
                createdAt = getLong(4),
                modifiedAt = getLong(5),
                snippet = getString(6),
            )
        }
        return out
    }

    /**
     * Builds an FTS MATCH expression, quoting each term.
     *
     * Quoting matters: FTS treats `-`, `*`, `"`, `(`, `)` and `OR` as operators, so a user typing
     * a hyphenated word or an unbalanced quote would otherwise get a syntax error rather than
     * results.
     */
    private fun buildMatchExpression(query: String): String? {
        val terms = query.split(WHITESPACE)
            .map { it.filter { c -> c.isLetterOrDigit() || c == '_' } }
            .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.mapIndexed { i, term ->
            // Prefix-match the final term so results narrow as the user types.
            if (i == terms.lastIndex) "\"$term\"*" else "\"$term\""
        }.joinToString(" ")
    }

    private fun parentOf(ref: String): String = ref.substringBeforeLast('/', "")

    private companion object {
        const val DATABASE_NAME = "note-index.db"

        /** Bumping this rebuilds the index from the files, which are the source of truth. */
        const val VERSION = 1

        /** ASCII unit separator: it cannot occur in a user-typed tag, so no escaping is needed. */
        const val TAG_SEPARATOR = "\u001F"
        const val SNIPPET_LENGTH = 240
        val WHITESPACE = Regex("\\s+")
    }
}
