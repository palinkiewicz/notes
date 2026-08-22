package pl.dakil.notes.editor.canvas

import pl.dakil.notes.ink.RulerEdge
import pl.dakil.notes.ink.RulerPose
import pl.dakil.notes.ink.RulerSide
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.ui.sheet.SheetPainter

/**
 * The edge a stroke starting at ([docX], [docY]) should be drawn against, in document points, or
 * null to leave it free-hand.
 *
 * ### Why there are two coordinate systems here
 *
 * The ruler is laid out in strip pixels, because that is what it is drawn in and what a finger
 * moving it works in. Ink is captured in document points, because that is what is written to the
 * file. The two differ by a scale, and — in paged view only — by the accumulated gaps between
 * sheets, which are presentation and must never reach the document.
 *
 * So the test happens in strip pixels and the result is converted once, at pen-down: the anchor is
 * the foot of the perpendicular from the pen, which is on the page the stroke is actually being
 * drawn on, and the direction needs no unwinding at all because both axes carry the same scale.
 * Converting per sample instead would drag a stroke sideways the moment it crossed a page break.
 */
internal fun rulerEdgeAt(
    pose: RulerPose,
    docX: Float,
    docY: Float,
    band: Float,
    ptToPx: Float,
    format: PageFormat,
    paged: Boolean,
    /**
     * How far outside the edge the line of ink sits, in document points — half the nib.
     *
     * A pen held against a straightedge touches the paper *beside* it, so the mark it leaves is
     * half a nib clear of the edge, not centred on it. Without this the ink would be laid down
     * underneath the slab, where the ruler's own edge covers it: the line the user is drawing
     * would not appear until they moved the ruler off it.
     */
    outward: Float = 0f,
): RulerEdge? {
    if (ptToPx <= 0f) return null
    val stripX = docX * ptToPx
    val stripY = SheetPainter.documentYToStripPx(docY, format, ptToPx, paged)

    val side = pose.snapSide(stripX, stripY, band) ?: return null
    val edge = pose.edge(side)
    // The normal points towards the lower edge, so it already points out of the slab on that side
    // and into it on the other.
    val sign = if (side == RulerSide.LOWER) 1f else -1f
    val shift = outward * ptToPx * sign
    val anchorX = edge.projectX(stripX, stripY) + pose.normalX * shift
    val anchorY = edge.projectY(stripX, stripY) + pose.normalY * shift

    return RulerEdge(
        x = anchorX / ptToPx,
        y = SheetPainter.stripPxToDocumentY(anchorY, format, ptToPx, paged),
        dx = edge.dx,
        dy = edge.dy,
    )
}

/**
 * How close to the edge a stroke has to start to take hold of it.
 *
 * A distance on the glass, divided back out of the zoom by the caller, for the same reason the
 * lasso's outline width is: what decides whether the user was aiming at the straightedge is where
 * their hand was, not how magnified the paper happened to be. About a nib's width — wide enough to
 * catch a deliberate approach, narrow enough that writing beside the ruler still writes.
 */
val RULER_SNAP_BAND = 9.dp
