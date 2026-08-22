package pl.dakil.notes.library

import androidx.compose.runtime.Immutable
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.NoteSummary
import pl.dakil.notes.data.StoreEntry
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind

/** What the root of the library is called on screen. It is a label, not a folder on disk. */
const val ROOT_FOLDER_NAME = "Notes"

/**
 * One row of the browser, folder or note.
 *
 * Folders and notes were two lists until selection arrived, and selection is what merged them: a
 * user picks four things and moves them without caring which of the two each one is, so the code
 * that filters, sorts, selects and moves should not have to care either.
 *
 * [path] is the *containing* folder's logical path, which is what every mutation on the item needs
 * — the index is keyed by it, and a note found by a search is not necessarily in the folder on
 * screen.
 */
@Immutable
sealed interface LibraryItem {
    val ref: StoreRef
    val name: String
    val modifiedAt: Long
    val path: String

    @Immutable
    data class Folder(
        override val ref: StoreRef,
        override val name: String,
        override val modifiedAt: Long,
        override val path: String,
    ) : LibraryItem

    @Immutable
    data class Note(val summary: NoteSummary) : LibraryItem {
        override val ref: StoreRef get() = summary.ref
        override val name: String get() = summary.title
        override val modifiedAt: Long get() = summary.modifiedAt
        override val path: String get() = summary.path
    }
}

/** Which kinds of thing the browser is showing. */
enum class LibraryFilter(val label: String) {
    ALL("All"),
    FOLDERS("Folders only"),
    TEXT("Text notes only"),
    INK("Ink notes only"),
}

/**
 * Merges the folders read from the store with the notes read from the index into the one list the
 * browser draws.
 *
 * Pure so it can be tested without a database or a device — the reason it is not simply inlined
 * into the view model.
 *
 * Folders come first whatever the sort. Sorting them in among the notes would scatter them through
 * a long list, and a folder is a place rather than a thing: somewhere to go, not something to read.
 */
object LibraryListing {

    fun build(
        folders: List<StoreEntry>,
        notes: List<NoteSummary>,
        path: String,
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: NoteSort = NoteSort.MODIFIED_DESC,
    ): List<LibraryItem> {
        val items = ArrayList<LibraryItem>(folders.size + notes.size)
        if (filter == LibraryFilter.ALL || filter == LibraryFilter.FOLDERS) {
            folders
                .map { LibraryItem.Folder(it.ref, it.name, it.modifiedAt, path) }
                .sortedWith(folderOrder(sort))
                .forEach { items += it }
        }
        if (filter != LibraryFilter.FOLDERS) {
            // The index has already applied the sort; re-sorting here would only risk disagreeing
            // with it about ties.
            notes.asSequence()
                .filter { filter.accepts(it.kind) }
                .forEach { items += LibraryItem.Note(it) }
        }
        return items
    }

    private fun LibraryFilter.accepts(kind: NoteKind): Boolean = when (this) {
        LibraryFilter.ALL -> true
        LibraryFilter.FOLDERS -> false
        LibraryFilter.TEXT -> kind == NoteKind.TEXT
        LibraryFilter.INK -> kind == NoteKind.INK
    }

    /**
     * The same order the index applies to notes, as far as a folder can honour it.
     *
     * A folder has no creation date the store will tell us about, so "recently created" falls back
     * to "recently changed" rather than leaving the folders in whatever order the filesystem
     * happened to hand them over in.
     */
    private fun folderOrder(sort: NoteSort): Comparator<LibraryItem.Folder> = when (sort) {
        NoteSort.MODIFIED_DESC, NoteSort.CREATED_DESC -> compareByDescending { it.modifiedAt }
        NoteSort.MODIFIED_ASC -> compareBy { it.modifiedAt }
        NoteSort.TITLE_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        NoteSort.TITLE_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}
