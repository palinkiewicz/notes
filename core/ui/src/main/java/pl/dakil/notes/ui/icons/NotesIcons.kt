package pl.dakil.notes.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import pl.dakil.notes.ui.R

/**
 * The app's icon set: Material Symbols Rounded, the family the author's other apps draw in.
 *
 * The glyphs are vector drawables in `core/ui/src/main/res/drawable` — `sym_*` fetched by
 * `tools/fetch-icons.py` from `tools/icons.txt`, and two hand-made `notes_*` composites. Resources
 * rather than Kotlin path data because the APK deflates resource XML but stores dex as it is, so the
 * same paths cost about a third as much; `material-icons-extended`, at ~9 MB, was never an option.
 *
 * Each name says what the icon *means* here, not which symbol draws it, so a better glyph is a
 * one-line change. A `…Fill` twin is the filled form, for something that fills in when it is
 * selected (see `AnimatedIcon`).
 *
 * The getters are composable because a drawable is read through the composition's resources; they
 * are cached there, so reading one on every recomposition costs nothing.
 */
object NotesIcons {

    // ---- Navigation and common actions ----------------------------------------------------------

    val Back: ImageVector @Composable get() = sym(R.drawable.sym_arrow_back)
    val Add: ImageVector @Composable get() = sym(R.drawable.sym_add)
    val Search: ImageVector @Composable get() = sym(R.drawable.sym_search)
    val SearchOff: ImageVector @Composable get() = sym(R.drawable.sym_search_off)
    val Settings: ImageVector @Composable get() = sym(R.drawable.sym_settings)
    val SettingsFill: ImageVector @Composable get() = sym(R.drawable.sym_settings_fill)
    val Delete: ImageVector @Composable get() = sym(R.drawable.sym_delete)
    val DeleteFill: ImageVector @Composable get() = sym(R.drawable.sym_delete_fill)
    val DeleteForever: ImageVector @Composable get() = sym(R.drawable.sym_delete_forever)

    /** The trash as a place. */
    val Trash: ImageVector @Composable get() = sym(R.drawable.sym_delete)

    /** Empty the whole trash. */
    val EmptyTrash: ImageVector @Composable get() = sym(R.drawable.sym_delete_sweep)
    val RestoreFromTrash: ImageVector @Composable get() = sym(R.drawable.sym_restore_from_trash)
    val Info: ImageVector @Composable get() = sym(R.drawable.sym_info)
    val More: ImageVector @Composable get() = sym(R.drawable.sym_more_vert)
    val Export: ImageVector @Composable get() = sym(R.drawable.sym_ios_share)
    val Share: ImageVector @Composable get() = sym(R.drawable.sym_share)
    val Check: ImageVector @Composable get() = sym(R.drawable.sym_check)
    val Close: ImageVector @Composable get() = sym(R.drawable.sym_close)
    val Rename: ImageVector @Composable get() = sym(R.drawable.sym_edit)
    val Error: ImageVector @Composable get() = sym(R.drawable.sym_error)
    val OpenInNew: ImageVector @Composable get() = sym(R.drawable.sym_open_in_new)

    /** Marks the paper theme new notes are made with. */
    val Default: ImageVector @Composable get() = sym(R.drawable.sym_star_fill)

    val Undo: ImageVector @Composable get() = sym(R.drawable.sym_undo)
    val Redo: ImageVector @Composable get() = sym(R.drawable.sym_redo)

    /** An arrow coming round to where it started: back to the defaults. */
    val Restore: ImageVector @Composable get() = sym(R.drawable.sym_restart_alt)

    /** Chevron: opens the menu belonging to the control it sits on. */
    val ExpandMore: ImageVector @Composable get() = sym(R.drawable.sym_expand_more)

    /** The small triangle on a control that drops a list of choices. */
    val ArrowDropDown: ImageVector @Composable get() = sym(R.drawable.sym_arrow_drop_down)

    /** Trailing affordance on a row that opens something rather than toggling it. */
    val ChevronRight: ImageVector @Composable get() = sym(R.drawable.sym_chevron_right)
    val Copy: ImageVector @Composable get() = sym(R.drawable.sym_content_copy)
    val Keyboard: ImageVector @Composable get() = sym(R.drawable.sym_keyboard)

    // ---- The library ----------------------------------------------------------------------------

    /** The library itself: a stack of notes. */
    val Note: ImageVector @Composable get() = sym(R.drawable.sym_note_stack)
    val NoteFill: ImageVector @Composable get() = sym(R.drawable.sym_note_stack_fill)

    /** A blank note, for an empty library. */
    val BlankNote: ImageVector @Composable get() = sym(R.drawable.sym_sticky_note_2)
    val Folder: ImageVector @Composable get() = sym(R.drawable.sym_folder)
    val FolderFill: ImageVector @Composable get() = sym(R.drawable.sym_folder_fill)
    val NewFolder: ImageVector @Composable get() = sym(R.drawable.sym_create_new_folder)

    /** Move the selection into another folder. */
    val MoveTo: ImageVector @Composable get() = sym(R.drawable.sym_drive_file_move)

    /** A pen drawing a line: a note written by hand. */
    val InkNote: ImageVector @Composable get() = sym(R.drawable.sym_draw)
    val InkNoteFill: ImageVector @Composable get() = sym(R.drawable.sym_draw_fill)

    /** A page of lines: a note that is only text. */
    val TextNote: ImageVector @Composable get() = sym(R.drawable.sym_description)
    val TextNoteFill: ImageVector @Composable get() = sym(R.drawable.sym_description_fill)

    /** Everything, unfiltered. */
    val AllItems: ImageVector @Composable get() = sym(R.drawable.sym_apps)
    val AllItemsFill: ImageVector @Composable get() = sym(R.drawable.sym_apps_fill)

    /** A PDF to write on. */
    val Pdf: ImageVector @Composable get() = sym(R.drawable.sym_picture_as_pdf)
    val Filter: ImageVector @Composable get() = sym(R.drawable.sym_filter_alt)
    val Sort: ImageVector @Composable get() = sym(R.drawable.sym_sort)
    val Tag: ImageVector @Composable get() = sym(R.drawable.sym_label)
    val TagFill: ImageVector @Composable get() = sym(R.drawable.sym_label_fill)

    /** Notes as a grid of cards. */
    val CardsLayout: ImageVector @Composable get() = sym(R.drawable.sym_grid_view)
    val CardsLayoutFill: ImageVector @Composable get() = sym(R.drawable.sym_grid_view_fill)

    /** Notes as a list of rows. */
    val ListLayout: ImageVector @Composable get() = sym(R.drawable.sym_view_agenda)
    val ListLayoutFill: ImageVector @Composable get() = sym(R.drawable.sym_view_agenda_fill)

    // ---- Drawing tools --------------------------------------------------------------------------

    /** Ballpoint. */
    val Pen: ImageVector @Composable get() = sym(R.drawable.sym_stylus)
    val FountainPen: ImageVector @Composable get() = sym(R.drawable.sym_stylus_fountain_pen)
    val Pencil: ImageVector @Composable get() = sym(R.drawable.sym_stylus_pencil)
    val Highlighter: ImageVector @Composable get() = sym(R.drawable.sym_ink_highlighter)

    /** The two-tone outline marker. */
    val Marker: ImageVector @Composable get() = sym(R.drawable.sym_ink_marker)

    /** Exchange the two things either side of it. */
    val Swap: ImageVector @Composable get() = sym(R.drawable.sym_swap_horiz)

    /** Stroke eraser: the block and the line it takes away. */
    val EraserStroke: ImageVector @Composable get() = sym(R.drawable.sym_ink_eraser)

    /** Area eraser: the same block, with the disc it removes. */
    val EraserPoint: ImageVector @Composable get() = sym(R.drawable.notes_eraser_point)
    val Lasso: ImageVector @Composable get() = sym(R.drawable.sym_lasso_select)
    val TextBox: ImageVector @Composable get() = sym(R.drawable.sym_text_fields)

    /** Four-way arrows: the grip that carries a text box around the page. */
    val Move: ImageVector @Composable get() = sym(R.drawable.sym_open_with)

    /** The grip that sets a text box's width. */
    val ResizeWidth: ImageVector @Composable get() = sym(R.drawable.sym_arrow_range)

    /** The grip that turns a selection about its middle. */
    val Rotate: ImageVector @Composable get() = sym(R.drawable.sym_rotate_right)
    val FlipHorizontal: ImageVector @Composable get() = sym(R.drawable.sym_flip)
    val FlipVertical: ImageVector @Composable get() = sym(R.drawable.notes_flip_vertical)
    val Layers: ImageVector @Composable get() = sym(R.drawable.sym_layers)

    /** Drawing with a finger as well as a stylus. */
    val TouchApp: ImageVector @Composable get() = sym(R.drawable.sym_touch_app)
    val Ruler: ImageVector @Composable get() = sym(R.drawable.sym_straighten)
    val Palette: ImageVector @Composable get() = sym(R.drawable.sym_palette)

    /** Save this colour to the palette. */
    val Bookmark: ImageVector @Composable get() = sym(R.drawable.sym_bookmark)

    /** The colour is saved; pressing again takes it back out. */
    val BookmarkFilled: ImageVector @Composable get() = sym(R.drawable.sym_bookmark_fill)

    /** Fill the inside of a shape. */
    val Fill: ImageVector @Composable get() = sym(R.drawable.sym_format_color_fill)

    /** Take the fill back out and leave the outline. */
    val FillOff: ImageVector @Composable get() = sym(R.drawable.sym_format_color_reset)
    val Lock: ImageVector @Composable get() = sym(R.drawable.sym_lock)
    val LockOpen: ImageVector @Composable get() = sym(R.drawable.sym_lock_open_right)

    // ---- Pages and the sheet --------------------------------------------------------------------

    val AddPage: ImageVector @Composable get() = sym(R.drawable.sym_note_add)
    val DuplicatePage: ImageVector @Composable get() = sym(R.drawable.sym_file_copy)

    /** Jump the view to a page by its number. */
    val GoToPage: ImageVector @Composable get() = sym(R.drawable.sym_input)
    val MoveUp: ImageVector @Composable get() = sym(R.drawable.sym_arrow_upward)
    val MoveDown: ImageVector @Composable get() = sym(R.drawable.sym_arrow_downward)

    /** Move a colour one place towards the start of its row; mirrors with the row in RTL. */
    val MoveEarlier: ImageVector @Composable get() = sym(R.drawable.sym_arrow_back)

    /** Move a colour one place towards the end of its row. */
    val MoveLater: ImageVector @Composable get() = sym(R.drawable.sym_arrow_forward)

    /** The paper: its size, pattern and colour. */
    val PageSetup: ImageVector @Composable get() = sym(R.drawable.sym_texture)

    /** Scale the page out to the sides of the window. */
    val FillWidth: ImageVector @Composable get() = sym(R.drawable.sym_fit_page_width)

    /** Scale the page out to the top and bottom of the window. */
    val FillHeight: ImageVector @Composable get() = sym(R.drawable.sym_fit_page_height)

    // ---- Markdown formatting --------------------------------------------------------------------

    val BulletList: ImageVector @Composable get() = sym(R.drawable.sym_format_list_bulleted)
    val NumberedList: ImageVector @Composable get() = sym(R.drawable.sym_format_list_numbered)
    val TaskList: ImageVector @Composable get() = sym(R.drawable.sym_checklist)
    val IndentIncrease: ImageVector @Composable get() = sym(R.drawable.sym_format_indent_increase)
    val IndentDecrease: ImageVector @Composable get() = sym(R.drawable.sym_format_indent_decrease)
    val Quote: ImageVector @Composable get() = sym(R.drawable.sym_format_quote)
    val InlineCode: ImageVector @Composable get() = sym(R.drawable.sym_code)
    val CodeBlock: ImageVector @Composable get() = sym(R.drawable.sym_code_blocks)
    val HorizontalRule: ImageVector @Composable get() = sym(R.drawable.sym_horizontal_rule)
    val Link: ImageVector @Composable get() = sym(R.drawable.sym_link)
    val Image: ImageVector @Composable get() = sym(R.drawable.sym_image)
    val Crop: ImageVector @Composable get() = sym(R.drawable.sym_crop)
    val Table: ImageVector @Composable get() = sym(R.drawable.sym_table)

    /** "This line is a heading": opens the block styles. */
    val Heading: ImageVector @Composable get() = sym(R.drawable.sym_title)

    /** Leave the source alone and show the formatted result. */
    val Preview: ImageVector @Composable get() = sym(R.drawable.sym_visibility)

    /** Show the raw Markdown. */
    val Source: ImageVector @Composable get() = sym(R.drawable.sym_markdown)

    // ---- Settings -------------------------------------------------------------------------------

    val EditNote: ImageVector @Composable get() = sym(R.drawable.sym_edit_note)
    val Backup: ImageVector @Composable get() = sym(R.drawable.sym_backup)
    val Sync: ImageVector @Composable get() = sym(R.drawable.sym_sync)
    val AutoBrightness: ImageVector @Composable get() = sym(R.drawable.sym_brightness_auto)
    val AutoBrightnessFill: ImageVector @Composable get() = sym(R.drawable.sym_brightness_auto_fill)
    val LightMode: ImageVector @Composable get() = sym(R.drawable.sym_light_mode)
    val LightModeFill: ImageVector @Composable get() = sym(R.drawable.sym_light_mode_fill)
    val DarkMode: ImageVector @Composable get() = sym(R.drawable.sym_dark_mode)
    val DarkModeFill: ImageVector @Composable get() = sym(R.drawable.sym_dark_mode_fill)

    /** Pure black backgrounds. */
    val Contrast: ImageVector @Composable get() = sym(R.drawable.sym_contrast)
    val Vibration: ImageVector @Composable get() = sym(R.drawable.sym_vibration)
    val Animation: ImageVector @Composable get() = sym(R.drawable.sym_animation)
    val Numbers: ImageVector @Composable get() = sym(R.drawable.sym_123)

    /** How progress bars are drawn: wavy or straight. */
    val Waves: ImageVector @Composable get() = sym(R.drawable.sym_waves)

    // About
    val Person: ImageVector @Composable get() = sym(R.drawable.sym_person)
    val FavoriteFill: ImageVector @Composable get() = sym(R.drawable.sym_favorite_fill)
}

@Composable
private fun sym(@DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)
