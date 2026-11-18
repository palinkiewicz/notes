package pl.dakil.notes.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import pl.dakil.notes.ui.designsystem.entrance
import pl.dakil.notes.ui.designsystem.pressScale
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
 *
 * [header] spans the full width above the cards and scrolls with them, so the folder path and the
 * filters move out of the way as the notes come up.
 */
@Composable
fun LibraryCards(
    items: List<LibraryItem>,
    selection: Set<StoreRef>,
    handlers: LibraryItemHandlers,
    loadPreview: PreviewLoader,
    renderOrder: RenderOrder,
    dateFormat: DateFormat,
    showPath: Boolean,
    contentPadding: PaddingValues,
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    LazyVerticalStaggeredGrid(
        // Adaptive rather than a fixed count, so a tablet and an unfolded foldable get more columns
        // without the layout having to be told about them.
        columns = StaggeredGridCells.Adaptive(CARD_MIN_WIDTH),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        item(key = HEADER_KEY, span = StaggeredGridItemSpan.FullLine, contentType = HEADER_KEY) { header() }
        itemsIndexed(items, key = { _, item -> item.ref.value }, contentType = { _, item -> item::class }) { index, item ->
            LibraryCard(
                item = item,
                selected = item.ref in selection,
                handlers = handlers,
                loadPreview = loadPreview,
                renderOrder = renderOrder,
                dateFormat = dateFormat,
                showPath = showPath,
                modifier = Modifier.animateItem().entrance(index),
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
    renderOrder: RenderOrder,
    dateFormat: DateFormat,
    showPath: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val motion = MaterialTheme.motionScheme
    // A picked card rounds out and takes the selection colour, so a screenful of picks reads at a
    // glance; the check badge in its corner says the same for anyone who cannot tell the colours apart.
    val corner by animateDpAsState(if (selected) SELECTED_CORNER else CARD_CORNER, motion.fastSpatialSpec(), label = "corner")
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        motion.fastEffectsSpec(),
        label = "container",
    )
    val shape = RoundedCornerShape(corner)
    Box(modifier.pressScale(interaction, pressedScale = 0.97f)) {
        Surface(
            shape = shape,
            color = container,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .libraryItem(item, handlers, interaction),
        ) {
            Column {
                when (item) {
                    is LibraryItem.Folder -> FolderCardBody(item)
                    is LibraryItem.Note -> NoteCardBody(item, loadPreview, renderOrder, dateFormat, showPath, corner)
                }
            }
        }
        SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
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
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShapedIcon(
            icon = NotesIcons.FolderFill,
            polygon = MaterialShapes.Cookie4Sided,
            size = 40.dp,
            iconSize = 22.dp,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            contentDescription = stringResource(R.string.library_item_folder),
        )
        Text(
            text = folder.displayName(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 28.dp),
        )
    }
}

@Composable
private fun NoteCardBody(
    note: LibraryItem.Note,
    loadPreview: PreviewLoader,
    renderOrder: RenderOrder,
    dateFormat: DateFormat,
    showPath: Boolean,
    corner: androidx.compose.ui.unit.Dp,
) {
    val ink = note.summary.kind == NoteKind.INK
    if (ink) {
        // Roughly three fifths of a card once the text below it is counted, which is enough of the
        // page to recognise it by and little enough that the title is still on screen. Inset, with
        // corners that follow the card's, so the page sits in the card like a photo in a frame.
        NotePreviewBox(
            note = note,
            load = loadPreview,
            order = renderOrder,
            modifier = Modifier
                .padding(PREVIEW_INSET)
                .fillMaxWidth()
                .aspectRatio(PREVIEW_ASPECT)
                .clip(RoundedCornerShape(corner - PREVIEW_INSET)),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = if (ink) 6.dp else 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = note.displayName(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Clear of the selection badge, which sits over a text note's title.
            modifier = if (ink) Modifier else Modifier.padding(end = 24.dp),
        )
        val snippet = note.summary.snippet
        if (snippet.isNotBlank()) {
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // An ink note has already said what it is with its picture; the words are a hint.
                maxLines = if (ink) 1 else 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                imageVector = if (ink) NotesIcons.InkNote else NotesIcons.TextNote,
                contentDescription = note.contentDescription(),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (showPath) {
                    note.path.ifEmpty { stringResource(R.string.library_root_folder) } + "  ·  " +
                        dateFormat.format(Date(note.modifiedAt))
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
}

/** Two columns on a phone, more on anything wider. */
private val CARD_MIN_WIDTH = 160.dp

private val CARD_CORNER = 20.dp
private val SELECTED_CORNER = 32.dp
private val PREVIEW_INSET = 6.dp

/** Page-ish, cropped: tall enough to show a few lines of writing, short enough to leave room below. */
private const val PREVIEW_ASPECT = 1.25f

internal const val HEADER_KEY = "header"
