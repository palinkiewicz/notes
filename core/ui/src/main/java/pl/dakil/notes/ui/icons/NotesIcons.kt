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

    /** Four-way arrows: the grip that carries a text box around the page. */
    val Move: ImageVector by lazy {
        strokedIcon(
            "Move",
            "M12 3 L12 21 M3 12 L21 12 " +
                "M9 6 L12 3 L15 6 M9 18 L12 21 L15 18 " +
                "M6 9 L3 12 L6 15 M18 9 L21 12 L18 15",
        )
    }

    /** A vertical bar between two arrows: the grip that sets a text box's width. */
    val ResizeWidth: ImageVector by lazy {
        strokedIcon(
            "ResizeWidth",
            "M12 4 L12 20 M4 12 L20 12 M7 9 L4 12 L7 15 M17 9 L20 12 L17 15",
        )
    }

    val Layers: ImageVector by lazy {
        strokedIcon(
            "Layers",
            "M12 3 L21 8 L12 13 L3 8 Z M3 12 L12 17 L21 12 M3 16 L12 21 L21 16",
        )
    }

    /**
     * Finger-drawing toggle: Material's touch_app — a hand with one finger raised to the glass.
     *
     * Three parts: the arc over the fingertip, the finger itself, and the fist folded beneath it.
     */
    val TouchApp: ImageVector by lazy {
        strokedIcon(
            "TouchApp",
            "M7.6 10.9 A5 5 0 1 1 14.4 10.9 " +
                "M9.8 12.2 V7.6 a1.2 1.2 0 0 1 2.4 0 V13.4 h4.1 l3.3 1.65 " +
                "a1.5 1.5 0 0 1 0.7 1.6 l-0.75 4.3 a1.5 1.5 0 0 1 -1.5 1.25 h-6.3 " +
                "a1.5 1.5 0 0 1 -1.05 -0.44 L6.2 17.9 l0.8 -0.8 a1.3 1.3 0 0 1 1.2 -0.35 " +
                "L9.8 17 Z",
        )
    }

    /** Straightedge: a slab lying at a slight angle, with its scale marked along the near edge. */
    val Ruler: ImageVector by lazy {
        strokedIcon(
            "Ruler",
            "M3 15.5 L15.5 3 L21 8.5 L8.5 21 Z " +
                "M6.5 12 L9 14.5 M9.5 9 L13 12.5 M13 5.5 L15.5 8",
        )
    }

    val Folder: ImageVector by lazy {
        strokedIcon("Folder", FOLDER_PATH)
    }

    /** A folder with a plus in it: make a new one here. */
    val NewFolder: ImageVector by lazy {
        strokedIcon("NewFolder", FOLDER_PATH + " M12 10.5 v6 M9 13.5 h6")
    }

    /**
     * A page with two handwritten waves on it: a note that is drawn rather than typed.
     *
     * Waves rather than the straight rules of [TextNote] on purpose — the two glyphs sit next to
     * each other in the new-note menu, and a lined page beside a lined page tells nobody which is
     * which.
     */
    val InkNote: ImageVector by lazy {
        strokedIcon(
            "InkNote",
            "M5.5 3.5 h13 v17 h-13 Z " +
                "M8.5 9.5 c1.1 -2.2 2.2 -2.2 3.3 0 c1.1 2.2 2.2 2.2 3.3 0 " +
                "M8.5 15.5 c1.1 -2.2 2.2 -2.2 3.3 0",
        )
    }

    /** A funnel: narrow the list to one kind of thing. */
    val Filter: ImageVector by lazy {
        strokedIcon("Filter", "M3.5 5 h17 l-6.5 7.5 v6.5 l-4 -2 v-4.5 Z")
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

    /** Two stacked sheets: copy this page and its ink onto a new one after it. */
    val DuplicatePage: ImageVector by lazy {
        strokedIcon(
            "DuplicatePage",
            "M8.5 3.5 h11 v13 h-11 Z M15.5 20.5 h-11 v-13",
        )
    }

    /** Move this page one position towards the front of the note. */
    val MoveUp: ImageVector by lazy {
        strokedIcon("MoveUp", "M12 20 v-15 M5.5 11.5 L12 5 L18.5 11.5")
    }

    /** Move this page one position towards the back of the note. */
    val MoveDown: ImageVector by lazy {
        strokedIcon("MoveDown", "M12 4 v15 M5.5 12.5 L12 19 L18.5 12.5")
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
     * Padlock, shackle down. Authored rather than aliased to `Icons.Default.Lock` so that it and
     * [LockOpen] read as one glyph in two states — the filled core icon has no open counterpart,
     * and a pair drawn in two different styles reads as two different controls.
     */
    val Lock: ImageVector by lazy {
        strokedIcon("Lock", "M5.5 10.5 h13 v10 h-13 Z M8.5 10.5 v-3 a3.5 3.5 0 0 1 7 0 v3")
    }

    /** The same padlock with the shackle sprung, hinged on the left so the body does not move. */
    val LockOpen: ImageVector by lazy {
        strokedIcon("LockOpen", "M5.5 10.5 h13 v10 h-13 Z M8.5 10.5 v-3 a3.5 3.5 0 0 1 7 0")
    }

    /** Chevron: opens the menu belonging to the control it sits on. */
    val ExpandMore: ImageVector by lazy {
        strokedIcon("ExpandMore", "M6.5 9.5 L12 15 L17.5 9.5")
    }

    /** Trailing affordance on a settings row that opens something rather than toggling it. */
    val ChevronRight: ImageVector by lazy {
        strokedIcon("ChevronRight", "M9.5 6.5 L15 12 L9.5 17.5")
    }

    /**
     * Scale the page until it spans the window sideways: an arrow pushing out to two walls.
     *
     * The walls are what distinguish this from a plain resize — they are the edges of the window,
     * so the glyph says "out to there" rather than "bigger", which is the whole distinction between
     * fill-width and a zoom step.
     */
    val FillWidth: ImageVector by lazy {
        strokedIcon(
            "FillWidth",
            "M3.5 5 v14 M20.5 5 v14 M7 12 h10.5 M9.5 9.5 L7 12 L9.5 14.5 M15 9.5 L17.5 12 L15 14.5",
        )
    }

    /** The same glyph turned a quarter: out to the top and bottom of the window. */
    val FillHeight: ImageVector by lazy {
        strokedIcon(
            "FillHeight",
            "M5 3.5 h14 M5 20.5 h14 M12 7 v10 M9.5 9.5 L12 7 L14.5 9.5 M9.5 14.5 L12 17 L14.5 14.5",
        )
    }

    // ---- Markdown formatting --------------------------------------------------------------------
    //
    // Bold, italic and strikethrough are deliberately absent: those three are letterforms, and a
    // stroked outline of a "B" reads as a diagram of a B rather than as a B. The format bar draws
    // them with `Text` instead, which is both free and more legible at 24dp.

    /** A page of lines with a folded corner: a note that is only text. */
    val TextNote: ImageVector by lazy {
        strokedIcon(
            "TextNote",
            "M5.5 3.5 h9 l4 4 v13 h-13 Z M14.5 3.5 v4 h4 M8.5 12 h7 M8.5 16 h4",
        )
    }

    /** Three dots with three rules beside them. */
    val BulletList: ImageVector by lazy {
        strokedIcon(
            "BulletList",
            "M9 6 h11 M9 12 h11 M9 18 h11 " +
                "M4.6 6 m-0.9 0 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0 " +
                "M4.6 12 m-0.9 0 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0 " +
                "M4.6 18 m-0.9 0 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0",
        )
    }

    /** The same rules, numbered 1–2–3 down the left. */
    val NumberedList: ImageVector by lazy {
        strokedIcon(
            "NumberedList",
            "M9 6 h11 M9 12 h11 M9 18 h11 " +
                "M3.4 4.6 L4.8 4 v4 " +
                "M3.2 10.6 a1.4 1.4 0 1 1 2.2 1.6 L3.2 14 h2.4 " +
                "M3.3 16.4 a1.3 1.3 0 1 1 1.1 2 a1.3 1.3 0 1 1 -1.1 2",
        )
    }

    /** A ticked box beside a rule: the task-list item. */
    val TaskList: ImageVector by lazy {
        strokedIcon(
            "TaskList",
            "M3.5 4.5 h6 v6 h-6 Z M4.8 7.6 L6.3 9.1 L8.6 5.6 " +
                "M12.5 7.5 h8 M3.5 13.5 h6 v6 h-6 Z M12.5 16.5 h8",
        )
    }

    /** Rules pushed right, with an arrow pointing the way they went: nest this item. */
    val IndentIncrease: ImageVector by lazy {
        strokedIcon(
            "IndentIncrease",
            "M10.5 5 h10 M10.5 12 h10 M10.5 19 h10 M3.5 8.5 L7 12 L3.5 15.5",
        )
    }

    /** The same, pointing back out. */
    val IndentDecrease: ImageVector by lazy {
        strokedIcon(
            "IndentDecrease",
            "M10.5 5 h10 M10.5 12 h10 M10.5 19 h10 M7 8.5 L3.5 12 L7 15.5",
        )
    }

    /** A curly opening quote over a rule. */
    val Quote: ImageVector by lazy {
        strokedIcon(
            "Quote",
            "M4 4.5 v15 M8.5 8 h11.5 M8.5 12 h11.5 M8.5 16 h7",
        )
    }

    /** Angle brackets: the inline code span. */
    val InlineCode: ImageVector by lazy {
        strokedIcon("InlineCode", "M9 8 L5 12 L9 16 M15 8 L19 12 L15 16 M13.4 5.5 L10.6 18.5")
    }

    /** The same brackets boxed: a whole fenced block rather than a span. */
    val CodeBlock: ImageVector by lazy {
        strokedIcon(
            "CodeBlock",
            "M3.5 4.5 h17 v15 h-17 Z M9.5 9.5 L7 12 L9.5 14.5 M14.5 9.5 L17 12 L14.5 14.5",
        )
    }

    /** A single rule across the page: the thematic break. */
    val HorizontalRule: ImageVector by lazy {
        strokedIcon("HorizontalRule", "M3.5 12 h17")
    }

    /** Two chain links. */
    val Link: ImageVector by lazy {
        strokedIcon(
            "Link",
            "M10 13.8 a3.6 3.6 0 0 0 5.1 0 l3 -3 a3.6 3.6 0 0 0 -5.1 -5.1 l-1.4 1.4 " +
                "M14 10.2 a3.6 3.6 0 0 0 -5.1 0 l-3 3 a3.6 3.6 0 0 0 5.1 5.1 l1.4 -1.4",
        )
    }

    /** A framed picture with a sun and a hill. */
    val Image: ImageVector by lazy {
        strokedIcon(
            "Image",
            "M3.5 4.5 h17 v15 h-17 Z M8 9.2 m-1.2 0 a1.2 1.2 0 1 0 2.4 0 a1.2 1.2 0 1 0 -2.4 0 " +
                "M3.5 16.5 L9 11.5 L14 16 L17 13.5 L20.5 16.5",
        )
    }

    /** A grid: header row plus two columns. */
    val Table: ImageVector by lazy {
        strokedIcon(
            "Table",
            "M3.5 4.5 h17 v15 h-17 Z M3.5 9.5 h17 M3.5 14.5 h17 M12 9.5 v10",
        )
    }

    /** Two stacked sheets: take a copy of this. */
    val Copy: ImageVector by lazy {
        strokedIcon("Copy", "M9 9 h10 v11 h-10 Z M15 9 v-3.5 h-10 v11 h3.5")
    }

    /** A capital H with a descending stem: "this line is a heading". */
    val Heading: ImageVector by lazy {
        strokedIcon("Heading", "M5 4.5 v15 M13 4.5 v15 M5 12 h8 M16.5 19.5 v-7 h4 M20.5 15.5 h-4")
    }

    /** An eye: leave the source alone and show the formatted result. */
    val Preview: ImageVector by lazy {
        strokedIcon(
            "Preview",
            "M2.5 12 c3 -4.8 6.2 -7.2 9.5 -7.2 c3.3 0 6.5 2.4 9.5 7.2 " +
                "c-3 4.8 -6.2 7.2 -9.5 7.2 c-3.3 0 -6.5 -2.4 -9.5 -7.2 Z " +
                "M12 12 m-2.6 0 a2.6 2.6 0 1 0 5.2 0 a2.6 2.6 0 1 0 -5.2 0",
        )
    }

    /** Angle brackets around a slash: show the raw Markdown. */
    val Source: ImageVector by lazy {
        strokedIcon("Source", "M8 7 L3.5 12 L8 17 M16 7 L20.5 12 L16 17 M13.6 5 L10.4 19")
    }

    /**
     * Builds a 24×24 icon from SVG path data, rendered as a stroke.
     *
     * `PathParser` is part of `ui-graphics`, so this needs no extra dependency and no generated
     * XML resources.
     */
    /** Shared by [Folder] and [NewFolder], so the two can never drift apart. */
    private const val FOLDER_PATH =
        "M3 6.5 a1.5 1.5 0 0 1 1.5 -1.5 h4.5 l2 2.5 h8 a1.5 1.5 0 0 1 1.5 1.5 " +
            "v9 a1.5 1.5 0 0 1 -1.5 1.5 h-15 a1.5 1.5 0 0 1 -1.5 -1.5 Z"

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
