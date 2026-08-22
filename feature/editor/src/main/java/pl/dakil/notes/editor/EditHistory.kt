package pl.dakil.notes.editor

import pl.dakil.notes.model.Block
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.NoteMeta
import pl.dakil.notes.model.Sheet

/**
 * A reversible document edit.
 *
 * Each edit knows how to undo itself, rather than the history keeping whole document snapshots.
 * Snapshotting a sheet of dense handwriting on every stroke would cost megabytes and make undo
 * depth a memory decision; storing the inverse costs the size of what actually changed.
 */
sealed interface Edit {
    fun apply(note: Note): Note
    fun invert(): Edit

    /** Edits touching the same target within a short window merge, so typing undoes in words. */
    fun mergeWith(next: Edit): Edit? = null

    data class ReplaceBlock(val before: Block, val after: Block) : Edit {
        override fun apply(note: Note): Note = note.withSheet(note.sheet.withBlock(after))
        override fun invert(): Edit = ReplaceBlock(after, before)

        override fun mergeWith(next: Edit): Edit? =
            if (next is ReplaceBlock && next.before.id == after.id) {
                // Keep this edit's `before` so undoing rewinds to the start of the run.
                ReplaceBlock(before, next.after)
            } else {
                null
            }
    }

    data class AddBlock(val block: Block) : Edit {
        override fun apply(note: Note): Note = note.withSheet(note.sheet.withBlock(block))
        override fun invert(): Edit = RemoveBlock(block)
    }

    data class RemoveBlock(val block: Block) : Edit {
        override fun apply(note: Note): Note = note.withSheet(note.sheet.withoutBlock(block.id))
        override fun invert(): Edit = AddBlock(block)
    }

    /**
     * A whole-sheet swap, for edits that move many blocks at once.
     *
     * Duplicating or removing a page shifts every stroke below it, so there is no smaller unit to
     * record. Holding both sheets costs the stroke *lists* twice, not the strokes: they are shared
     * immutable objects, and only the ones that actually moved are new.
     */
    data class ReplaceSheet(val before: Sheet, val after: Sheet) : Edit {
        override fun apply(note: Note): Note = note.withSheet(after)
        override fun invert(): Edit = ReplaceSheet(after, before)
    }

    /** Adding or removing blank pages at the end of the sheet. */
    data class SetPages(val before: Int, val after: Int) : Edit {
        override fun apply(note: Note): Note = note.withSheet(note.sheet.withPages(after))
        override fun invert(): Edit = SetPages(after, before)

        // Tapping "add page" four times is one thought, and should be one undo.
        override fun mergeWith(next: Edit): Edit? =
            if (next is SetPages) SetPages(before, next.after) else null
    }

    /** Several edits that must undo together — a lasso delete spanning multiple layers. */
    data class Batch(val edits: List<Edit>) : Edit {
        override fun apply(note: Note): Note = edits.fold(note) { acc, edit -> edit.apply(acc) }
        override fun invert(): Edit = Batch(edits.reversed().map { it.invert() })
    }

    data class SetMeta(val before: NoteMeta, val after: NoteMeta) : Edit {
        override fun apply(note: Note): Note = note.copy(meta = after)
        override fun invert(): Edit = SetMeta(after, before)

        override fun mergeWith(next: Edit): Edit? =
            if (next is SetMeta) SetMeta(before, next.after) else null
    }
}

/**
 * Bounded undo/redo.
 *
 * Depth is capped rather than unbounded: a long session of handwriting would otherwise retain every
 * stroke ever erased. [MAX_DEPTH] is generous enough that no realistic session hits it.
 */
class EditHistory(private val maxDepth: Int = MAX_DEPTH) {

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var lastPushAtMs = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val depth: Int get() = undoStack.size

    fun push(edit: Edit, nowMs: Long = System.currentTimeMillis()) {
        redoStack.clear()

        val previous = undoStack.lastOrNull()
        if (previous != null && nowMs - lastPushAtMs <= MERGE_WINDOW_MS) {
            val merged = previous.mergeWith(edit)
            if (merged != null) {
                undoStack.removeLast()
                undoStack.addLast(merged)
                lastPushAtMs = nowMs
                return
            }
        }

        undoStack.addLast(edit)
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        lastPushAtMs = nowMs
    }

    fun undo(note: Note): Note? {
        val edit = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(edit)
        // Break the merge run so the next edit does not fold into one that has been undone.
        lastPushAtMs = 0L
        return edit.invert().apply(note)
    }

    fun redo(note: Note): Note? {
        val edit = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(edit)
        lastPushAtMs = 0L
        return edit.apply(note)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastPushAtMs = 0L
    }

    companion object {
        const val MAX_DEPTH = 200
        const val MERGE_WINDOW_MS = 900L
    }
}
