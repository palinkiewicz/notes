package pl.dakil.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class NoteViewStateCodecTest {

    @Test
    fun `a sheet's pan and zoom read back as written`() {
        val state = NoteViewState.Ink(zoom = 1.75f, offsetX = -120.5f, offsetY = -3021.25f)
        assertEquals(state, NoteViewStateCodec.decode(NoteViewStateCodec.encode(state, 42L)))
    }

    @Test
    fun `a centred sheet keeps its NaN offset, which means centred rather than nowhere`() {
        val state = NoteViewStateCodec.decode(
            NoteViewStateCodec.encode(NoteViewState.Ink(0.4f, Float.NaN, -800f), 1L),
        ) as NoteViewState.Ink
        assert(state.offsetX.isNaN())
    }

    @Test
    fun `a text note's scroll reads back as written`() {
        val state = NoteViewState.Text(scroll = 5120)
        assertEquals(state, NoteViewStateCodec.decode(NoteViewStateCodec.encode(state, 7L)))
    }

    @Test
    fun `a device whose language writes decimal commas still produces a readable entry`() {
        // The comma is not the separator, but a locale-formatted float is exactly the kind of bug
        // that only ever shows up on someone else's phone.
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        try {
            val state = NoteViewState.Ink(1.5f, 2.5f, -3.5f)
            assertEquals(state, NoteViewStateCodec.decode(NoteViewStateCodec.encode(state, 0L)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `an entry that is damaged or from some future build reads as nothing rather than a guess`() {
        assertNull(NoteViewStateCodec.decode(""))
        assertNull(NoteViewStateCodec.decode("i;1;2"))
        assertNull(NoteViewStateCodec.decode("i;1;0;0;0"))
        assertNull(NoteViewStateCodec.decode("t;1;lots"))
        assertNull(NoteViewStateCodec.decode("x;1;2;3"))
    }

    @Test
    fun `past the cap the entries used longest ago are the ones dropped`() {
        val entries = mapOf(
            "a" to NoteViewStateCodec.encode(NoteViewState.Text(1), 30L),
            "b" to NoteViewStateCodec.encode(NoteViewState.Text(1), 10L),
            "c" to NoteViewStateCodec.encode(NoteViewState.Text(1), 20L),
        )
        assertEquals(listOf("b"), NoteViewStateCodec.overflow(entries, cap = 2))
        assertEquals(emptyList<String>(), NoteViewStateCodec.overflow(entries, cap = 3))
    }
}
