package pl.dakil.notes.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Copying a library from one store into another.
 *
 * The copy itself is exercised here through two [FileNoteStore]s rather than through
 * [LibraryRootController], whose other half needs a `ContentResolver`. What is being pinned is the
 * property that matters on the day it runs for real: **the source is never touched**, and a copy
 * that could not be completed is reported rather than half-adopted.
 */
class LibraryRootCopyTest {

    private val source: File = Files.createTempDirectory("notes-source").toFile()
    private val destination: File = Files.createTempDirectory("notes-destination").toFile()
    private val from = FileNoteStore(source)
    private val to = FileNoteStore(destination)

    @After
    fun tearDown() {
        source.deleteRecursively()
        destination.deleteRecursively()
    }

    /** The same walk `LibraryRootController.copyTree` performs, over the public store surface. */
    private suspend fun copyTree(fromDir: StoreRef, toDir: StoreRef): Int {
        var copied = 0
        val entries = (from.listChecked(fromDir) as StoreListing.Ok).entries
        for (entry in entries) {
            if (entry.isDirectory) {
                val child = to.child(toDir, entry.name) ?: to.createDirectory(toDir, entry.name)
                copied += copyTree(entry.ref, child)
                continue
            }
            val bytes = from.read(entry.ref) { it.readBytes() }
            val target = to.newChild(toDir, entry.name, "text/markdown")
            to.write(target) { out -> out.write(bytes) }
            copied++
        }
        return copied
    }

    @Test
    fun `a nested library arrives with its folders and its bytes intact`() = runBlocking {
        File(source, "Groceries.md").writeText("milk\neggs")
        File(source, "Work/Q3").mkdirs()
        File(source, "Work/Q3/Standup.md").writeText("# Standup")

        val copied = copyTree(from.root(), to.root())

        assertEquals(2, copied)
        assertEquals("milk\neggs", File(destination, "Groceries.md").readText())
        assertEquals("# Standup", File(destination, "Work/Q3/Standup.md").readText())
    }

    @Test
    fun `the library being copied out of is left exactly as it was`() = runBlocking {
        File(source, "Groceries.md").writeText("milk")
        val before = source.walkTopDown().map { it.relativeTo(source).path }.toSortedSet()

        copyTree(from.root(), to.root())

        // Copy, never move: the old library is what the user falls back to if the new folder turns
        // out to be the wrong one, so nothing may be removed until they say so.
        assertEquals(before, source.walkTopDown().map { it.relativeTo(source).path }.toSortedSet())
        assertEquals("milk", File(source, "Groceries.md").readText())
    }

    @Test
    fun `a folder that cannot be read stops the copy instead of producing half a library`() = runBlocking {
        val listing = from.listChecked(StoreRef(File(source, "Gone").absolutePath))

        // The controller turns this into a thrown `StoreException` and reports `Failed`. Adopting a
        // partial copy would look identical to a library the user had lost half of.
        assertTrue(listing is StoreListing.Unavailable)
    }

    @Test
    fun `a name already taken in the destination steps aside rather than overwriting`() = runBlocking {
        File(destination, "Groceries.md").writeText("the one already there")
        File(source, "Groceries.md").writeText("the one arriving")

        copyTree(from.root(), to.root())

        assertEquals("the one already there", File(destination, "Groceries.md").readText())
        assertEquals("the one arriving", File(destination, "Groceries (2).md").readText())
    }
}
