package pl.dakil.notes.library

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.dakil.notes.data.NoteSort
import pl.dakil.notes.data.NoteSummary
import pl.dakil.notes.data.StoreEntry
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind

class LibraryListingTest {

    private fun folder(name: String, modified: Long = 0L) = StoreEntry(
        ref = StoreRef("/root/$name"),
        name = name,
        isDirectory = true,
        modifiedAt = modified,
    )

    private fun note(title: String, kind: NoteKind, modified: Long = 0L) = NoteSummary(
        ref = StoreRef("/root/$title.${kind.extension}"),
        noteId = title,
        kind = kind,
        title = title,
        tags = emptyList(),
        modifiedAt = modified,
        createdAt = modified,
        snippet = "",
    )

    @Test
    fun `folders come before notes whatever the sort`() {
        // A folder is somewhere to go rather than something to read, and sorting the two together
        // would scatter the folders through a long list of notes.
        val items = LibraryListing.build(
            folders = listOf(folder("Work", modified = 1)),
            notes = listOf(note("Zebra", NoteKind.INK, modified = 9)),
            path = "",
            sort = NoteSort.MODIFIED_DESC,
        )

        assertEquals(listOf("Work", "Zebra"), items.map { it.name })
    }

    @Test
    fun `folders follow the chosen sort among themselves`() {
        val items = LibraryListing.build(
            folders = listOf(folder("beta"), folder("Alpha"), folder("Gamma")),
            notes = emptyList(),
            path = "",
            sort = NoteSort.TITLE_ASC,
        )

        // Case-insensitive, or "beta" would sort after "Gamma" and the row order would look random.
        assertEquals(listOf("Alpha", "beta", "Gamma"), items.map { it.name })
    }

    @Test
    fun `the notes keep the order the index gave them`() {
        // The index has already applied the sort in SQL; re-sorting here could only disagree with
        // it about ties, which would make the list jump between a scan and a reload.
        val given = listOf(
            note("Third", NoteKind.INK, modified = 3),
            note("First", NoteKind.TEXT, modified = 1),
            note("Second", NoteKind.INK, modified = 2),
        )

        val items = LibraryListing.build(emptyList(), given, path = "", sort = NoteSort.TITLE_ASC)

        assertEquals(listOf("Third", "First", "Second"), items.map { it.name })
    }

    @Test
    fun `the folders filter hides every note`() {
        val items = LibraryListing.build(
            folders = listOf(folder("Work")),
            notes = listOf(note("Ideas", NoteKind.TEXT), note("Sketch", NoteKind.INK)),
            path = "",
            filter = LibraryFilter.FOLDERS,
        )

        assertEquals(listOf("Work"), items.map { it.name })
    }

    @Test
    fun `a kind filter hides the folders as well as the other kind`() {
        // "Ink notes only" means only ink notes. Leaving the folders in would make the filter read
        // as "and also everything you can open".
        val items = LibraryListing.build(
            folders = listOf(folder("Work")),
            notes = listOf(note("Ideas", NoteKind.TEXT), note("Sketch", NoteKind.INK)),
            path = "",
            filter = LibraryFilter.INK,
        )

        assertEquals(listOf("Sketch"), items.map { it.name })
    }

    @Test
    fun `an item carries the folder it is in, not the folder it is`() {
        // Every mutation is keyed by the containing path, so a folder row must report its parent —
        // reporting itself would delete the index rows of the folder above it.
        val items = LibraryListing.build(
            folders = listOf(folder("Q3")),
            notes = emptyList(),
            path = "Work",
        )

        assertEquals("Work", items.single().path)
    }
}
