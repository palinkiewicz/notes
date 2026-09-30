package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refusals. Each one is a case where doing the obvious thing destroys a library. */
class SyncPlannerGuardsTest {

    private val policy = SyncPolicy(now = 1_787_926_353_000L, deviceId = "K7QF2")

    private fun local(path: String, hash: String, size: Long = 10, modified: Long = 1000, dir: Boolean = false) =
        LocalEntry(path, dir, size, modified, contentHash = hash)

    private fun remote(path: String, version: String = "v1") =
        RemoteEntry(RemoteId("id-$path"), RemotePath(path), false, 10, 1000, RemoteVersion(version))

    private fun record(path: String, hash: String) =
        NoteRecord(path, "", 1000, 10, hash, "id-$path", "v1", 1000, 10)

    @Test
    fun `an incomplete local listing suppresses every delete and still allows downloads`() {
        val plan = SyncPlanner.plan(
            LocalListing(emptyList(), complete = false),
            RemoteListing(listOf(remote("A.md"), remote("B.md"))),
            SyncState(notes = listOf(record("A.md", "a")).associateBy { it.path }),
            policy,
        )

        // The library looking empty is exactly what a revoked SAF grant produces. Reading that as
        // "the user deleted everything" and propagating it is the failure this exists to prevent.
        assertTrue(plan.actions.none { it is SyncAction.DeleteRemote })
        assertTrue(plan.refusals.any { it is SyncRefusal.ListingIncomplete })
        assertTrue(plan.actions.any { it is SyncAction.Download })
    }

    @Test
    fun `an incomplete remote listing suppresses every delete and still allows uploads`() {
        val plan = SyncPlanner.plan(
            LocalListing(listOf(local("A.md", "a"))),
            RemoteListing(emptyList(), complete = false),
            SyncState(notes = listOf(record("A.md", "a")).associateBy { it.path }),
            policy,
        )

        assertTrue(plan.actions.none { it is SyncAction.DeleteLocal })
        assertTrue(plan.refusals.any { it is SyncRefusal.ListingIncomplete })
    }

    @Test
    fun `a plan that would delete most of the library is refused until the user confirms it`() {
        val records = (1..20).map { record("Note$it.md", "h$it") }

        val plan = SyncPlanner.plan(
            LocalListing(emptyList()),
            RemoteListing(records.map { remote(it.path) }),
            SyncState(notes = records.associateBy { it.path }),
            policy,
        )

        assertTrue(plan.actions.none { it is SyncAction.DeleteRemote })
        val tripped = plan.refusals.filterIsInstance<SyncRefusal.DeleteGuardTripped>().single()
        assertEquals(20, tripped.wouldDelete)
    }

    @Test
    fun `deleting one note out of four is ordinary use and is not stopped`() {
        val records = (1..4).map { record("Note$it.md", "h$it") }

        val plan = SyncPlanner.plan(
            LocalListing(records.drop(1).map { local(it.path, it.contentHash) }),
            RemoteListing(records.map { remote(it.path) }),
            SyncState(notes = records.associateBy { it.path }),
            policy,
        )

        // A quarter of a four-note library is one note. Without an absolute floor the guard would
        // fire on the most ordinary edit there is.
        assertEquals(1, plan.actions.filterIsInstance<SyncAction.DeleteRemote>().size)
        assertTrue(plan.refusals.none { it is SyncRefusal.DeleteGuardTripped })
    }

    @Test
    fun `the same mass delete goes ahead once the user has confirmed it`() {
        val records = (1..20).map { record("Note$it.md", "h$it") }

        val plan = SyncPlanner.plan(
            LocalListing(emptyList()),
            RemoteListing(records.map { remote(it.path) }),
            SyncState(notes = records.associateBy { it.path }),
            policy.copy(deleteGuardConfirmed = true),
        )

        assertEquals(20, plan.actions.filterIsInstance<SyncAction.DeleteRemote>().size)
    }

    @Test
    fun `a note open in the editor is pushed but never pulled`() {
        val plan = SyncPlanner.plan(
            LocalListing(listOf(local("Open.md", "a"))),
            RemoteListing(listOf(remote("Open.md", version = "v2"))),
            SyncState(notes = listOf(record("Open.md", "a")).associateBy { it.path }),
            policy.copy(pinnedPaths = setOf("Open.md")),
        )

        // Writing under an open document either loses the in-flight edits or is clobbered by the
        // autosave a second later. It is picked up on the next pass instead.
        assertTrue(plan.actions.none { it is SyncAction.Download })
        assertTrue(plan.refusals.any { it is SyncRefusal.NoteOpenInEditor })
    }

    @Test
    fun `a temporary file the file store left behind is never uploaded`() {
        // `FileNoteStore.write` creates one of these on every single save.
        val plan = SyncPlanner.plan(
            LocalListing(listOf(local("Groceries.md.tmp", "a"), local(".stfolder", "b"))),
            RemoteListing(emptyList()),
            SyncState(),
            policy,
        )

        assertEquals(emptyList<SyncAction>(), plan.actions)
    }

    @Test
    fun `a file this app does not own is left alone rather than synced or deleted`() {
        val plan = SyncPlanner.plan(
            LocalListing(listOf(local("scan.pdf", "a"))),
            RemoteListing(emptyList()),
            SyncState(),
            policy,
        )

        assertEquals(emptyList<SyncAction>(), plan.actions)
    }

    @Test
    fun `a remote folder holding two files of the same name syncs one and reports the other`() {
        val duplicate = remote("Standup.md").copy(id = RemoteId("second"))

        val plan = SyncPlanner.plan(
            LocalListing(emptyList()),
            RemoteListing(listOf(remote("Standup.md")), duplicates = listOf(duplicate)),
            SyncState(),
            policy,
        )

        // Drive allows it. Picking one at random would make the note flip content between passes.
        assertEquals(1, plan.actions.filterIsInstance<SyncAction.Download>().size)
        assertTrue(plan.refusals.any { it is SyncRefusal.DuplicateRemoteName })
    }

    @Test
    fun `upload-only never writes to this device`() {
        val plan = SyncPlanner.plan(
            LocalListing(emptyList()),
            RemoteListing(listOf(remote("A.md"))),
            SyncState(),
            policy.copy(direction = SyncDirection.UPLOAD_ONLY),
        )

        assertEquals(emptyList<SyncAction>(), plan.actions)
    }
}
