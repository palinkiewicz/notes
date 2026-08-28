package pl.dakil.notes.editor.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import pl.dakil.notes.ink.StrokeOutline
import pl.dakil.notes.ink.dragHandle
import pl.dakil.notes.ink.outlineInto
import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.ShapeSpec

/**
 * A selection being moved, scaled, rotated or reshaped, while the finger is still down.
 *
 * ### Why the document is not touched until pointer-up
 *
 * Transforming a selection means allocating a fresh [pl.dakil.notes.model.Stroke] for every stroke
 * in it — `Stroke` is immutable, so there is no other way — and `StrokeRenderer` caches built paths
 * by stroke *identity*. Driving that per input sample would rebuild every selected path at up to
 * 240 Hz and evict the cache the renderer exists to keep, which is the exact cost the ink pipeline
 * is written to avoid.
 *
 * So a drag keeps its whole effect here, as one [Affine], and the overlay draws the selected
 * strokes a second time underneath it — same instances, same cached paths, one canvas transform.
 * The document sees a single [pl.dakil.notes.editor.Edit] at pointer-up, which is also the undo
 * step the user expects: one drag, one undo.
 *
 * This is deliberately *not* the `beginTextBlockDrag`/`dragTextBlockTo` pattern, which republishes
 * the document every frame. That is affordable for one text box and is not for two hundred strokes.
 *
 * ### Why none of this is snapshot state
 *
 * Same rule as [ShapeController] and [RulerState]: everything a gesture changes per sample is a
 * plain field, and [version] — an `Int` read inside the overlay's draw lambda — is what makes the
 * canvas repaint. Nothing recomposes while a selection is being dragged.
 */
@Stable
class SelectionController {

    /** Bumped per input sample. Read inside a draw lambda to invalidate the draw phase alone. */
    var version by mutableIntStateOf(0)
        private set

    /** The transform the gesture has built so far. Identity whenever nothing is being dragged. */
    var live: Affine = Affine.IDENTITY
        private set

    /** The shape being reshaped by an apex grip, or null when this is an ordinary transform. */
    var liveShape: ShapeSpec? = null
        private set

    /** The live shape's centreline, valid while [liveShape] is set. */
    val outline = StrokeOutline()

    /** Which apex the grip is holding. Taken back from every drag — see [dragShape]. */
    private var handle = 0

    private var active = false

    /** Whether the gesture ever actually moved. A grip pressed and released commits nothing. */
    private var moved = false

    /** True while a gesture is in flight, so the overlay knows to draw the selection separately. */
    val isActive: Boolean get() = active

    /**
     * Whether the gesture in flight is the overlay's own drag-to-move rather than a chrome grip.
     *
     * The two share this controller, and only one of them can be running at a time — but they are
     * different Compose subtrees, so each has to be able to tell whose gesture it is looking at
     * before cancelling it. The ink overlay is told about a grip's drag as a cancelled stream, and
     * without this it would tear down a scale the user is still in the middle of.
     */
    var isMoving: Boolean = false
        private set

    private var anchorX = 0f
    private var anchorY = 0f

    // ---- Move, scale, rotate ---------------------------------------------------------------------

    /** Grab-and-drag from inside the selection, as the ink overlay routes it. */
    fun beginMove(x: Float, y: Float) {
        beginTransform()
        isMoving = true
        anchorX = x
        anchorY = y
    }

    /** Moves to a point in document coordinates, from wherever [beginMove] was grabbed. */
    fun moveTo(x: Float, y: Float) {
        if (!isMoving) return
        setLive(Affine.translate(x - anchorX, y - anchorY))
    }

    fun beginTransform() {
        active = true
        isMoving = false
        live = Affine.IDENTITY
        liveShape = null
        moved = false
        version++
    }

    /**
     * Replaces the gesture's transform outright rather than composing onto it.
     *
     * Every caller works out the whole transform from where the finger started to where it is now,
     * so composing per-frame deltas would only accumulate rounding — and, for a scale, re-multiply
     * every stroke width by another `sqrt(|det|)` on every sample.
     */
    fun setLive(matrix: Affine) {
        if (!active) return
        live = matrix
        version++
    }

    /** The transform to commit, or null when the gesture never moved anywhere. */
    fun endTransform(): Affine? {
        val out = if (active && !live.isIdentity) live else null
        cancel()
        return out
    }

    // ---- Apex editing ----------------------------------------------------------------------------

    fun beginShape(spec: ShapeSpec, handleIndex: Int) {
        active = true
        isMoving = false
        live = Affine.IDENTITY
        liveShape = spec
        moved = false
        handle = handleIndex
        spec.outlineInto(outline)
        version++
    }

    /** Drags the held apex to a point, in document coordinates. */
    fun dragShape(x: Float, y: Float) {
        val current = liveShape ?: return
        // Take the handle back from the drag as well as the shape. Pulled past the apex it pins, a
        // shape mirrors and the grip becomes a different corner; holding the old index would pin
        // the wrong one on the very next sample. Same reasoning as ShapeController.drag.
        val (next, movedHandle) = current.dragHandle(handle, x, y)
        handle = movedHandle
        if (next == current) return
        moved = true
        liveShape = next
        next.outlineInto(outline)
        version++
    }

    /** The reshaped spec to commit, or null when the grip never moved. */
    fun endShape(): ShapeSpec? {
        val out = if (moved) liveShape else null
        cancel()
        return out
    }

    fun cancel() {
        active = false
        isMoving = false
        moved = false
        live = Affine.IDENTITY
        liveShape = null
        version++
    }
}
