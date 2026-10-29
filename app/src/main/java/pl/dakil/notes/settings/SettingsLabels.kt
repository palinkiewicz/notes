package pl.dakil.notes.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.ImageInsertMode
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.data.SyncFrequency
import pl.dakil.notes.model.AppColorTheme
import pl.dakil.notes.model.DarkThemeOption
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PressureCurve
import pl.dakil.notes.model.RenderLayer
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutGroup
import pl.dakil.notes.model.StylusButtonAction
import pl.dakil.notes.editor.R as EditorR

/**
 * What each stored choice is called on screen.
 *
 * The enums stay in modules that have no resources and no business knowing English; the mapping to a
 * translatable name lives here, at the one layer that has a `Context`. That is also what keeps a
 * renamed enum constant from silently renaming a user-visible label.
 */

@Composable
fun AppColorTheme.label(): String = stringResource(
    when (this) {
        AppColorTheme.DAKILS_NOTES -> R.string.theme_dakils_notes
        AppColorTheme.DYNAMIC -> R.string.theme_dynamic
        AppColorTheme.OCEAN -> R.string.theme_ocean
        AppColorTheme.LAVENDER -> R.string.theme_lavender
        AppColorTheme.SUNSET -> R.string.theme_sunset
        AppColorTheme.ROSE -> R.string.theme_rose
        AppColorTheme.TEAL -> R.string.theme_teal
    },
)

@Composable
fun DarkThemeOption.label(): String = stringResource(
    when (this) {
        DarkThemeOption.SYSTEM -> R.string.dark_theme_system
        DarkThemeOption.LIGHT -> R.string.dark_theme_light
        DarkThemeOption.DARK -> R.string.dark_theme_dark
    },
)

@Composable
fun LibraryLayout.label(): String = stringResource(
    when (this) {
        LibraryLayout.CARDS -> R.string.library_layout_cards
        LibraryLayout.LIST -> R.string.library_layout_list
    },
)

@Composable
fun ImageInsertMode.label(): String = stringResource(
    when (this) {
        ImageInsertMode.DIALOG -> R.string.image_insert_dialog
        ImageInsertMode.PICKER -> R.string.image_insert_picker
    },
)

@Composable
fun MeasurementUnit.label(): String = stringResource(
    when (this) {
        MeasurementUnit.CENTIMETRE -> R.string.unit_centimetres
        MeasurementUnit.MILLIMETRE -> R.string.unit_millimetres
        MeasurementUnit.INCH -> R.string.unit_inches
    },
)

@Composable
fun StylusButtonAction.label(): String = stringResource(
    when (this) {
        StylusButtonAction.ERASE -> R.string.stylus_button_erase
        StylusButtonAction.SELECT -> R.string.stylus_button_select
        StylusButtonAction.NONE -> R.string.stylus_button_none
    },
)

/**
 * The three offered pressure responses, in order.
 *
 * `PressureCurve` is four control-point floats rather than an enum — a user can in principle sit
 * anywhere in that space — so the presets are listed here rather than derived from a type.
 */
val PressureCurvePresets: List<PressureCurve> =
    listOf(PressureCurve.SOFT, PressureCurve.LINEAR, PressureCurve.FIRM)

@Composable
fun PressureCurve.label(): String = stringResource(
    when (this) {
        PressureCurve.SOFT -> R.string.pressure_curve_soft
        PressureCurve.FIRM -> R.string.pressure_curve_firm
        // Anything that is not one of the two named shapes reads as the neutral one, including a
        // curve a future editor lets someone draw by hand.
        else -> R.string.pressure_curve_linear
    },
)

/**
 * How often a background job runs.
 *
 * Here rather than on the enum, like every other label in this file: `SyncFrequency` lives in
 * `:core:data`, which has no resources and no `Context` to resolve them with.
 */
@Composable
fun SyncFrequency.label(): String = stringResource(
    when (this) {
        SyncFrequency.MANUAL -> R.string.frequency_manual
        SyncFrequency.QUARTER_HOUR -> R.string.frequency_quarter_hour
        SyncFrequency.HOURLY -> R.string.frequency_hourly
        SyncFrequency.SIX_HOURLY -> R.string.frequency_six_hourly
        SyncFrequency.DAILY -> R.string.frequency_daily
        SyncFrequency.WEEKLY -> R.string.frequency_weekly
    }
)

/** The tool names the toolbar already uses, so a layer here is called what its button is called. */
@Composable
fun RenderLayer.label(): String = stringResource(
    when (this) {
        RenderLayer.IMAGES -> EditorR.string.editor_tool_image
        RenderLayer.TEXT -> EditorR.string.editor_tool_text
        RenderLayer.HIGHLIGHTER -> EditorR.string.editor_tool_highlighter
        RenderLayer.PEN -> EditorR.string.editor_tool_pen
        RenderLayer.FOUNTAIN_PEN -> EditorR.string.editor_tool_fountain
        RenderLayer.PENCIL -> EditorR.string.editor_tool_pencil
        RenderLayer.MARKER -> EditorR.string.editor_tool_marker
    },
)

/**
 * What a keyboard shortcut is called, as a resource id rather than a string because the system's
 * shortcut overview is built outside composition (see `MainActivity.onProvideKeyboardShortcuts`).
 * Where the editor already names the thing — a tool, a format button — the name is borrowed, so a
 * shortcut is called what its button is called.
 */
@StringRes
fun ShortcutAction.labelRes(): Int = when (this) {
    ShortcutAction.UNDO -> EditorR.string.editor_undo
    ShortcutAction.REDO -> EditorR.string.editor_redo
    ShortcutAction.SAVE -> R.string.keymap_save
    ShortcutAction.CLOSE_NOTE -> R.string.keymap_close_note
    ShortcutAction.DESELECT -> R.string.keymap_deselect
    ShortcutAction.EXPORT -> EditorR.string.editor_export
    ShortcutAction.SHOW_SHORTCUTS -> R.string.keymap_show_shortcuts
    ShortcutAction.PEN -> EditorR.string.editor_tool_pen
    ShortcutAction.FOUNTAIN_PEN -> R.string.keymap_fountain_pen
    ShortcutAction.PENCIL -> EditorR.string.editor_tool_pencil
    ShortcutAction.HIGHLIGHTER -> EditorR.string.editor_tool_highlighter
    ShortcutAction.MARKER -> EditorR.string.editor_tool_marker
    ShortcutAction.ERASER -> R.string.keymap_eraser
    ShortcutAction.LASSO -> R.string.keymap_lasso
    ShortcutAction.TEXT_TOOL -> R.string.keymap_text_tool
    ShortcutAction.IMAGE_TOOL -> R.string.keymap_image_tool
    ShortcutAction.RULER -> R.string.keymap_ruler
    ShortcutAction.SELECT_ALL -> R.string.keymap_select_all
    ShortcutAction.DELETE_SELECTION -> R.string.keymap_delete_selection
    ShortcutAction.DUPLICATE_SELECTION -> R.string.keymap_duplicate_selection
    ShortcutAction.ZOOM_IN -> R.string.keymap_zoom_in
    ShortcutAction.ZOOM_OUT -> R.string.keymap_zoom_out
    ShortcutAction.ZOOM_FIT -> EditorR.string.editor_zoom_fill_width
    ShortcutAction.PREVIOUS_PAGE -> R.string.keymap_previous_page
    ShortcutAction.NEXT_PAGE -> R.string.keymap_next_page
    ShortcutAction.ADD_PAGE -> EditorR.string.editor_add_page
    ShortcutAction.GO_TO_PAGE -> EditorR.string.editor_go_to_page
    ShortcutAction.BOLD -> EditorR.string.markdown_bold
    ShortcutAction.ITALIC -> EditorR.string.markdown_italic
    ShortcutAction.STRIKETHROUGH -> EditorR.string.markdown_strikethrough
    ShortcutAction.INLINE_CODE -> R.string.keymap_inline_code
    ShortcutAction.LINK -> EditorR.string.markdown_insert_link
    ShortcutAction.HEADING_1 -> R.string.keymap_heading_1
    ShortcutAction.HEADING_2 -> R.string.keymap_heading_2
    ShortcutAction.HEADING_3 -> R.string.keymap_heading_3
    ShortcutAction.PARAGRAPH -> R.string.keymap_body_text
    ShortcutAction.BULLET_LIST -> EditorR.string.markdown_bulleted_list
    ShortcutAction.NUMBERED_LIST -> EditorR.string.markdown_numbered_list
    ShortcutAction.TASK_LIST -> EditorR.string.markdown_task_list
    ShortcutAction.INDENT -> EditorR.string.markdown_indent_increase
    ShortcutAction.OUTDENT -> EditorR.string.markdown_indent_decrease
    ShortcutAction.SEARCH -> R.string.keymap_search
    ShortcutAction.NEW_INK_NOTE -> R.string.keymap_new_ink_note
    ShortcutAction.NEW_TEXT_NOTE -> R.string.keymap_new_text_note
}

@Composable
fun ShortcutAction.label(): String = stringResource(labelRes())

/** The one line worth adding under a shortcut whose name does not say everything it does. */
@Composable
fun ShortcutAction.hint(): String? = when (this) {
    ShortcutAction.DESELECT -> R.string.keymap_deselect_hint
    ShortcutAction.ERASER -> R.string.keymap_eraser_hint
    ShortcutAction.SHOW_SHORTCUTS -> R.string.keymap_show_shortcuts_hint
    ShortcutAction.DELETE_SELECTION -> R.string.keymap_delete_selection_hint
    ShortcutAction.SAVE -> R.string.keymap_save_hint
    ShortcutAction.INDENT, ShortcutAction.OUTDENT -> R.string.keymap_indent_hint
    else -> null
}?.let { stringResource(it) }

@StringRes
fun ShortcutGroup.titleRes(): Int = when (this) {
    ShortcutGroup.GENERAL -> R.string.keymap_group_general
    ShortcutGroup.TOOLS -> R.string.keymap_group_tools
    ShortcutGroup.SELECTION -> R.string.keymap_group_selection
    ShortcutGroup.VIEW -> R.string.keymap_group_view
    ShortcutGroup.TEXT -> R.string.keymap_group_text
    ShortcutGroup.LIBRARY -> R.string.keymap_group_library
}

/** Where a group's keys work — the thing that decides which keys it may use. */
@Composable
fun ShortcutGroup.whereLabel(): String = stringResource(
    when (this) {
        ShortcutGroup.GENERAL -> R.string.keymap_where_general
        ShortcutGroup.TOOLS, ShortcutGroup.SELECTION, ShortcutGroup.VIEW -> R.string.keymap_where_sheet
        ShortcutGroup.TEXT -> R.string.keymap_where_text
        ShortcutGroup.LIBRARY -> R.string.keymap_where_library
    },
)
