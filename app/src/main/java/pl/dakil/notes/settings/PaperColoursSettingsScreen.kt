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
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/** What goes on the page: the paper notes start on, the units it is measured in, and the palette. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperColoursSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onOpenPaperThemes: () -> Unit,
    onOpenSavedColors: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_paper_colours)) },
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
            SectionHeader(stringResource(R.string.settings_paper))

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

            SectionHeader(stringResource(R.string.settings_colours))

            NavigationRow(
                title = stringResource(R.string.settings_saved_colours),
                summary = stringResource(R.string.settings_saved_colours_summary),
                onClick = onOpenSavedColors,
            )
        }
    }
}
