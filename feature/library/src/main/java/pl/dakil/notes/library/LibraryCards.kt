package pl.dakil.notes.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.ui.icons.NotesIcons
import java.text.DateFormat
import java.util.Date

/**
 * The card layout.
 *
 * A staggered grid rather than a uniform one: a text note with four lines and one with none should
 * not both be given the height of the taller, and an ink note's preview makes it taller than either.
 * Cards keep their own height and the columns fill independently, which is what stops the grid
 * being mostly whitespace.
 */
@Composable
fun LibraryCards(
    items: List<LibraryItem>,
    selection: Set<StoreRef>,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
    showPath: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyVerticalStaggeredGrid(
        // Adaptive rather than a fixed count, so a tablet and an unfolded foldable get more columns
        // without the layout having to be told about them.
        columns = StaggeredGridCells.Adaptive(CARD_MIN_WIDTH),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        items(items, key = { it.ref.value }) { item ->
            LibraryCard(
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
private fun LibraryCard(
    item: LibraryItem,
    selected: Boolean,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
    showPath: Boolean,
) {
    // The filled card, which is the one M3 variant whose container actually stands off the screen's
    // own surface. The elevated variant looks right on paper but draws itself in `surfaceContainerLow`
    // — barely a shade from the background under a light dynamic theme — so the cards read as text
    // on a page rather than as objects on it. A little elevation on top of that gives the shadow
    // that makes them float.
    Card(
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .libraryItem(item, handlers),
    ) {
        when (item) {
            is LibraryItem.Folder -> FolderCardBody(item)
            is LibraryItem.Note -> NoteCardBody(item, loadPreview, dateFormat, showPath)
        }
    }
}

/**
 * A folder card is deliberately short.
 *
 * There is nothing inside a folder to show a picture of, and giving it the height of a note card
 * would fill the top of every grid with empty rectangles.
 */
@Composable
private fun FolderCardBody(folder: LibraryItem.Folder) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(NotesIcons.Folder, contentDescription = "Folder", modifier = Modifier.size(22.dp))
        Text(
            text = folder.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteCardBody(
    note: LibraryItem.Note,
    loadPreview: PreviewLoader,
    dateFormat: DateFormat,
    showPath: Boolean,
) {
    val ink = note.summary.kind == NoteKind.INK
    if (ink) {
        // Roughly three fifths of a card once the text below it is counted, which is enough of the
        // page to recognise it by and little enough that the title is still on screen.
        NotePreviewBox(
            note = note,
            load = loadPreview,
            modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_ASPECT),
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = note.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val snippet = note.summary.snippet
        if (snippet.isNotBlank()) {
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // An ink note has already said what it is with its picture; the words are a hint.
                maxLines = if (ink) 1 else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (showPath) {
                note.path.ifEmpty { ROOT_FOLDER_NAME } + "  ·  " + dateFormat.format(Date(note.modifiedAt))
            } else {
                dateFormat.format(Date(note.modifiedAt))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two columns on a phone, more on anything wider. */
private val CARD_MIN_WIDTH = 160.dp

/** Page-ish, cropped: tall enough to show a few lines of writing, short enough to leave room below. */
private const val PREVIEW_ASPECT = 1.25f
