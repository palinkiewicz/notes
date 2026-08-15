package pl.dakil.notes.editor.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Lays out Markdown blocks down the sheet, avoiding page breaks through a block.
 *
 * A block that would straddle a page boundary is pushed whole onto the next page, the way a word
 * processor does — otherwise printing would slice headings and list items in half, and the "still
 * logically split into pages" promise would be cosmetic only. A block taller than a page is left
 * where it is rather than pushed forever.
 *
 * Reports the laid-out height back through [onHeightMeasured] so the sheet knows how many pages it
 * occupies. That number cannot be known before layout, because only the text engine knows how tall
 * the text turned out.
 */
@Composable
fun PaginatedFlow(
    pageHeightPx: Float,
    topMarginPx: Float,
    bottomMarginPx: Float,
    /** Extra offset added at each page boundary, for the gap between sheets in paged view. */
    pageGapPx: Float,
    /** Floor on the page count, so ink drawn past the end of the text still has paper under it. */
    minPageCount: Int,
    onHeightMeasured: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val childConstraints = Constraints(maxWidth = width, minWidth = 0)
        val placeables = measurables.map { it.measure(childConstraints) }

        val usable = (pageHeightPx - topMarginPx - bottomMarginPx).coerceAtLeast(1f)
        var y = topMarginPx
        val positions = FloatArray(placeables.size)

        placeables.forEachIndexed { i, placeable ->
            val height = placeable.height.toFloat()
            val page = floor((y - topMarginPx) / pageHeightPx).coerceAtLeast(0f)
            val pageBottom = page * pageHeightPx + pageHeightPx - bottomMarginPx

            // Push a block that would straddle the boundary onto the next page — unless it is
            // taller than a page, in which case there is nowhere better for it to go.
            if (y + height > pageBottom && height <= usable) {
                y = (page + 1f) * pageHeightPx + topMarginPx
            }
            positions[i] = y
            y += height
        }

        val contentHeight = y + bottomMarginPx
        onHeightMeasured(contentHeight)

        // The container is the full strip so the ink overlay above it lines up exactly.
        val pageCount = maxOf(minPageCount, ceil(contentHeight / pageHeightPx - 1e-4f).toInt())
        val stripHeight = (pageCount * pageHeightPx + (pageCount - 1) * pageGapPx).roundToInt()

        layout(width, stripHeight) {
            placeables.forEachIndexed { i, placeable ->
                val documentY = positions[i]
                val page = floor(documentY / pageHeightPx).toInt().coerceAtLeast(0)
                placeable.place(0, (documentY + page * pageGapPx).roundToInt())
            }
        }
    }
}
