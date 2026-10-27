package pl.dakil.notes.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.RenderLayer
import pl.dakil.notes.model.RenderOrder
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

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
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_drawing_order)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { settings.setRenderOrder(RenderOrder.DEFAULT) },
                        enabled = !order.isDefault,
                    ) {
                        Text(stringResource(R.string.settings_drawing_order_reset))
                    }
                },
                colors = flatTopAppBarColors(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item(key = "description") {
                Text(
                    text = stringResource(R.string.settings_drawing_order_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }
            items(rows, key = { it.layer.key }) { row ->
                LayerRow(
                    row = row,
                    canRaise = order.canRaise(row.layer),
                    canLower = order.canLower(row.layer),
                    onRaise = { settings.setRenderOrder(order.raise(row.layer)) },
                    onLower = { settings.setRenderOrder(order.lower(row.layer)) },
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
    Column(modifier.padding(top = if (row.isFirst) 8.dp else 2.dp)) {
        ListItem(
            headlineContent = { Text(name) },
            overlineContent = if (row.isFirst && row.box.size > 1) {
                { Text(stringResource(R.string.settings_drawing_order_mixed)) }
            } else {
                null
            },
            leadingContent = { Icon(row.layer.icon(), contentDescription = null) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRaise, enabled = canRaise) {
                        Icon(
                            NotesIcons.MoveUp,
                            contentDescription = stringResource(R.string.settings_drawing_order_raise, name),
                        )
                    }
                    IconButton(onClick = onLower, enabled = canLower) {
                        Icon(
                            NotesIcons.MoveDown,
                            contentDescription = stringResource(R.string.settings_drawing_order_lower, name),
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(row.shape())
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}

private fun Slot.shape(): Shape {
    val outer = 20.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (isFirst) outer else inner,
        topEnd = if (isFirst) outer else inner,
        bottomStart = if (isLast) outer else inner,
        bottomEnd = if (isLast) outer else inner,
    )
}

private fun RenderLayer.icon(): ImageVector = when (this) {
    RenderLayer.IMAGES -> NotesIcons.Image
    RenderLayer.TEXT -> NotesIcons.TextBox
    RenderLayer.HIGHLIGHTER -> NotesIcons.Highlighter
    RenderLayer.PEN -> NotesIcons.Pen
    RenderLayer.FOUNTAIN_PEN -> NotesIcons.FountainPen
    RenderLayer.PENCIL -> NotesIcons.Pencil
    RenderLayer.MARKER -> NotesIcons.Marker
}
