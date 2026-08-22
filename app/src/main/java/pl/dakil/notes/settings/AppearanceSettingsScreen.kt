package pl.dakil.notes.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.AppColorTheme
import pl.dakil.notes.model.DarkThemeOption
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.colorSchemeFor
import pl.dakil.notes.ui.theme.resolveDark

/** Everything about how the app looks: its colours, its dark mode, and how it lays notes out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_appearance)) },
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
            SectionHeader(stringResource(R.string.settings_theme_colors))

            // Dynamic colour is the platform's wallpaper palette; below Android 12 there is no
            // wallpaper palette to read, so the option is not offered rather than offered and inert.
            val themes = AppColorTheme.entries.filter {
                it != AppColorTheme.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(themes, key = { it.key }) { theme ->
                    ThemeColorSwatch(
                        theme = theme,
                        darkTheme = current.darkTheme.resolveDark(),
                        isSelected = theme == current.colorTheme,
                        onClick = { settings.setColorTheme(theme) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.settings_dark_mode))

            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                DarkThemeOption.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = option == current.darkTheme,
                        onClick = { settings.setDarkTheme(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = i,
                            count = DarkThemeOption.entries.size,
                        ),
                    ) { Text(option.label()) }
                }
            }

            SwitchRow(
                title = stringResource(R.string.settings_pure_black),
                summary = stringResource(R.string.settings_pure_black_description),
                checked = current.pureBlack,
                onCheckedChange = settings::setPureBlack,
                // It has no effect at all in light mode; a switch you can flip that changes nothing
                // reads as broken.
                enabled = current.darkTheme != DarkThemeOption.LIGHT,
            )

            SectionHeader(stringResource(R.string.settings_library))

            SelectRow(
                title = stringResource(R.string.settings_notes_layout),
                summary = stringResource(R.string.settings_notes_layout_description),
                selected = current.libraryLayout,
                options = LibraryLayout.entries.map { it to it.label() },
                onSelect = settings::setLibraryLayout,
            )

            SectionHeader(stringResource(R.string.settings_paper))

            SelectRow(
                title = stringResource(R.string.settings_measurements),
                summary = stringResource(R.string.settings_measurements_description),
                selected = current.measurementUnit,
                options = MeasurementUnit.entries.map { it to it.label() },
                onSelect = settings::setMeasurementUnit,
            )

            SwitchRow(
                title = stringResource(R.string.settings_pattern_in_document),
                summary = stringResource(R.string.settings_pattern_in_document_description),
                checked = current.patternInDocumentMode,
                onCheckedChange = settings::setPatternInDocumentMode,
            )
        }
    }
}

/**
 * One theme, painted in its own colours.
 *
 * The circle is split the way the platform's own theme picker splits it — the accent across the top,
 * its two supporting containers below — so the swatch shows what the theme will actually look like
 * rather than reducing it to a single dot.
 *
 * [darkTheme] is the app's *resolved* dark flag, not the system's: someone forcing light mode on a
 * dark phone should be shown the light schemes they are about to get.
 */
@Composable
private fun ThemeColorSwatch(
    theme: AppColorTheme,
    darkTheme: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = colorSchemeFor(colorTheme = theme, darkTheme = darkTheme)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                )
                // The ring is drawn on the outside edge, so the painted circle has to shrink inside
                // it or the two overlap.
                .padding(if (isSelected) 6.dp else 0.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(scheme.primary, startAngle = 180f, sweepAngle = 180f, useCenter = true)
                drawArc(scheme.secondaryContainer, startAngle = 90f, sweepAngle = 90f, useCenter = true)
                drawArc(scheme.tertiaryContainer, startAngle = 0f, sweepAngle = 90f, useCenter = true)
            }
            if (isSelected) {
                Icon(
                    imageVector = NotesIcons.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = theme.label(),
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
