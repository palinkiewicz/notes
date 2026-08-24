package pl.dakil.notes.model

/**
 * A centreline that carries a drawn width at every point.
 *
 * The tessellator's whole input, and the only thing a committed [Stroke] and the wet stroke still
 * under the pen have in common. Having both speak this interface is what lets the ink being drawn
 * be rendered by exactly the same code as the ink already on the page — which is the only way the
 * two can be guaranteed to look alike, and the reason the width a pen is pressing out is visible
 * while the stroke is being made rather than only once it lands.
 */
interface WidthedPath {
    val pointCount: Int
    fun pointX(i: Int): Float
    fun pointY(i: Int): Float
    /** Drawn width at point [i], in points. */
    fun pointWidth(i: Int): Float
}
