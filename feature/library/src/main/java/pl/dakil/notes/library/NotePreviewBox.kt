package pl.dakil.notes.library

import pl.dakil.notes.model.RenderOrder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.dakil.notes.format.NoteKind
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.sheet.InkNotePreview

/**
 * What a card draws of an ink note: its sheet, and its pictures already decoded small.
 *
 * Decoded here, with the read, rather than by the painter: a photograph takes longer to decode
 * than a frame lasts, and a card needs it only a few hundred pixels wide.
 */
@Immutable
class NoteThumbnail(val sheet: Sheet, val images: Map<String, ImageBitmap> = emptyMap())

/** Reads the first page of a note so a card or row can draw it. */
interface PreviewLoader {
    /** What is already in memory, answered without suspending so a card's first frame has it. */
    fun peek(note: LibraryItem.Note): NoteThumbnail?

    /** Reads the note if it has to; null on failure, or for a note with nothing to draw. */
    suspend fun load(note: LibraryItem.Note): NoteThumbnail?
}

/**
 * A picture of the note, or a stand-in until there is one.
 *
 * The sheet is fetched per item rather than with the listing, because loading it means opening the
 * `.daknote` and deserialising its strokes — the one thing the index exists to keep off the library
 * path. Fetching it here means only the notes actually on screen are ever read, and the stand-in
 * keeps the row's height stable so nothing jumps when the real thing arrives.
 */
@Composable
fun NotePreviewBox(
    note: LibraryItem.Note,
    load: PreviewLoader,
    /** The user's drawing order; see `AppSettings.renderOrder`. */
    order: RenderOrder,
    modifier: Modifier = Modifier,
) {
    val sheet by produceState(load.peek(note), note.ref, note.modifiedAt) {
        if (value == null) value = load.load(note)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        val current = sheet
        if (current != null) {
            InkNotePreview(current.sheet, Modifier.fillMaxSize(), images = current.images, order = order)
        } else {
            Icon(
                imageVector = if (note.summary.kind == NoteKind.INK) NotesIcons.InkNote else NotesIcons.TextNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
