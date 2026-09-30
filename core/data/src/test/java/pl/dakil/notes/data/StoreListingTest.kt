package pl.dakil.notes.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The difference between "this folder is empty" and "I could not read this folder".
 *
 * These two answers call for opposite responses — the first means the index should drop the rows
 * for notes that are gone, the second means it must not touch a thing — and for as long as `list`
 * was the only way to ask, they arrived as the same empty list. A lapsed SAF grant then read as a
 * library the user had emptied.
 */
class StoreListingTest {

    private val root: File = Files.createTempDirectory("notes-listing").toFile()
    private val store = FileNoteStore(root)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `an empty folder lists as empty rather than as unavailable`() = runBlocking {
        val empty = File(root, "Empty").apply { mkdirs() }

        val listing = store.listChecked(StoreRef(empty.absolutePath))

        // The case that must stay purgeable: nothing is there, and the index should say so too.
        assertEquals(StoreListing.Ok(emptyList()), listing)
    }

    @Test
    fun `a folder that is not there is unavailable, not empty`() = runBlocking {
        val missing = File(root, "Gone")

        val listing = store.listChecked(StoreRef(missing.absolutePath))

        assertTrue("expected Unavailable, got $listing", listing is StoreListing.Unavailable)
    }

    @Test
    fun `a file handed in where a folder was expected is unavailable, not empty`() = runBlocking {
        val notAFolder = File(root, "Note.md").apply { writeText("# hello") }

        val listing = store.listChecked(StoreRef(notAFolder.absolutePath))

        // `listFiles` answers null here exactly as it does for an unreadable directory, which is
        // why the store has to look at *why* before reporting anything.
        assertTrue("expected Unavailable, got $listing", listing is StoreListing.Unavailable)
    }

    @Test
    fun `list still flattens an unavailable folder to nothing, for screens that only show it empty`() =
        runBlocking {
            val missing = File(root, "Gone")

            // The lenient answer is kept deliberately: the library screen wants an empty state
            // here, not an exception. Only callers that *delete* on absence must ask the other way.
            assertEquals(emptyList<StoreEntry>(), store.list(StoreRef(missing.absolutePath)))
        }

    @Test
    fun `a folder lists the notes and the folders it holds`() = runBlocking {
        File(root, "Work").mkdirs()
        File(root, "Groceries.md").writeText("milk")

        val listing = store.listChecked(StoreRef(root.absolutePath))

        val entries = (listing as StoreListing.Ok).entries.sortedBy { it.name }
        assertEquals(listOf("Groceries.md", "Work"), entries.map { it.name })
        assertEquals(listOf(false, true), entries.map { it.isDirectory })
    }
}
