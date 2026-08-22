package pl.dakil.notes.library

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
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.sheet.InkNotePreview

/** Reads the first page of a note so a card or row can draw it; null while loading, or on failure. */
typealias PreviewLoader = suspend (LibraryItem.Note) -> Sheet?

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
    modifier: Modifier = Modifier,
) {
    val sheet by produceState<Sheet?>(null, note.ref, note.modifiedAt) { value = load(note) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        val current = sheet
        if (current != null) {
            InkNotePreview(current, Modifier.fillMaxSize())
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
