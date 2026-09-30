package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateCodecTest {

    private val state = SyncState(
        backendId = "webdav",
        remoteRoot = "Notes",
        localRoot = "content://tree/primary%3ANotes",
        deviceId = "K7QF2",
        lastSyncAt = 1_787_926_353_000L,
        clockSkewMs = -1200,
        folders = mapOf("Work" to "id-work", "Work/Q3" to "id-q3"),
        notes = mapOf(
            "Groceries.md" to NoteRecord("Groceries.md", "", 1000, 42, "abc", "r1", "v1", 2000, 42),
        ),
    )

    @Test
    fun `a state file survives a round trip`() {
        val read = SyncStateCodec.read(SyncStateCodec.write(state))

        assertEquals(state.copy(unknown = read!!.unknown), read)
    }

    @Test
    fun `writing an unchanged state twice produces identical bytes`() {
        // This file can itself sit in a synced folder. A writer that reordered its own keys would
        // manufacture a conflict on every pass.
        assertEquals(SyncStateCodec.write(state), SyncStateCodec.write(state))
    }

    @Test
    fun `records are written in a stable order however the map was built`() {
        val a = state.copy(notes = linkedMapOf(
            "B.md" to NoteRecord("B.md", "", 1, 1, "b", "", "", 0, 0),
            "A.md" to NoteRecord("A.md", "", 1, 1, "a", "", "", 0, 0),
        ))
        val b = state.copy(notes = linkedMapOf(
            "A.md" to NoteRecord("A.md", "", 1, 1, "a", "", "", 0, 0),
            "B.md" to NoteRecord("B.md", "", 1, 1, "b", "", "", 0, 0),
        ))

        assertEquals(SyncStateCodec.write(a), SyncStateCodec.write(b))
    }

    @Test
    fun `a key written by a later version of the app is still there after this one saves`() {
        val original = SyncStateCodec.write(state).replace(
            """"deviceId": "K7QF2",""",
            """"deviceId": "K7QF2",
  "somethingNewer": {"kept": true},""",
        )

        val rewritten = SyncStateCodec.write(SyncStateCodec.read(original)!!)

        // Same forward-compatibility rule the container follows: an older build must not quietly
        // strip a newer build's bookkeeping.
        assertTrue(rewritten, rewritten.contains("somethingNewer"))
    }

    @Test
    fun `a state file from an unknown version is discarded rather than half read`() {
        val future = SyncStateCodec.write(state).replace(""""version": 1""", """"version": 99""")

        // Null makes the next pass a first sync, which by construction deletes nothing. Half-read
        // bookkeeping would instead read real notes as deleted on both sides.
        assertNull(SyncStateCodec.read(future))
    }

    @Test
    fun `a file that is not json at all is discarded rather than throwing`() {
        assertNull(SyncStateCodec.read("this is not json"))
        assertNull(SyncStateCodec.read(""))
    }

    @Test
    fun `a note id index is built only for the notes that have one`() {
        val withInk = state.copy(notes = state.notes + mapOf(
            "S.daknote" to NoteRecord("S.daknote", "note-1", 1, 1, "h", "", "", 0, 0),
        ))

        assertEquals(setOf("note-1"), withInk.byNoteId.keys)
        assertNotNull(withInk.byNoteId["note-1"])
    }
}
