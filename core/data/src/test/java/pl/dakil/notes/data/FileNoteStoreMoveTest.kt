package pl.dakil.notes.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileNoteStoreMoveTest {

    private val root: File = Files.createTempDirectory("notes-move").toFile()
    private val store = FileNoteStore(root)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun note(name: String, body: String): StoreRef =
        File(root, name).apply { writeText(body) }.let { StoreRef(it.absolutePath) }

    @Test
    fun `renaming a note onto the name of another leaves that other note alone`() = runBlocking {
        val victim = note("Groceries.daknote", "the one already there")
        note("Scratch.daknote", "the one being renamed").let { source ->
            val moved = store.move(source, StoreRef("Groceries.daknote"))

            // `File.renameTo` would have overwritten the target without a word, which for a note
            // taker means a note the user never touched is simply gone.
            assertEquals("the one already there", File(victim.value).readText())
            assertEquals("Groceries (2).daknote", File(moved.value).name)
            assertEquals("the one being renamed", File(moved.value).readText())
        }
    }

    @Test
    fun `renaming a note to the name it already has is not a rename`() = runBlocking {
        val ref = note("Groceries.daknote", "unchanged")

        val moved = store.move(ref, StoreRef("Groceries.daknote"))

        // The collision here is the file with itself: stepping aside from it would rename a note
        // to "Groceries (2)" for confirming the name it was already called.
        assertEquals(ref, moved)
        assertTrue(File(ref.value).exists())
    }

    @Test
    fun `a name a file system could not take is cleaned up rather than rejected`() = runBlocking {
        val ref = note("Scratch.daknote", "content")

        val moved = store.move(ref, StoreRef("Notes: 12/03.daknote"))

        assertEquals("Notes  12 03.daknote", File(moved.value).name)
        assertEquals("content", File(moved.value).readText())
    }
}
