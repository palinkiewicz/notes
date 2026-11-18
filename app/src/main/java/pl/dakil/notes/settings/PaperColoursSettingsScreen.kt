package pl.dakil.notes.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.MeasurementUnit

/** What goes on the page: the paper notes start on, the units it is measured in, and the palette. */
@Composable
fun PaperColoursSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onOpenPaperThemes: () -> Unit,
    onOpenSavedColors: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    SettingsSubscreen(stringResource(R.string.settings_paper_colours), onNavigateBack) {
        SettingsGroup(stringResource(R.string.settings_paper)) {
            NavigationRow(
                title = stringResource(R.string.settings_paper_themes),
                summary = stringResource(R.string.settings_paper_themes_summary),
                onClick = onOpenPaperThemes,
            )
            SelectRow(
                title = stringResource(R.string.settings_measurements),
                summary = stringResource(R.string.settings_measurements_description),
                selected = current.measurementUnit,
                options = MeasurementUnit.entries.map { it to it.label() },
                onSelect = settings::setMeasurementUnit,
            )
        }

        SettingsGroup(stringResource(R.string.settings_colours)) {
            NavigationRow(
                title = stringResource(R.string.settings_saved_colours),
                summary = stringResource(R.string.settings_saved_colours_summary),
                onClick = onOpenSavedColors,
            )
        }
    }
}
