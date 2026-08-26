package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.format.DakNote
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.ToolId

class EditHistoryTest {

    private fun note(): Note = DakNote.newNote(title = "Test", now = 0L)

    private fun Note.ink(): InkBlock = sheet.inkLayers().single()

    private fun stroke(x: Float) = Stroke(
        ToolId.PEN, -1, 2f, BlendId.NORMAL, floatArrayOf(x, x + 10f), floatArrayOf(0f, 10f),
    )

    private fun Note.box(): TextBlock = sheet.textBlocks().single()

    /** A note with one empty text box on it, which is what typing edits. */
    private fun noteWithBox(): Note {
        val base = note()
        val box = TextBlock(
            id = base.sheet.nextBlockId(), z = 1, rect = Rect(50f, 50f, 400f, 80f),
        )
        return base.withSheet(base.sheet.withBlock(box))
    }

    private fun typed(note: Note, text: String): Edit =
        Edit.ReplaceBlock(note.box(), note.box().copy(markdown = text))

    @Test
    fun `an edit and its inverse round-trip the document`() {
        val original = note()
        val edit = Edit.ReplaceBlock(original.ink(), original.ink().copy(strokes = listOf(stroke(0f))))

        val applied = edit.apply(original)
        assertEquals(1, applied.ink().strokes.size)
        assertEquals(0, edit.invert().apply(applied).ink().strokes.size)
    }

    @Test
    fun `undo and redo walk the stack`() {
        val history = EditHistory()
        var current = note()

        val first = Edit.ReplaceBlock(current.ink(), current.ink().copy(strokes = listOf(stroke(0f))))
        current = first.apply(current)
        history.push(first, nowMs = 0)

        val second = Edit.ReplaceBlock(
            current.ink(), current.ink().copy(strokes = current.ink().strokes + stroke(50f)),
        )
        current = second.apply(current)
        history.push(second, nowMs = 10_000)

        assertEquals(2, current.ink().strokes.size)

        current = history.undo(current)!!
        assertEquals(1, current.ink().strokes.size)
        current = history.undo(current)!!
        assertEquals(0, current.ink().strokes.size)
        assertFalse(history.canUndo)
        assertNull(history.undo(current))

        current = history.redo(current)!!
        assertEquals(1, current.ink().strokes.size)
        current = history.redo(current)!!
        assertEquals(2, current.ink().strokes.size)
        assertFalse(history.canRedo)
    }

    @Test
    fun `a new edit clears the redo stack`() {
        val history = EditHistory()
        var current = note()

        val edit = Edit.ReplaceBlock(current.ink(), current.ink().copy(strokes = listOf(stroke(0f))))
        current = edit.apply(current)
        history.push(edit, nowMs = 0)
        current = history.undo(current)!!
        assertTrue(history.canRedo)

        history.push(
            Edit.ReplaceBlock(current.ink(), current.ink().copy(strokes = listOf(stroke(99f)))),
            nowMs = 20_000,
        )
        assertFalse(history.canRedo)
    }

    @Test
    fun `rapid text edits merge into one undo step`() {
        // This is what makes undo step back through words rather than characters while typing.
        val history = EditHistory()
        var current = noteWithBox()

        for ((i, word) in listOf("a", "ab", "abc").withIndex()) {
            val edit = typed(current, word)
            current = edit.apply(current)
            history.push(edit, nowMs = i * 100L)
        }

        assertEquals(1, history.depth)
        assertEquals("", history.undo(current)!!.box().markdown)
    }

    @Test
    fun `strokes drawn in quick succession each undo on their own`() {
        // Writing "i's" is four strokes made in well under the merge window, and taking them back
        // has to take back one mark at a time — a timer is not what separates one pen mark from
        // the next the way a pause separates one typed word from the next.
        val history = EditHistory()
        var current = note()

        repeat(4) { i ->
            val edit = Edit.ReplaceBlock(
                current.ink(),
                current.ink().copy(strokes = current.ink().strokes + stroke(i * 10f)),
                mergeable = false,
            )
            current = edit.apply(current)
            history.push(edit, nowMs = i * 50L)
        }

        assertEquals(4, history.depth)
        for (remaining in 3 downTo 0) {
            current = history.undo(current)!!
            assertEquals(remaining, current.ink().strokes.size)
        }
    }

    @Test
    fun `text edits separated by a pause stay distinct`() {
        val history = EditHistory()
        var current = noteWithBox()

        for ((i, word) in listOf("a", "ab").withIndex()) {
            val edit = typed(current, word)
            current = edit.apply(current)
            history.push(edit, nowMs = i * 5_000L)
        }

        assertEquals(2, history.depth)
        assertEquals("a", history.undo(current)!!.box().markdown)
    }

    @Test
    fun `typing in one box never merges with typing in another`() {
        // The merge rule is per block, and it has to be: two boxes are two different things to say,
        // and undoing back through one must not quietly rewind the other.
        val history = EditHistory()
        val base = noteWithBox()
        val one = base.box()
        val second = TextBlock(id = BlockId("bx"), z = 2, rect = Rect(50f, 200f, 400f, 230f))
        var current = base.withSheet(base.sheet.withBlock(second))

        val first = Edit.ReplaceBlock(one, one.copy(markdown = "one"))
        current = first.apply(current)
        history.push(first, nowMs = 0)

        val other = Edit.ReplaceBlock(second, second.copy(markdown = "two"))
        current = other.apply(current)
        history.push(other, nowMs = 100)

        assertEquals(2, history.depth)
    }

    @Test
    fun `history depth is bounded`() {
        // A long handwriting session would otherwise retain every stroke ever erased.
        val history = EditHistory(maxDepth = 5)
        var current = note()
        repeat(20) { i ->
            val edit = Edit.ReplaceBlock(
                current.ink(), current.ink().copy(strokes = current.ink().strokes + stroke(i * 10f)),
            )
            current = edit.apply(current)
            history.push(edit, nowMs = i * 5_000L)
        }
        assertEquals(5, history.depth)
    }

    @Test
    fun `a batch of text and ink undoes as a single step`() {
        // The unified sheet makes this the normal case: one gesture can touch both layers.
        val history = EditHistory()
        var current = noteWithBox()

        val batch = Edit.Batch(
            listOf(
                Edit.ReplaceBlock(current.ink(), current.ink().copy(strokes = listOf(stroke(0f)))),
                Edit.ReplaceBlock(current.box(), current.box().copy(markdown = "hi")),
            )
        )
        current = batch.apply(current)
        history.push(batch, nowMs = 0)

        assertEquals(1, current.ink().strokes.size)
        assertEquals("hi", current.box().markdown)

        current = history.undo(current)!!
        assertEquals(0, current.ink().strokes.size)
        assertEquals("", current.box().markdown)
    }

    @Test
    fun `adding a page undoes back to the previous count`() {
        val history = EditHistory()
        var current = note()

        val edit = Edit.SetPages(current.sheet.pages, current.sheet.pageCount() + 1)
        current = edit.apply(current)
        history.push(edit, nowMs = 0)

        assertEquals(2, current.sheet.pageCount())
        assertEquals(1, history.undo(current)!!.sheet.pageCount())
    }

    @Test
    fun `a run of page additions collapses into one undo`() {
        // Four taps on "add page" is one thought, and stepping back through it one page at a time
        // would be tedious rather than precise.
        val history = EditHistory()
        var current = note()

        repeat(4) { i ->
            val edit = Edit.SetPages(current.sheet.pages, current.sheet.pageCount() + 1)
            current = edit.apply(current)
            history.push(edit, nowMs = i * 100L)
        }

        assertEquals(5, current.sheet.pageCount())
        assertEquals(1, history.depth)
        assertEquals(1, history.undo(current)!!.sheet.pageCount())
    }

    @Test
    fun `adding and removing an ink layer invert cleanly`() {
        val current = note()
        val layer = InkBlock(id = current.sheet.nextBlockId(), z = 5, rect = Rect.ZERO)
        val edit = Edit.AddBlock(layer)

        val added = edit.apply(current)
        assertEquals(2, added.sheet.inkLayers().size)
        assertEquals(1, edit.invert().apply(added).sheet.inkLayers().size)
    }
}
