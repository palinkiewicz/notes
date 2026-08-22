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
    fun `a note moved into a folder leaves nothing behind`() = runBlocking {
        val target = File(root, "Work").apply { mkdirs() }
        val ref = note("Groceries.daknote", "content")

        val moved = store.moveTo(ref, StoreRef(root.absolutePath), StoreRef(target.absolutePath))

        assertEquals(File(target, "Groceries.daknote").absolutePath, moved.value)
        assertEquals("content", File(moved.value).readText())
        assertTrue(!File(ref.value).exists())
    }

    @Test
    fun `moving onto a name already taken in the destination steps aside`() = runBlocking {
        // Same reasoning as a rename collision: `renameTo` would overwrite the note already there
        // without a word, and a note the user never touched would simply be gone.
        val target = File(root, "Work").apply { mkdirs() }
        File(target, "Groceries.daknote").writeText("the one already there")
        val ref = note("Groceries.daknote", "the one being moved")

        val moved = store.moveTo(ref, StoreRef(root.absolutePath), StoreRef(target.absolutePath))

        assertEquals("Groceries (2).daknote", File(moved.value).name)
        assertEquals("the one already there", File(target, "Groceries.daknote").readText())
        assertEquals("the one being moved", File(moved.value).readText())
    }

    @Test
    fun `a folder moves with everything inside it`() = runBlocking {
        val source = File(root, "Recipes").apply { mkdirs() }
        File(source, "Bread.daknote").writeText("dough")
        File(source, "Sauces").mkdirs()
        File(source, "Sauces/Pesto.daknote").writeText("basil")
        val target = File(root, "Archive").apply { mkdirs() }

        val moved = store.moveTo(
            StoreRef(source.absolutePath),
            StoreRef(root.absolutePath),
            StoreRef(target.absolutePath),
        )

        assertEquals("dough", File(moved.value, "Bread.daknote").readText())
        assertEquals("basil", File(moved.value, "Sauces/Pesto.daknote").readText())
        assertTrue(!source.exists())
    }

    @Test
    fun `moving into the folder it is already in changes nothing`() = runBlocking {
        val ref = note("Groceries.daknote", "unchanged")

        val moved = store.moveTo(ref, StoreRef(root.absolutePath), StoreRef(root.absolutePath))

        // Stepping aside from the collision here would rename a note to "Groceries (2)" for being
        // dropped where it already was.
        assertEquals(ref, moved)
        assertEquals("unchanged", File(ref.value).readText())
    }

    @Test
    fun `a name a file system could not take is cleaned up rather than rejected`() = runBlocking {
        val ref = note("Scratch.daknote", "content")

        val moved = store.move(ref, StoreRef("Notes: 12/03.daknote"))

        assertEquals("Notes  12 03.daknote", File(moved.value).name)
        assertEquals("content", File(moved.value).readText())
    }
}
