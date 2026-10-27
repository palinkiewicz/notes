package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.ImageInsertMode
import pl.dakil.notes.data.MAX_ZOOM_INDICATOR_SECONDS
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons
import kotlin.math.roundToInt

/** How the two editors behave: where a note reopens, how a sheet moves, what a text note may hold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    /** Wipes the remembered positions of ink notes (true) or text notes (false). */
    onForgetPositions: (ink: Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_editor)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = NotesIcons.Back,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors = flatTopAppBarColors(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_reopening))

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

            SectionHeader(stringResource(R.string.settings_sheet))

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

            SectionHeader(stringResource(R.string.settings_text_notes))

            SwitchRow(
                title = stringResource(R.string.settings_pandoc_text_notes),
                summary = stringResource(R.string.settings_pandoc_text_notes_description),
                checked = current.pandocTextNotes,
                onCheckedChange = settings::setPandocTextNotes,
            )

            // Both editors' image buttons read this, which is why it is not under either kind of note.
            SectionHeader(stringResource(R.string.settings_images))

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
