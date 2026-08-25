package pl.dakil.notes.ui.export

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Paints Compose [DrawScope] drawing code into a plain `android.graphics.Canvas` — a
 * `PdfDocument.Page`'s canvas or a `Bitmap`'s — outside any composition.
 *
 * This is what lets export reuse `SheetPainter`/`StrokeRenderer`, written against `DrawScope`,
 * rather than duplicating that drawing logic in raw `android.graphics` calls. `CanvasDrawScope` and
 * `androidx.compose.ui.graphics.Canvas` are stable public Compose API usable with no `Composer` and
 * no running composition — they exist for exactly this kind of "draw once, off the UI tree" use.
 */
fun drawIntoCanvas(
    canvas: android.graphics.Canvas,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    block: DrawScope.() -> Unit,
) {
    val composeCanvas = Canvas(canvas)
    CanvasDrawScope().draw(
        density,
        LayoutDirection.Ltr,
        composeCanvas,
        Size(widthPx.toFloat(), heightPx.toFloat()),
        block,
    )
}
