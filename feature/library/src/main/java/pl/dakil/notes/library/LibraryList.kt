package pl.dakil.notes.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.model.RenderOrder
import pl.dakil.notes.ui.designsystem.SelectionBadge
import pl.dakil.notes.ui.designsystem.ShapedIcon
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.theme.SegmentedShapes
import java.text.DateFormat
import java.util.Date

/**
 * The dense layout: one row each, the rows of a folder joined into one rounded column.
 *
 * An ink note shows a thumbnail where the others show an icon. The icon told the user only what
 * kind of thing the row was, which the shape of the row already implies; the thumbnail tells them
 * which note it is, which is what they came to the list to find out.
 */
@Composable
fun LibraryList(
    items: List<LibraryItem>,
    selection: Set<StoreRef>,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    renderOrder: RenderOrder,
    dateFormat: DateFormat,
    showPath: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = contentPadding) {
        if (header != null) item(key = HEADER_KEY, contentType = HEADER_KEY) { header() }
        itemsIndexed(items, key = { _, item -> item.ref.value }, contentType = { _, item -> item::class }) { index, item ->
            Column(Modifier.animateItem().padding(horizontal = 12.dp)) {
                if (index > 0) Spacer(Modifier.height(SegmentedShapes.gap))
                LibraryRow(
                    item = item,
                    index = index,
                    count = items.size,
                    selected = item.ref in selection,
                    handlers = handlers,
                    loadPreview = loadPreview,
                    renderOrder = renderOrder,
                    dateFormat = dateFormat,
                    showPath = showPath,
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    index: Int,
    count: Int,
    selected: Boolean,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    renderOrder: RenderOrder,
    dateFormat: DateFormat,
    showPath: Boolean,
) {
    val haptics = rememberHaptics()
    SegmentedListItem(
        selected = selected,
        onClick = { handlers.click(item) },
        onLongClick = handlers.longClick(item, haptics),
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        supportingContent = {
            Column {
                val snippet = (item as? LibraryItem.Note)?.summary?.snippet.orEmpty()
                if (snippet.isNotBlank()) {
                    Text(snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = subtitle(
                        item = item,
                        dateFormat = dateFormat,
                        showPath = showPath,
                        rootFolderName = stringResource(R.string.library_root_folder),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            Box(Modifier.width(THUMBNAIL_WIDTH), Alignment.Center) {
                if (item is LibraryItem.Note && item.summary.kind == NoteKind.INK) {
                    NotePreviewBox(
                        note = item,
                        load = loadPreview,
                        order = renderOrder,
                        modifier = Modifier
                            .width(THUMBNAIL_WIDTH)
                            .height(THUMBNAIL_HEIGHT)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    ShapedIcon(
                        icon = item.icon,
                        polygon = if (item is LibraryItem.Folder) MaterialShapes.Cookie4Sided else MaterialShapes.Square,
                        size = 40.dp,
                        iconSize = 22.dp,
                        containerColor = if (item is LibraryItem.Folder) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (item is LibraryItem.Folder) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        contentDescription = item.contentDescription(),
                    )
                }
            }
        },
        trailingContent = if (selected) {
            { SelectionBadge(selected = true, Modifier.size(28.dp)) }
        } else {
            null
        },
    ) {
        Text(item.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The line under the snippet: when it was last touched, and — in search results — where it lives.
 *
 * The folder only appears when searching, because that is the only time a row on screen might not
 * be in the folder the breadcrumbs say it is.
 */
private fun subtitle(
    item: LibraryItem,
    dateFormat: DateFormat,
    showPath: Boolean,
    rootFolderName: String,
): String {
    val date = dateFormat.format(Date(item.modifiedAt))
    val tags = (item as? LibraryItem.Note)?.summary?.tags.orEmpty()
    return buildString {
        if (showPath) append(item.path.ifEmpty { rootFolderName }).append("  ·  ")
        append(date)
        if (tags.isNotEmpty()) append("  ·  ").append(tags.joinToString(", "))
    }
}

/** Sized so the thumbnail sits in the leading slot at a page's aspect ratio without widening the row. */
private val THUMBNAIL_WIDTH = 40.dp
private val THUMBNAIL_HEIGHT = 52.dp
