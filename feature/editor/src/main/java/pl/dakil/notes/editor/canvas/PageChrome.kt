package pl.dakil.notes.editor.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.ui.icons.NotesIcons
import kotlin.math.roundToInt

/**
 * The controls that belong to a page rather than to the document: its number, and the two things
 * you can do to it.
 *
 * ### Why this is not inside the sheet
 *
 * The sheet is drawn through a scale transform, and a note opens at roughly a quarter scale on a
 * phone. Anything laid out inside it would be scaled to match — a legible header at 1:1 becomes an
 * illegible one at fit-to-width, and a comfortable tap target becomes an impossible one. So the
 * chrome lives in the window's coordinate space at its natural size, and is *positioned* from the
 * transform instead of being transformed by it.
 *
 * ### Why it costs nothing to pan
 *
 * The transform is read in the placement block, not during composition. Panning therefore
 * invalidates layout for this one node and nothing recomposes — the same reason the ink canvas
 * reads its version counter inside the draw lambda.
 */
@Composable
fun PageChrome(
    format: PageFormat,
    pageCount: Int,
    paged: Boolean,
    ptToPx: Float,
    transform: SheetTransform,
    canEditPage: (Int) -> Boolean,
    canMoveUp: (Int) -> Boolean,
    canMoveDown: (Int) -> Boolean,
    onDuplicatePage: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onAddPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            for (page in 0 until pageCount) {
                PageHeader(
                    number = page + 1,
                    editable = canEditPage(page),
                    canMoveUp = canMoveUp(page),
                    canMoveDown = canMoveDown(page),
                    onDuplicate = { onDuplicatePage(page) },
                    onRemove = { onRemovePage(page) },
                    onMoveUp = { onMovePage(page, page - 1) },
                    onMoveDown = { onMovePage(page, page + 1) },
                    modifier = Modifier.layoutId(page),
                )
            }
            AddPageButton(onClick = onAddPage, modifier = Modifier.layoutId(ADD_BUTTON_ID))
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.layoutId to it.measure(loose) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            val zoom = transform.zoom
            val originX = transform.offsetX
            val originY = transform.offsetY
            val pageWidthPx = format.width * ptToPx * zoom
            val tops = SheetPainter.pageTops(format, pageCount, ptToPx, paged)
            val pageHeightPx = format.height * ptToPx * zoom

            for ((id, placeable) in placeables) {
                if (id == ADD_BUTTON_ID) {
                    // Below the last page, so it reads as "the document continues here" rather
                    // than as a permanent fixture of the editor.
                    val lastBottom = originY + tops.last() * zoom + pageHeightPx
                    placeable.place(
                        x = (originX + (pageWidthPx - placeable.width) / 2f).roundToInt(),
                        y = (lastBottom + GAP_PX).roundToInt(),
                    )
                } else {
                    val page = id as Int
                    // Sat just above its page, right-aligned with the paper's edge.
                    val top = originY + tops[page] * zoom
                    placeable.place(
                        x = (originX + pageWidthPx - placeable.width).roundToInt(),
                        y = (top - placeable.height - GAP_PX).roundToInt(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeader(
    number: Int,
    editable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Page $number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
            // Shown disabled rather than hidden on a page the text flows through: controls that
            // come and go as you type are harder to trust than ones that stay put.
            HeaderAction(NotesIcons.MoveUp, "Move page $number up", canMoveUp, onMoveUp)
            HeaderAction(NotesIcons.MoveDown, "Move page $number down", canMoveDown, onMoveDown)
            HeaderAction(NotesIcons.DuplicatePage, "Duplicate page $number", editable, onDuplicate)
            HeaderAction(NotesIcons.Delete, "Delete page $number", editable, onRemove)
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun AddPageButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = NotesIcons.AddPage,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("  Add page", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val ADD_BUTTON_ID = "add"

/** Breathing room between a control and the paper it belongs to, in window pixels. */
private const val GAP_PX = 8f
