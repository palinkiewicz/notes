package pl.dakil.notes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The file extension is the only record of what kind a note is, so this is the whole type system for
 * note kinds — a wrong answer here opens the wrong editor.
 */
class NoteKindTest {

    @Test
    fun `each kind is recognised by its own extension`() {
        assertEquals(NoteKind.INK, NoteKind.of("Shopping.daknote"))
        assertEquals(NoteKind.TEXT, NoteKind.of("Shopping.md"))
    }

    @Test
    fun `an extension the app does not own is not a note`() {
        assertNull(NoteKind.of("photo.png"))
        assertNull(NoteKind.of("notes.txt"))
        assertNull(NoteKind.of("README"))
        // A dotfile is all extension and no name; treating it as one would list every stray config
        // file in the library.
        assertNull(NoteKind.of(".md"))
    }

    @Test
    fun `case does not decide the kind`() {
        assertEquals(NoteKind.TEXT, NoteKind.of("Shopping.MD"))
    }

    @Test
    fun `only the last extension counts`() {
        assertEquals(NoteKind.TEXT, NoteKind.of("2026.02.01 standup.md"))
        assertEquals("2026.02.01 standup", NoteKind.titleOf("2026.02.01 standup.md"))
    }

    @Test
    fun `the title is the file name without its extension`() {
        assertEquals("Shopping", NoteKind.titleOf("Shopping.daknote"))
        assertEquals("Shopping", NoteKind.titleOf("Shopping.md"))
    }

    @Test
    fun `a file this app does not own keeps its whole name as a title`() {
        assertEquals("photo.png", NoteKind.titleOf("photo.png"))
    }
}
