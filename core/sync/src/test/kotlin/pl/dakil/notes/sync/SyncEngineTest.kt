package pl.dakil.notes.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine against in-memory fakes: what actually reaches the disk and the wire. */
class SyncEngineTest {

    private val local = FakeLocalMirror()
    private val remote = FakeRemoteBackend()
    private val state = FakeStateStore()

    private fun engine(now: Long = FAKE_NOW) =
        SyncEngine(local, remote, state, deviceId = "K7QF2", localRoot = "/notes", clock = { now })

    @Test
    fun `a new local note reaches the remote`() = runBlocking {
        local.put("Groceries.md", "milk")

        val outcome = engine().run()

        assertEquals("milk", remote.text("Groceries.md"))
        assertEquals(1, outcome.uploaded)
    }

    @Test
    fun `a new remote note reaches the device`() = runBlocking {
        remote.put("Groceries.md", "milk")

        val outcome = engine().run()

        assertEquals("milk", local.text("Groceries.md"))
        assertEquals(1, outcome.downloaded)
    }

    @Test
    fun `a second pass with nothing changed moves nothing`() = runBlocking {
        local.put("Groceries.md", "milk")
        engine().run()
        remote.log.clear()
        local.log.clear()

        val outcome = engine().run()

        // The state written by the first pass is what makes this free. Without it every pass would
        // be a first sync, and a first sync transfers the whole library.
        assertEquals(0, outcome.changed)
        assertEquals(emptyList<String>(), remote.log)
        assertEquals(emptyList<String>(), local.log)
    }

    @Test
    fun `a deletion on this device removes the note from the remote`() = runBlocking {
        local.put("Groceries.md", "milk")
        engine().run()

        local.delete("Groceries.md", "")
        val outcome = engine().run()

        assertEquals(emptySet<String>(), remote.paths())
        assertEquals(1, outcome.deleted)
    }

    @Test
    fun `an edit on both sides keeps the loser as a conflict copy beside the winner`() = runBlocking {
        local.put("Groceries.md", "milk")
        engine().run()

        local.put("Groceries.md", "milk and eggs")
        // Local edited a minute after the remote one, well outside the tie band.
        local.modifiedAt = FAKE_NOW + 60_000
        remote.put("Groceries.md", "milk and bread")
        remote.remoteNow = FAKE_NOW

        val outcome = engine().run()

        // The newer edit wins the path; the other is kept, not discarded.
        assertEquals("milk and eggs", local.text("Groceries.md"))
        val copy = local.paths().single { ConflictNaming.isConflictCopy(it) }
        assertEquals("milk and bread", local.text(copy))
        assertEquals(listOf(copy), outcome.conflicts)
    }

    @Test
    fun `state is written after every action, so a killed pass resumes rather than restarts`() = runBlocking {
        local.put("A.md", "a")
        local.put("B.md", "b")
        local.put("C.md", "c")

        state.saves = 0
        engine().run()

        // One save per applied action plus the closing one. A pass that only saved at the end
        // would, when the job window expired, replan against bookkeeping that no longer matched.
        assertTrue("saves=${state.saves}", state.saves >= 4)
    }

    @Test
    fun `a write the remote refuses leaves the record unadvanced, so the next pass sees a conflict`() =
        runBlocking {
            local.put("Groceries.md", "milk")
            remote.failWritesFor = "Groceries.md"

            val outcome = engine().run()

            assertEquals(0, outcome.uploaded)
            // Nothing recorded means the next pass still knows this note is unsynced.
            assertNull(state.load("fake-${"/notes".hashCode().toUInt().toString(16)}")?.notes?.get("Groceries.md"))
        }

    @Test
    fun `an incomplete remote listing never deletes anything on this device`() = runBlocking {
        local.put("Groceries.md", "milk")
        engine().run()

        // The shape a lapsed credential or a half-finished page sweep produces.
        remote.listingComplete = false
        val outcome = engine().run()

        assertTrue(local.paths().contains("Groceries.md"))
        assertEquals(0, outcome.deleted)
        assertTrue(outcome.refusals.any { it is SyncRefusal.ListingIncomplete })
    }

    @Test
    fun `a note open in the editor is uploaded but never overwritten underneath the user`() = runBlocking {
        local.put("Open.md", "typing")
        engine().run()

        remote.put("Open.md", "from the other device")
        local.open = setOf("Open.md")

        engine().run()

        assertEquals("typing", local.text("Open.md"))
    }

    @Test
    fun `the index is rebuilt only when files actually changed`() = runBlocking {
        local.put("Groceries.md", "milk")
        engine().run()
        assertEquals(1, local.reindexed)

        engine().run()

        // A quiet pass must not make the library rescan itself on a timer for nothing.
        assertEquals(1, local.reindexed)
    }

    @Test
    fun `state taken against a different library root is not adopted`() = runBlocking {
        local.put("Groceries.md", "milk")
        engine().run()
        val key = "fake-${"/notes".hashCode().toUInt().toString(16)}"
        val stored = state.load(key)
        assertNotNull(stored)

        // Same remote, different folder on the device: those records describe other files, and
        // adopting them would read this library's notes as deleted.
        state.save(key, stored!!.copy(localRoot = "/somewhere-else"))
        remote.log.clear()
        val outcome = engine().run()

        assertEquals(0, outcome.deleted)
    }
}
