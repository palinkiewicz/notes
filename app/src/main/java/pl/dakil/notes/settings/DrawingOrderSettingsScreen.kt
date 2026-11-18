package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.RenderLayer
import pl.dakil.notes.model.RenderOrder
import pl.dakil.notes.ui.designsystem.BackButton
import pl.dakil.notes.ui.designsystem.CollapsingScaffold
import pl.dakil.notes.ui.designsystem.ShapedIcon
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.designsystem.segmentedItemColors
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.SegmentedShapes
import pl.dakil.notes.ui.theme.Spacing

/**
 * What a sheet paints on top of what.
 *
 * The list reads the way a stack of paper does — the top of the list is the top of the pile — and
 * each box in it is one layer. A box holding several tools mixes them by when they were drawn,
 * which is the one idea grouping needs, so it is said once at the top and once on every such box.
 *
 * There is only one control, the pair of arrows. A step is the smallest visible change: a tool
 * steps into the box beside it, or out of the box it is in (see [RenderOrder.raise]), so grouping
 * and separating are things the arrows already do rather than a second mechanism to discover.
 */
@Composable
fun DrawingOrderSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
) {
    val order = current.renderOrder
    // Top of the pile first, rows and all, so the arrows point the way the layer moves.
    val rows = order.levels.asReversed().flatMap { level ->
        val members = level.asReversed()
        members.mapIndexed { index, layer -> Slot(layer, members, index) }
    }

    val haptics = rememberHaptics()

    CollapsingScaffold(
        title = stringResource(R.string.settings_drawing_order),
        navigationIcon = { BackButton(onNavigateBack) },
        actions = {
            TextButton(
                onClick = {
                    haptics.confirm()
                    settings.setRenderOrder(RenderOrder.DEFAULT)
                },
                enabled = !order.isDefault,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.settings_drawing_order_reset))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xxl),
        ) {
            item(key = "description") {
                Text(
                    text = stringResource(R.string.settings_drawing_order_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            items(rows, key = { it.layer.key }) { row ->
                LayerRow(
                    row = row,
                    canRaise = order.canRaise(row.layer),
                    canLower = order.canLower(row.layer),
                    onRaise = {
                        haptics.tick()
                        settings.setRenderOrder(order.raise(row.layer))
                    },
                    onLower = {
                        haptics.tick()
                        settings.setRenderOrder(order.lower(row.layer))
                    },
                    // Keyed by the layer, so a move slides the row rather than redrawing the list.
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** One tool in the list, and where it sits in its box: [index] counts down from the box's top. */
private class Slot(val layer: RenderLayer, val box: List<RenderLayer>, val index: Int) {
    val isFirst get() = index == 0
    val isLast get() = index == box.lastIndex
}

@Composable
private fun LayerRow(
    row: Slot,
    canRaise: Boolean,
    canLower: Boolean,
    onRaise: () -> Unit,
    onLower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = row.layer.label()
    // Rows of one box are joined into a single rounded card, and the cards of different boxes stand
    // apart — the gap between them *is* the boundary between two layers.
    Column(modifier.padding(top = if (row.isFirst) Spacing.s else SegmentedShapes.gap)) {
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = row.index, count = row.box.size),
            colors = segmentedItemColors(),
            overlineContent = if (row.isFirst && row.box.size > 1) {
                { Text(stringResource(R.string.settings_drawing_order_mixed)) }
            } else {
                null
            },
            leadingContent = { ShapedIcon(row.layer.icon(), polygon = MaterialShapes.Cookie6Sided) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRaise, enabled = canRaise, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            NotesIcons.MoveUp,
                            contentDescription = stringResource(R.string.settings_drawing_order_raise, name),
                        )
                    }
                    IconButton(onClick = onLower, enabled = canLower, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            NotesIcons.MoveDown,
                            contentDescription = stringResource(R.string.settings_drawing_order_lower, name),
                        )
                    }
                }
            },
        ) { Text(name) }
    }
}

@Composable
private fun RenderLayer.icon(): ImageVector = when (this) {
    RenderLayer.IMAGES -> NotesIcons.Image
    RenderLayer.TEXT -> NotesIcons.TextBox
    RenderLayer.HIGHLIGHTER -> NotesIcons.Highlighter
    RenderLayer.PEN -> NotesIcons.Pen
    RenderLayer.FOUNTAIN_PEN -> NotesIcons.FountainPen
    RenderLayer.PENCIL -> NotesIcons.Pencil
    RenderLayer.MARKER -> NotesIcons.Marker
}
