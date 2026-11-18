package pl.dakil.notes.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.ImageInsertMode
import pl.dakil.notes.data.MAX_ZOOM_INDICATOR_SECONDS
import pl.dakil.notes.data.SettingsRepository
import kotlin.math.roundToInt

/** How the two editors behave: where a note reopens, how a sheet moves, what a text note may hold. */
@Composable
fun EditorSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    /** Wipes the remembered positions of ink notes (true) or text notes (false). */
    onForgetPositions: (ink: Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    SettingsSubscreen(stringResource(R.string.settings_editor), onNavigateBack) {
        SettingsGroup(stringResource(R.string.settings_reopening)) {
            // Switching one off forgets what it had kept: "don't remember" should leave nothing
            // written down, not merely stop reading it.
            SwitchRow(
                title = stringResource(R.string.settings_remember_ink),
                summary = stringResource(R.string.settings_remember_ink_description),
                checked = current.rememberInkPosition,
                onCheckedChange = {
                    settings.setRememberInkPosition(it)
                    if (!it) onForgetPositions(true)
                },
            )
            SwitchRow(
                title = stringResource(R.string.settings_remember_text),
                summary = stringResource(R.string.settings_remember_text_description),
                checked = current.rememberTextPosition,
                onCheckedChange = {
                    settings.setRememberTextPosition(it)
                    if (!it) onForgetPositions(false)
                },
            )
        }

        SettingsGroup(stringResource(R.string.settings_sheet)) {
            SliderRow(
                title = stringResource(R.string.settings_zoom_indicator),
                summary = stringResource(R.string.settings_zoom_indicator_description),
                value = current.zoomIndicatorSeconds.toFloat(),
                range = 0f..MAX_ZOOM_INDICATOR_SECONDS.toFloat(),
                steps = MAX_ZOOM_INDICATOR_SECONDS - 1,
                format = {
                    if (it.roundToInt() == 0) stringResource(R.string.settings_off)
                    else stringResource(R.string.settings_seconds_x, it.roundToInt())
                },
                onChange = { settings.setZoomIndicatorSeconds(it.roundToInt()) },
            )
            SwitchRow(
                title = stringResource(R.string.settings_inertial_scroll),
                summary = stringResource(R.string.settings_inertial_scroll_description),
                checked = current.inertialScroll,
                onCheckedChange = settings::setInertialScroll,
            )
        }

        SettingsGroup(stringResource(R.string.settings_text_notes)) {
            SwitchRow(
                title = stringResource(R.string.settings_pandoc_text_notes),
                summary = stringResource(R.string.settings_pandoc_text_notes_description),
                checked = current.pandocTextNotes,
                onCheckedChange = settings::setPandocTextNotes,
            )
        }

        // Both editors' image buttons read this, which is why it is not under either kind of note.
        SettingsGroup(stringResource(R.string.settings_images)) {
            SelectRow(
                title = stringResource(R.string.settings_image_insert),
                summary = stringResource(R.string.settings_image_insert_description),
                selected = current.imageInsertMode,
                options = ImageInsertMode.entries.map { it to it.label() },
                onSelect = settings::setImageInsertMode,
            )
        }
    }
}
