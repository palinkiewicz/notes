package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The settings index.
 *
 * Three rows and nothing else. Everything a casual note-taker never has to look at is one level
 * down, which is not the same as hiding it behind an "advanced mode": both subscreens are named on
 * the first screen, and neither is a mode you can be stuck in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    onNavigateToAppearance: () -> Unit,
    onNavigateToInput: () -> Unit,
) {
    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            NavigationRow(
                title = stringResource(R.string.settings_appearance),
                summary = stringResource(R.string.settings_appearance_summary),
                icon = NotesIcons.Palette,
                onClick = onNavigateToAppearance,
            )
            NavigationRow(
                title = stringResource(R.string.settings_input),
                summary = stringResource(R.string.settings_input_summary),
                icon = NotesIcons.Pen,
                onClick = onNavigateToInput,
            )
            // A dialog rather than a screen, and so no chevron: there is nothing here to navigate
            // into, only something to read and close.
            SettingRow(
                title = stringResource(R.string.settings_about),
                summary = stringResource(R.string.settings_about_summary),
                leading = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = { showAbout = true },
            )
        }
    }
}
