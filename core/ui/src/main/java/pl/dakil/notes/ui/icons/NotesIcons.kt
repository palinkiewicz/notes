package pl.dakil.notes.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's icon set.
 *
 * `androidx.compose.material:material-icons-extended` is roughly nine megabytes of vector
 * drawables — on its own it would blow the entire size budget for a handful of glyphs. So the
 * icons that exist in `material-icons-core` (already a transitive dependency of Material 3) are
 * aliased, and the dozen tool glyphs that do not exist there are authored here as stroked paths.
 *
 * Stroked rather than filled: a consistent 1.8dp outline matches the Material Symbols "outlined"
 * look, and stroke geometry is far easier to author correctly by hand than filled silhouettes.
 */
object NotesIcons {

    // ---- From material-icons-core (no extra dependency) -----------------------------------------

    val Back get() = Icons.AutoMirrored.Filled.ArrowBack
    val Add get() = Icons.Default.Add
    val Search get() = Icons.Default.Search
    val Settings get() = Icons.Default.Settings
    val Delete get() = Icons.Default.Delete
    val More get() = Icons.Default.MoreVert
    val Check get() = Icons.Default.Check
    val Close get() = Icons.Default.Close
    val Rename get() = Icons.Default.Edit

    // ---- Authored here --------------------------------------------------------------------------

    val Undo: ImageVector by lazy {
        strokedIcon("Undo", "M9 14 L4 9 L9 4 M4 9 h9 a6 6 0 1 1 0 12 h-4")
    }

    val Redo: ImageVector by lazy {
        strokedIcon("Redo", "M15 14 L20 9 L15 4 M20 9 h-9 a6 6 0 1 0 0 12 h4")
    }

    /** Ballpoint: a plain barrel with a fine tip. */
    val Pen: ImageVector by lazy {
        strokedIcon("Pen", "M17 3.5 L20.5 7 L8 19.5 L3.5 21 L5 16.5 Z M14.5 6 L18 9.5")
    }

    /** Fountain pen: the same barrel ending in a nib with a slit. */
    val FountainPen: ImageVector by lazy {
        strokedIcon(
            "FountainPen",
            "M16 3 L21 8 L11 18 L6 21 L9 16 Z M9 16 L11 18 M12.5 11.5 L14.5 13.5",
        )
    }

    /** Pencil: barrel plus the ferrule band that distinguishes it from a pen. */
    val Pencil: ImageVector by lazy {
        strokedIcon(
            "Pencil",
            "M15.5 3 L21 8.5 L9.5 20 L4 21.5 L5.5 16 Z M13.5 5 L19 10.5 M5.5 16 L9.5 20",
        )
    }

    /** Highlighter: a chisel marker sitting on the line it has drawn. */
    val Highlighter: ImageVector by lazy {
        strokedIcon(
            "Highlighter",
            "M15 3 L21 9 L12.5 17.5 L7 17.5 L5.5 12 Z M4 21 L20 21",
        )
    }

    /** Stroke eraser: the classic wedge block. */
    val EraserStroke: ImageVector by lazy {
        strokedIcon("EraserStroke", "M7.5 20.5 L3 16 L13 6 L19 12 L14.5 16.5 Z M9.5 20.5 L20.5 20.5")
    }

    /** Point eraser: the same block, plus the disc it removes. */
    val EraserPoint: ImageVector by lazy {
        strokedIcon(
            "EraserPoint",
            "M8 17.5 L4 13.5 L12 5.5 L17 10.5 L13 14.5 Z " +
                "M17 17 m-3.2 0 a3.2 3.2 0 1 0 6.4 0 a3.2 3.2 0 1 0 -6.4 0",
        )
    }

    /** Lasso: a loop with the tail the user drags. */
    val Lasso: ImageVector by lazy {
        strokedIcon(
            "Lasso",
            "M12 4 a8 5.5 0 1 0 0.01 0 M11 15 L9.5 20.5 L14 18.5 Z",
        )
    }

    /** Text block. */
    val TextBox: ImageVector by lazy {
        strokedIcon("TextBox", "M5 5 L19 5 M12 5 L12 19 M8.5 19 L15.5 19")
    }

    val Layers: ImageVector by lazy {
        strokedIcon(
            "Layers",
            "M12 3 L21 8 L12 13 L3 8 Z M3 12 L12 17 L21 12 M3 16 L12 21 L21 16",
        )
    }

    /** Finger-drawing toggle: a touch point with contact ripples. */
    val FingerDraw: ImageVector by lazy {
        strokedIcon(
            "FingerDraw",
            "M12 12 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0 " +
                "M7.4 7.4 a6.5 6.5 0 0 0 0 9.2 M16.6 16.6 a6.5 6.5 0 0 0 0 -9.2",
        )
    }

    val Folder: ImageVector by lazy {
        strokedIcon("Folder", "M3 6.5 a1.5 1.5 0 0 1 1.5 -1.5 h4.5 l2 2.5 h8 a1.5 1.5 0 0 1 1.5 1.5 v9 a1.5 1.5 0 0 1 -1.5 1.5 h-15 a1.5 1.5 0 0 1 -1.5 -1.5 Z")
    }

    val Note: ImageVector by lazy {
        strokedIcon(
            "Note",
            "M5.5 3.5 h13 v17 h-13 Z M8.5 8 h7 M8.5 12 h7 M8.5 16 h4",
        )
    }

    /** A sheet with a plus: append a blank page to the end of the note. */
    val AddPage: ImageVector by lazy {
        strokedIcon(
            "AddPage",
            "M4.5 3.5 h10 v13 h-10 Z M7.5 7.5 h4 M7.5 11 h4 " +
                "M17.5 15 v6 M14.5 18 h6",
        )
    }

    /** The same sheet with a minus: give back a blank page from the end. */
    val RemovePage: ImageVector by lazy {
        strokedIcon(
            "RemovePage",
            "M4.5 3.5 h10 v13 h-10 Z M7.5 7.5 h4 M7.5 11 h4 " +
                "M14.5 18 h6",
        )
    }

    val PageSetup: ImageVector by lazy {
        strokedIcon(
            "PageSetup",
            "M5 3.5 h14 v17 h-14 Z M5 9 h14 M5 14.5 h14 M9.5 3.5 v17",
        )
    }

    val Tag: ImageVector by lazy {
        strokedIcon(
            "Tag",
            "M4 4 h7.5 L20 12.5 L12.5 20 L4 11.5 Z M8 8 m-1.1 0 a1.1 1.1 0 1 0 2.2 0 a1.1 1.1 0 1 0 -2.2 0",
        )
    }

    val Palette: ImageVector by lazy {
        strokedIcon(
            "Palette",
            "M12 3.2 a8.8 8.8 0 1 0 0 17.6 c1.5 0 2.2 -1 2.2 -1.9 c0 -1.4 -1.3 -1.6 -1.3 -2.7 " +
                "c0 -0.9 0.8 -1.5 1.8 -1.5 h1.9 a4.2 4.2 0 0 0 4.2 -4.2 c0 -4 -3.9 -7.3 -8.8 -7.3 Z " +
                "M8 9.5 m-0.9 0 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0 " +
                "M13.5 7.5 m-0.9 0 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0 " +
                "M6.8 14.5 m-0.9 0 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0",
        )
    }

    val Sort: ImageVector by lazy {
        strokedIcon("Sort", "M4 6.5 h16 M4 12 h11 M4 17.5 h6")
    }

    /**
     * Builds a 24×24 icon from SVG path data, rendered as a stroke.
     *
     * `PathParser` is part of `ui-graphics`, so this needs no extra dependency and no generated
     * XML resources.
     */
    private fun strokedIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                // Black is a placeholder: `Icon` tints the whole vector with the current content
                // colour, so the authored colour never reaches the screen.
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}
