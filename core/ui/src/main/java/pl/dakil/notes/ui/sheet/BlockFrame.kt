package pl.dakil.notes.ui.sheet

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import pl.dakil.notes.model.Frame

/**
 * Draws [content] in a whole block's own coordinates: its rect's top-left corner at the origin and
 * its sides in pixels, carried onto the page through the block's [frame].
 *
 * [originX], [originY] are where that corner is in this canvas's pixels. They are passed in rather
 * than worked out from the frame because each caller has its own idea of where the paper is — the
 * editor's strip has gaps between pages, a page's export starts at its own top.
 *
 * A matrix rather than a rotation, because a picture or a box can be stretched and mirrored as well
 * as turned, and a skew is something no rotate-then-scale can draw. The editor, the library card and
 * the export all go through here, so a block looks one way wherever it is drawn.
 */
inline fun DrawScope.inBlockFrame(
    frame: Frame,
    originX: Float,
    originY: Float,
    content: DrawScope.() -> Unit,
) {
    val m = Matrix()
    // Compose's Matrix is 4×4 and column-major; x' = v[0]·x + v[4]·y + v[12], y' = v[1]·x + v[5]·y + v[13].
    m.values[Matrix.ScaleX] = frame.a
    m.values[Matrix.SkewY] = frame.b
    m.values[Matrix.SkewX] = frame.c
    m.values[Matrix.ScaleY] = frame.d
    m.values[Matrix.TranslateX] = originX
    m.values[Matrix.TranslateY] = originY
    withTransform({ transform(m) }, content)
}
