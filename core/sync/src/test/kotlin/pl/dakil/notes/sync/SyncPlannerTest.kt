package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table, one test per row.
 *
 * Every case here is a way a note-taker loses work if it is wrong, so each name says what the user
 * would experience rather than which branch is taken.
 */
class SyncPlannerTest {

    private val now = 1_787_926_353_000L
    private val policy = SyncPolicy(now = now, deviceId = "K7QF2")

    private fun local(path: String, hash: String, size: Long = 10, modified: Long = 1000, noteId: String = "") =
        LocalEntry(path, isDirectory = false, sizeBytes = size, modifiedAt = modified, noteId = noteId, contentHash = hash)

    private fun remote(path: String, version: String = "v1", size: Long = 10, modified: Long = 1000) =
        RemoteEntry(RemoteId("id-$path"), RemotePath(path), false, size, modified, RemoteVersion(version))

    private fun record(
        path: String, hash: String, size: Long = 10, modified: Long = 1000,
        version: String = "v1", noteId: String = "",
    ) = NoteRecord(path, noteId, modified, size, hash, "id-$path", version, 1000, size)

    private fun plan(
        local: List<LocalEntry> = emptyList(),
        remote: List<RemoteEntry> = emptyList(),
        records: List<NoteRecord> = emptyList(),
        policy: SyncPolicy = this.policy,
        localComplete: Boolean = true,
        remoteComplete: Boolean = true,
    ) = SyncPlanner.plan(
        LocalListing(local, localComplete),
        RemoteListing(remote, complete = remoteComplete),
        SyncState(notes = records.associateBy { it.path }),
        policy,
    )

    // ---- New on one side --------------------------------------------------------------------

    @Test
    fun `an unrecorded local note is uploaded`() {
        val p = plan(local = listOf(local("Groceries.md", "aaa")))

        assertEquals(listOf(SyncAction.Upload("Groceries.md", null, null)), p.actions)
    }

    @Test
    fun `an unrecorded remote note is downloaded`() {
        val p = plan(remote = listOf(remote("Groceries.md")))

        assertEquals(1, p.actions.size)
        assertTrue(p.actions.single() is SyncAction.Download)
    }

    // ---- First sync -------------------------------------------------------------------------

    @Test
    fun `a first sync of identical bytes on both sides moves no data at all`() {
        val p = plan(
            local = listOf(local("Groceries.md", "aaa", size = 10)),
            remote = listOf(remote("Groceries.md", size = 10)),
        )

        // Adopted into the state instead: the common case of pointing two devices at the same
        // folder must not cost a full re-upload of the library.
        assertEquals(emptyList<SyncAction>(), p.actions)
        assertEquals(listOf("Groceries.md"), p.adopt.map { it.path })
    }

    @Test
    fun `a first sync of different bytes keeps both rather than picking a winner silently`() {
        val p = plan(
            local = listOf(local("Groceries.md", "aaa", size = 10, modified = 5000)),
            remote = listOf(remote("Groceries.md", size = 99, modified = 1000)),
        )

        val action = p.actions.single() as SyncAction.PreserveThen
        assertEquals(Side.REMOTE, action.loser)
        assertTrue(action.copyPath, ConflictNaming.isConflictCopy(action.copyPath))
    }

    @Test
    fun `a first sync never deletes anything, however many records are missing`() {
        val p = plan(
            local = listOf(local("A.md", "a"), local("B.md", "b")),
            remote = listOf(remote("C.md")),
        )

        // Every delete row is gated on a record existing, so an empty state cannot produce one.
        assertTrue(p.actions.none { it is SyncAction.DeleteLocal || it is SyncAction.DeleteRemote })
    }

    // ---- Ordinary edits ---------------------------------------------------------------------

    @Test
    fun `a note whose timestamp moved but whose bytes did not is left alone`() {
        val p = plan(
            local = listOf(local("Groceries.md", "aaa", modified = 9999)),
            remote = listOf(remote("Groceries.md")),
            records = listOf(record("Groceries.md", "aaa")),
        )

        // Syncthing and a few file managers touch mtimes without changing content. Reading that as
        // an edit would make the two devices push the same bytes at each other forever.
        assertEquals(emptyList<SyncAction>(), p.actions)
    }

    @Test
    fun `a note changed only locally is uploaded with the recorded remote version as its precondition`() {
        val p = plan(
            local = listOf(local("Groceries.md", "bbb", size = 20, modified = 9999)),
            remote = listOf(remote("Groceries.md", version = "v1")),
            records = listOf(record("Groceries.md", "aaa", version = "v1")),
        )

        assertEquals(SyncAction.Upload("Groceries.md", "id-Groceries.md", "v1"), p.actions.single())
    }

    @Test
    fun `a note changed only remotely is downloaded`() {
        val p = plan(
            local = listOf(local("Groceries.md", "aaa")),
            remote = listOf(remote("Groceries.md", version = "v2")),
            records = listOf(record("Groceries.md", "aaa", version = "v1")),
        )

        assertTrue(p.actions.single() is SyncAction.Download)
    }

    @Test
    fun `a note changed on both sides to the same bytes converges without traffic`() {
        val p = plan(
            local = listOf(local("Groceries.md", "ccc", size = 30, modified = 9999)),
            remote = listOf(remote("Groceries.md", version = "v2", size = 30)),
            records = listOf(record("Groceries.md", "aaa", size = 10, version = "v1")),
        )

        assertEquals(emptyList<SyncAction>(), p.actions)
        assertEquals(1, p.adopt.size)
    }

    @Test
    fun `a note changed on both sides loses the older edit to a conflict copy, not to the bin`() {
        val p = plan(
            local = listOf(local("Groceries.md", "bbb", size = 20, modified = 9_000_000)),
            remote = listOf(remote("Groceries.md", version = "v2", size = 30, modified = 1_000_000)),
            records = listOf(record("Groceries.md", "aaa", size = 10, version = "v1")),
        )

        val action = p.actions.single() as SyncAction.PreserveThen
        assertEquals(Side.REMOTE, action.loser)
        assertTrue(action.then is SyncAction.Upload)
    }

    // ---- Deletes ----------------------------------------------------------------------------

    @Test
    fun `a note deleted locally is deleted remotely`() {
        val p = plan(
            remote = listOf(remote("Groceries.md", version = "v1")),
            records = listOf(record("Groceries.md", "aaa", version = "v1")),
        )

        assertEquals(SyncAction.DeleteRemote("Groceries.md", "id-Groceries.md", "v1"), p.actions.single())
    }

    @Test
    fun `a note deleted remotely is deleted locally`() {
        val p = plan(
            local = listOf(local("Groceries.md", "aaa")),
            records = listOf(record("Groceries.md", "aaa")),
        )

        assertEquals(SyncAction.DeleteLocal("Groceries.md", "aaa"), p.actions.single())
    }

    @Test
    fun `a note deleted locally but edited remotely comes back, because a delete cannot be merged and an edit can`() {
        val p = plan(
            remote = listOf(remote("Groceries.md", version = "v2")),
            records = listOf(record("Groceries.md", "aaa", version = "v1")),
        )

        assertTrue(p.actions.single() is SyncAction.Download)
    }

    @Test
    fun `a note deleted remotely but edited locally comes back`() {
        val p = plan(
            local = listOf(local("Groceries.md", "bbb", size = 20, modified = 9999)),
            records = listOf(record("Groceries.md", "aaa")),
        )

        assertEquals(SyncAction.Upload("Groceries.md", null, null), p.actions.single())
    }

    @Test
    fun `a note deleted on both sides leaves only a record to forget`() {
        val p = plan(records = listOf(record("Groceries.md", "aaa")))

        assertEquals(emptyList<SyncAction>(), p.actions)
        assertEquals(listOf("Groceries.md"), p.forget)
    }
}
