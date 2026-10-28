package pl.dakil.notes.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.sync.TrashNaming
import java.io.File
import java.nio.file.Files

class LibraryTrashTest {

    private val root: File = Files.createTempDirectory("notes-trash").toFile()
    private val store = FileNoteStore(root)
    private var now = 1_793_220_912_345L
    private val day = 24L * 60 * 60 * 1000

    private val forgotten = ArrayList<String>()
    private val reindexed = ArrayList<String>()
    private val indexing = object : LibraryTrash.Indexing {
        override suspend fun forget(ref: StoreRef, path: String, directoryName: String?) {
            forgotten += directoryName ?: ref.fileName()
        }

        override suspend fun reindex(folder: StoreRef, path: String, recursive: Boolean) {
            reindexed += path
        }
    }
    private val trash = LibraryTrash(store, indexing) { now }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun ref(file: File) = StoreRef(file.absolutePath)

    private fun note(path: String, body: String = path): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(body) }

    @Test
    fun `a deleted note goes to the trash, out of the library and out of the index`() = runBlocking {
        val plan = note("Work/Plan.daknote")
        val item = trash.moveToTrash(ref(plan), ref(plan.parentFile), "Work").getOrThrow()

        assertFalse(plan.exists())
        assertEquals("Work", item.originPath)
        assertEquals(listOf("Plan.daknote"), forgotten)
        assertEquals("Work/Plan.daknote", File(item.ref.value).readText())
        assertTrue(File(item.ref.value).path.contains(TrashNaming.DIR))
        assertEquals(listOf("Plan.daknote"), trash.list().map { it.name })
    }

    @Test
    fun `a restored note goes back to its folder, even one deleted since`() = runBlocking {
        val plan = note("Work/Q3/Plan.daknote", "the plan")
        val item = trash.moveToTrash(ref(plan), ref(plan.parentFile), "Work/Q3").getOrThrow()
        File(root, "Work").deleteRecursively()

        trash.restore(item).getOrThrow()

        assertEquals("the plan", File(root, "Work/Q3/Plan.daknote").readText())
        assertEquals(listOf("Work/Q3"), reindexed)
        assertTrue("the emptied batch folder goes", trash.list().isEmpty())
        assertEquals(0, File(root, TrashNaming.DIR).listFiles()!!.size)
    }

    @Test
    fun `restoring onto a name taken in the meantime keeps both notes`() = runBlocking {
        val plan = note("Plan.daknote", "old")
        val item = trash.moveToTrash(ref(plan), ref(root), "").getOrThrow()
        note("Plan.daknote", "new")

        val back = trash.restore(item).getOrThrow()

        assertEquals("new", File(root, "Plan.daknote").readText())
        assertEquals("old", File(back.value).readText())
    }

    @Test
    fun `a folder goes to the trash with everything in it, and comes back the same`() = runBlocking {
        note("Recipes/Soup.md")
        note("Recipes/Cakes/Sponge.daknote")
        val folder = File(root, "Recipes")
        val item = trash.moveToTrash(ref(folder), ref(root), "", directoryName = "Recipes").getOrThrow()
        assertTrue(item.isDirectory)
        assertFalse(folder.exists())

        trash.restore(item).getOrThrow()

        assertEquals("Recipes/Cakes/Sponge.daknote", File(root, "Recipes/Cakes/Sponge.daknote").readText())
        assertEquals(listOf("Recipes"), reindexed)
    }

    @Test
    fun `the trash lets go of what is thirty days old and keeps the rest`() = runBlocking {
        val old = note("Old.daknote")
        trash.moveToTrash(ref(old), ref(root), "").getOrThrow()
        now += 20 * day
        val recent = note("Recent.daknote")
        trash.moveToTrash(ref(recent), ref(root), "").getOrThrow()
        // Something in the trash this app did not put there, which it must not touch.
        File(root, "${TrashNaming.DIR}/someone else's").mkdirs()

        now += 11 * day
        assertEquals(1, trash.purge().getOrThrow())

        assertEquals(listOf("Recent.daknote"), trash.list().map { it.name })
        assertTrue(File(root, "${TrashNaming.DIR}/someone else's").exists())
    }

    @Test
    fun `deleting for good and emptying the trash leave nothing behind`() = runBlocking {
        val a = trash.moveToTrash(ref(note("A.daknote")), ref(root), "").getOrThrow()
        trash.moveToTrash(ref(note("B.daknote")), ref(root), "").getOrThrow()

        trash.deleteForever(a).getOrThrow()
        assertEquals(listOf("B.daknote"), trash.list().map { it.name })

        trash.empty().getOrThrow()
        assertTrue(trash.list().isEmpty())
        assertFalse(File(root, TrashNaming.DIR).exists())
    }
}
