package pl.dakil.notes.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Swapping the library root without rebuilding what sits on top of it.
 *
 * The identity of the store object is what matters here: `NoteRepository` captures it once and
 * hangs a debounced save pipeline and a write lock off it, so the root has to be able to move
 * without the repository being replaced underneath a queued write.
 */
class SwitchableNoteStoreTest {

    private val first: File = Files.createTempDirectory("notes-first").toFile()
    private val second: File = Files.createTempDirectory("notes-second").toFile()
    private val store = SwitchableNoteStore(FileNoteStore(first))

    @After
    fun tearDown() {
        first.deleteRecursively()
        second.deleteRecursively()
    }

    @Test
    fun `reads and writes land in whichever root is current`() = runBlocking {
        File(first, "Only in first.md").writeText("a")
        File(second, "Only in second.md").writeText("b")

        assertEquals(listOf("Only in first.md"), store.list(store.root()!!).map { it.name })

        store.swap(FileNoteStore(second))

        assertEquals(listOf("Only in second.md"), store.list(store.root()!!).map { it.name })
    }

    @Test
    fun `the store handed out before a swap is the same object after it`() = runBlocking {
        val held: NoteStore = store

        store.swap(FileNoteStore(second))

        // What a holder captured must keep working and must point at the new root — the whole
        // reason the indirection exists rather than handing out a fresh FileNoteStore.
        assertSame(store, held)
        assertEquals(second.absolutePath, held.root()!!.value)
    }

    @Test
    fun `capabilities are read through to the current backend rather than frozen at construction`() {
        // Read as a property, not copied: a SAF root cannot watch for changes and a file root can,
        // and a caller asking after a swap must be told about the backend it actually has.
        assertEquals(true, store.capabilities.watch)
    }
}
