package pl.dakil.notes.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import pl.dakil.notes.ui.theme.SegmentedShapes

/**
 * Segmented list items as visible tonal tiles on the app's surface. A disabled tile keeps its tile,
 * greying only what is on it, or it would leave a hole in the middle of its group.
 */
@Composable
fun segmentedItemColors(): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

/**
 * Tiles whose state is shown by a control inside them (a switch): checking one must not also
 * recolour the whole tile, or a screen of enabled settings turns into a wall of accent.
 */
@Composable
fun checkableItemColors(): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
    selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * The shapes of a tile inside a [SegmentedGroup]: small corners everywhere, and the group's clip
 * turns the outer corners of its first and last tiles into the big rounded ones. A pressed tile
 * still rounds; a checked one keeps its shape, since its switch already shows the state.
 */
@Composable
fun segmentedInnerShapes(): ListItemShapes = ListItemDefaults.segmentedShapes(index = 1, count = 3).let {
    it.copy(selectedShape = it.shape)
}

/**
 * A group of segmented tiles reading as one rounded card: children touch with a hairline gap, and
 * the clip rounds the group's outer corners whatever (and however many) rows end up inside.
 */
@Composable
fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().clip(SegmentedShapes.group),
        verticalArrangement = Arrangement.spacedBy(SegmentedShapes.gap),
        content = content,
    )
}

/**
 * The shape of one tile in a lazy list drawn as a segmented group: the first tile gets the group's
 * big top corners, the last its big bottom corners, the rest stay small — the lazy counterpart of
 * [SegmentedGroup].
 */
fun segmentedItemShape(index: Int, count: Int): Shape = SegmentedShapes.of(index, count)

/** Toggle buttons that stay visible on container-coloured backgrounds (cards, scrolled app bars). */
@Composable
fun raisedToggleColors(): ToggleButtonColors =
    ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)

/** A rounded tonal card shape for content that is not a list. */
val CardShape = RoundedCornerShape(SegmentedShapes.outer)
