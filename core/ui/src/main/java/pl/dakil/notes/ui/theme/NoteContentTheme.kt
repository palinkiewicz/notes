package pl.dakil.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The theme a note's own content is drawn in, kept apart from the app's chrome.
 *
 * The note surfaces read `MaterialTheme` directly — heading sizes from the type scale, body text
 * from `bodyLarge`, decorations from the container roles. So anything the app does to its own look
 * would reach into every note: a new chrome font would reflow them, a colour crossfade would
 * re-annotate a long document thirty times over. Wrapping each note-content subtree in this is what
 * lets the chrome change freely while the notes stay exactly as they were.
 *
 * Wrap points, and the only ones: the `.md` document (`TextNoteScreen`), the sheet's layers
 * (`SheetEditor`), the text box being edited on a sheet (`TextBoxChrome`) and the library thumbnail
 * (`NotePreviewBox`). Export reads no theme at all. A new place that lays out note content goes
 * inside one of these or gets its own wrap; `NoteRenderingSnapshotTest` is what notices.
 *
 * Shapes and motion are the Material defaults the notes were built against, so a control drawn
 * inside a note — a task checkbox, a table's menu — keeps its old look and timing too.
 */
@Composable
fun NoteContentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LocalNoteColorScheme.current ?: MaterialTheme.colorScheme,
        typography = NoteContentTypography,
        shapes = NoteContentShapes,
        motionScheme = NoteContentMotion,
        content = content,
    )
}

/**
 * The scheme a note is coloured from: the theme's settled target, not the frame-by-frame crossfade
 * the chrome shows. Text styles are remembered by scheme identity, so handing a note the animating
 * scheme would rebuild them on every frame of a theme change; a note's colours snap to the new
 * theme once instead. Null outside `NotesTheme`, where the ambient scheme is used as it is.
 */
internal val LocalNoteColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

private val NoteContentShapes = Shapes()
private val NoteContentMotion = MotionScheme.standard()

/**
 * The type notes are laid out in, on the platform's own font — unchanged since before the app had
 * a chrome font of its own, and to stay that way.
 *
 * Body text is set slightly larger and looser than the Material default, because a note's body text
 * is the content rather than a label. The heading sizes here are what Markdown headings and the
 * rendered view on a sheet use.
 */
internal val NoteContentTypography: Typography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 25.sp,
        ),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 34.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 30.sp,
        ),
    )
}
