package pl.dakil.notes.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.data.NoteSummary
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind

class LibraryMoveTest {

    private fun folder(name: String, path: String) =
        LibraryItem.Folder(StoreRef("/root/$name"), name, 0L, path)

    private fun note(title: String, path: String) = LibraryItem.Note(
        NoteSummary(
            ref = StoreRef("/root/$title.daknote"),
            noteId = title,
            kind = NoteKind.INK,
            title = title,
            tags = emptyList(),
            modifiedAt = 0L,
            createdAt = 0L,
            snippet = "",
            path = path,
        )
    )

    private fun request(vararg items: LibraryItem) =
        MoveRequest(items.toList(), items.associate { it.ref to StoreRef("/root") })

    @Test
    fun `a folder cannot be moved into itself`() {
        // The subtree would be detached from the library, and on a filesystem that allows it, the
        // folder would contain itself.
        val move = request(folder("Work", path = ""))

        assertFalse(canMoveInto(move, "Work"))
    }

    @Test
    fun `a folder cannot be moved into one of its own descendants`() {
        val move = request(folder("Work", path = ""))

        assertFalse(canMoveInto(move, "Work/Q3/Drafts"))
    }

    @Test
    fun `a folder whose name is a prefix of the destination is not a descendant of it`() {
        // "Work" and "Workshop" share five characters and nothing else; a naive prefix test would
        // refuse a move that is perfectly legal.
        val move = request(folder("Work", path = ""))

        assertTrue(canMoveInto(move, "Workshop"))
    }

    @Test
    fun `moving items back where they came from is refused`() {
        // Not dangerous, merely pointless — but an enabled button that does nothing is worse than
        // a disabled one, which at least says the destination is not a new one.
        val move = request(note("Groceries", path = "Work"))

        assertFalse(canMoveInto(move, "Work"))
    }

    @Test
    fun `notes may go anywhere but home`() {
        val move = request(note("Groceries", path = ""))

        assertTrue(canMoveInto(move, "Work/Q3"))
    }

    @Test
    fun `a selection gathered from several folders may land in one of them`() {
        // Search results can come from anywhere beneath the folder that was searched. One of them
        // already being in the destination is no reason to refuse to gather up the rest.
        val move = request(note("Groceries", path = "Work"), note("Ideas", path = "Home"))

        assertTrue(canMoveInto(move, "Work"))
    }

    @Test
    fun `one illegal folder in the selection blocks the whole move`() {
        // The alternative is moving some of what the user selected and silently leaving the rest,
        // which is a worse outcome than refusing.
        val move = request(note("Groceries", path = ""), folder("Work", path = ""))

        assertFalse(canMoveInto(move, "Work"))
    }
}
