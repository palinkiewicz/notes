package pl.dakil.notes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReadMetaTest {

    @Test
    fun `a manifest can be read without unzipping the strokes`() {
        val note = DakNote.newNote(title = "Standup", now = 1_787_926_353_000L)
        val bytes = ByteArrayOutputStream().also { DakNoteWriter.write(note, it) }.toByteArray()

        val meta = DakNoteReader.readMeta(ByteArrayInputStream(bytes))

        // The id is how a `.daknote` is recognised after a rename, so sync asks this of every file
        // in the library on every pass. Inflating each sheet to answer it would be unaffordable.
        assertEquals(note.meta.id, meta?.id)
        assertEquals("Standup", meta?.title)
    }

    @Test
    fun `a file that is not a note answers null rather than throwing`() {
        assertNull(DakNoteReader.readMeta(ByteArrayInputStream("not a zip".toByteArray())))
        assertNull(DakNoteReader.readMeta(ByteArrayInputStream(ByteArray(0))))
    }
}
