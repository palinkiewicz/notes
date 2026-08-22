package pl.dakil.notes.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind
import java.text.DateFormat
import java.util.Date

/**
 * The dense layout: one row each.
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
    dateFormat: DateFormat,
    showPath: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(items, key = { it.ref.value }) { item ->
            LibraryRow(
                item = item,
                selected = item.ref in selection,
                handlers = handlers,
                loadPreview = loadPreview,
                dateFormat = dateFormat,
                showPath = showPath,
            )
        }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    selected: Boolean,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
    showPath: Boolean,
) {
    ListItem(
        headlineContent = {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                val snippet = (item as? LibraryItem.Note)?.summary?.snippet.orEmpty()
                if (snippet.isNotBlank()) {
                    Text(snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = subtitle(item, dateFormat, showPath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            if (item is LibraryItem.Note && item.summary.kind == NoteKind.INK) {
                NotePreviewBox(
                    note = item,
                    load = loadPreview,
                    modifier = Modifier
                        .width(THUMBNAIL_WIDTH)
                        .height(THUMBNAIL_HEIGHT)
                        .clip(RoundedCornerShape(4.dp)),
                )
            } else {
                Box(Modifier.width(THUMBNAIL_WIDTH), androidx.compose.ui.Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.libraryItem(item, handlers),
    )
}

/**
 * The line under the snippet: when it was last touched, and — in search results — where it lives.
 *
 * The folder only appears when searching, because that is the only time a row on screen might not
 * be in the folder the breadcrumbs say it is.
 */
private fun subtitle(item: LibraryItem, dateFormat: DateFormat, showPath: Boolean): String {
    val date = dateFormat.format(Date(item.modifiedAt))
    val tags = (item as? LibraryItem.Note)?.summary?.tags.orEmpty()
    return buildString {
        if (showPath) append(item.path.ifEmpty { ROOT_FOLDER_NAME }).append("  ·  ")
        append(date)
        if (tags.isNotEmpty()) append("  ·  ").append(tags.joinToString(", "))
    }
}

/** Sized so the thumbnail sits in the leading slot at a page's aspect ratio without widening the row. */
private val THUMBNAIL_WIDTH = 40.dp
private val THUMBNAIL_HEIGHT = 52.dp
