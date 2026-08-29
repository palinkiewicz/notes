package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising a note that moved, rather than uploading it again and deleting the old copy.
 *
 * The safety net underneath all of it: every create and update runs before every delete, so a
 * *missed* rename costs bandwidth and never bytes. That is what lets the matchers stay cheap.
 */
class RenameDetectionTest {

    private val policy = SyncPolicy(now = 1_787_926_353_000L, deviceId = "K7QF2")

    private fun local(path: String, hash: String, size: Long = 10, noteId: String = "") =
        LocalEntry(path, false, size, 1000, noteId, hash)

    private fun remote(path: String) =
        RemoteEntry(RemoteId("id-$path"), RemotePath(path), false, 10, 1000, RemoteVersion("v1"))

    private fun record(path: String, hash: String, size: Long = 10, noteId: String = "") =
        NoteRecord(path, noteId, 1000, size, hash, "id-$path", "v1", 1000, size)

    private fun plan(local: List<LocalEntry>, remote: List<RemoteEntry>, records: List<NoteRecord>) =
        SyncPlanner.plan(
            LocalListing(local),
            RemoteListing(remote),
            SyncState(notes = records.associateBy { it.path }),
            policy,
        )

    @Test
    fun `a renamed ink note is matched by its stable id and moved, not re-uploaded`() {
        val p = plan(
            local = listOf(local("Work/Standup.daknote", "changed", size = 99, noteId = "note-1")),
            remote = listOf(remote("Standup.daknote")),
            records = listOf(record("Standup.daknote", "original", noteId = "note-1")),
        )

        // `NoteMeta.id` survives rename and move, so even a note edited at the same time is matched
        // exactly — no re-upload of a sheet full of ink.
        val move = p.actions.filterIsInstance<SyncAction.MoveRemote>().single()
        assertEquals("Standup.daknote", move.from)
        assertEquals("Work/Standup.daknote", move.path)
    }

    @Test
    fun `a renamed markdown note is matched by the bytes the last sync recorded`() {
        val p = plan(
            local = listOf(local("Shopping.md", "aaa")),
            remote = listOf(remote("Groceries.md")),
            records = listOf(record("Groceries.md", "aaa")),
        )

        val move = p.actions.filterIsInstance<SyncAction.MoveRemote>().single()
        assertEquals("Groceries.md", move.from)
        assertEquals("Shopping.md", move.path)
    }

    @Test
    fun `a markdown note renamed and edited at once is a delete and a create, and the create runs first`() {
        val p = plan(
            local = listOf(local("Shopping.md", "bbb", size = 20)),
            remote = listOf(remote("Groceries.md")),
            records = listOf(record("Groceries.md", "aaa")),
        )

        // A `.md` file has no id to keep, so this is the honest degradation: correct, never lossy,
        // occasionally wasteful. The ordering is what makes it safe.
        val upload = p.actions.indexOfFirst { it is SyncAction.Upload && it.path == "Shopping.md" }
        val delete = p.actions.indexOfFirst { it is SyncAction.DeleteRemote }
        assertTrue("upload=$upload delete=$delete", upload >= 0 && delete >= 0 && upload < delete)
    }

    @Test
    fun `a conflict copy is never mistaken for a rename of the note it was copied from`() {
        val copy = ConflictNaming.nameFor("Groceries.md", policy.now, "K7QF2")

        val p = plan(
            local = listOf(local("Groceries.md", "aaa"), local(copy, "aaa")),
            remote = listOf(remote("Groceries.md")),
            records = listOf(record("Groceries.md", "aaa")),
        )

        // It shares its bytes with the original by construction, so a hash matcher would happily
        // pair the two and move the original on top of the copy.
        assertTrue(p.actions.none { it is SyncAction.MoveRemote })
        assertEquals(listOf(copy), p.actions.filterIsInstance<SyncAction.Upload>().map { it.path })
    }

    @Test
    fun `an unmatched rename never loses bytes, because every create runs before every delete`() {
        val p = plan(
            local = listOf(local("New.md", "brand new", size = 42)),
            remote = listOf(remote("Old.md")),
            records = listOf(record("Old.md", "old bytes")),
        )

        val firstDelete = p.actions.indexOfFirst { it is SyncAction.DeleteRemote }
        val lastWrite = p.actions.indexOfLast { it is SyncAction.Upload || it is SyncAction.Download }
        assertTrue(firstDelete == -1 || lastWrite < firstDelete)
    }
}
