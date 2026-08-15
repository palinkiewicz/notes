package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.format.DakNote
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.ToolId

class EditHistoryTest {

    private fun note(): Note = DakNote.newNote(title = "Test", now = 0L)

    private fun Note.ink(): InkBlock = sheet.inkLayers().single()

    private fun stroke(x: Float) = Stroke(
        ToolId.PEN, -1, 2f, BlendId.NORMAL, floatArrayOf(x, x + 10f), floatArrayOf(0f, 10f),
    )

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
        var current = note()

        for ((i, word) in listOf("a", "ab", "abc").withIndex()) {
            val edit = Edit.SetText(current.sheet.markdown, word)
            current = edit.apply(current)
            history.push(edit, nowMs = i * 100L)
        }

        assertEquals(1, history.depth)
        assertEquals("", history.undo(current)!!.sheet.markdown)
    }

    @Test
    fun `text edits separated by a pause stay distinct`() {
        val history = EditHistory()
        var current = note()

        for ((i, word) in listOf("a", "ab").withIndex()) {
            val edit = Edit.SetText(current.sheet.markdown, word)
            current = edit.apply(current)
            history.push(edit, nowMs = i * 5_000L)
        }

        assertEquals(2, history.depth)
        assertEquals("a", history.undo(current)!!.sheet.markdown)
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
        var current = note()

        val batch = Edit.Batch(
            listOf(
                Edit.ReplaceBlock(current.ink(), current.ink().copy(strokes = listOf(stroke(0f)))),
                Edit.SetText("", "hi"),
            )
        )
        current = batch.apply(current)
        history.push(batch, nowMs = 0)

        assertEquals(1, current.ink().strokes.size)
        assertEquals("hi", current.sheet.markdown)

        current = history.undo(current)!!
        assertEquals(0, current.ink().strokes.size)
        assertEquals("", current.sheet.markdown)
    }

    @Test
    fun `adding and removing an ink layer invert cleanly`() {
        val current = note()
        val layer = InkBlock(id = current.sheet.nextBlockId(), z = 5, rect = pl.dakil.notes.model.Rect.ZERO)
        val edit = Edit.AddBlock(layer)

        val added = edit.apply(current)
        assertEquals(2, added.sheet.inkLayers().size)
        assertEquals(1, edit.invert().apply(added).sheet.inkLayers().size)
    }
}

class MarkdownActionsTest {

    @Test
    fun `wrapping a selection adds markers around it`() {
        val result = MarkdownActions.toggleWrap("hello world", 6, 11, "**")
        assertEquals("hello **world**", result.text)
        assertEquals("world", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `wrapping twice unwraps`() {
        // A formatting button that only ever adds markers becomes a trap on the second press.
        val once = MarkdownActions.toggleWrap("hello world", 6, 11, "**")
        val twice = MarkdownActions.toggleWrap(once.text, once.selectionStart, once.selectionEnd, "**")
        assertEquals("hello world", twice.text)
    }

    @Test
    fun `wrapping an empty selection leaves the caret between the markers`() {
        val result = MarkdownActions.toggleWrap("ab", 1, 1, "**")
        assertEquals("a****b", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(3, result.selectionEnd)
    }

    @Test
    fun `a prefix applies across every line the selection spans`() {
        val result = MarkdownActions.togglePrefix("one\ntwo\nthree", 0, 9, "> ")
        assertEquals("> one\n> two\n> three", result.text)
    }

    @Test
    fun `an already-prefixed block has its prefix removed`() {
        val result = MarkdownActions.togglePrefix("> one\n> two", 0, 8, "> ")
        assertEquals("one\ntwo", result.text)
    }

    @Test
    fun `a code fence leaves the caret on the empty line inside`() {
        val result = MarkdownActions.insertCodeFence("text\n", 5)
        assertTrue(result.text.contains("```\n\n```"))
        assertEquals('\n', result.text[result.selectionStart - 1])
    }

    @Test
    fun `a link puts the caret inside the parentheses`() {
        val result = MarkdownActions.insertLink("see docs", 4, 8)
        assertEquals("see [docs]()", result.text)
        assertEquals("see [docs](".length, result.selectionStart)
    }

    @Test
    fun `list markers are inserted at the start of the line`() {
        val result = MarkdownActions.insertTaskItem("first\nsecond", 8)
        assertEquals("first\n- [ ] second", result.text)
    }

    @Test
    fun `out of range offsets are clamped rather than crashing`() {
        val result = MarkdownActions.toggleWrap("abc", -5, 99, "*")
        assertEquals("*abc*", result.text)
    }
}
