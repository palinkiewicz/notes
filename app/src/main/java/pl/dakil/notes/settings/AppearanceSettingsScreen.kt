package pl.dakil.notes.settings

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.AppColorTheme
import pl.dakil.notes.model.DarkThemeOption
import pl.dakil.notes.model.ProgressStyle
import pl.dakil.notes.ui.designsystem.AppLinearProgress
import pl.dakil.notes.ui.designsystem.ConnectedToggleRow
import pl.dakil.notes.ui.designsystem.MorphShape
import pl.dakil.notes.ui.designsystem.ToggleOption
import pl.dakil.notes.ui.designsystem.bleedHorizontally
import pl.dakil.notes.ui.designsystem.decorativeSpec
import pl.dakil.notes.ui.designsystem.decorativeTurn
import pl.dakil.notes.ui.designsystem.pressScale
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.Spacing
import pl.dakil.notes.ui.theme.colorSchemeFor
import pl.dakil.notes.ui.theme.resolveDark

/**
 * How the app itself looks and feels: its colours, its dark mode, how the library lays notes out,
 * and how much it moves and buzzes.
 *
 * Not how a note looks — paper and ink colours are under Paper & colours, and how the editors behave
 * is under Editor. This screen used to hold all three, and grew until nothing could be found on it.
 */
@Composable
fun AppearanceSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
) {
    val haptics = rememberHaptics()

    SettingsSubscreen(stringResource(R.string.settings_appearance), onNavigateBack) {
        SectionHeader(stringResource(R.string.settings_theme_colors))

        // Dynamic colour is the platform's wallpaper palette; below Android 12 there is no wallpaper
        // palette to read, so the option is not offered rather than offered and inert.
        val themes = remember {
            AppColorTheme.entries.filter {
                it != AppColorTheme.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            }
        }
        val darkTheme = current.darkTheme.resolveDark()

        // Out to the screen's edges, so the swatches scroll off it rather than off an invisible line.
        LazyRow(
            modifier = Modifier.bleedHorizontally(Spacing.gutter),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(themes, key = { it.key }) { theme ->
                ThemeColorSwatch(
                    theme = theme,
                    darkTheme = darkTheme,
                    isSelected = theme == current.colorTheme,
                    onClick = {
                        if (theme != current.colorTheme) haptics.tick()
                        settings.setColorTheme(theme)
                    },
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_dark_mode))

        ConnectedToggleRow(
            options = listOf(
                ToggleOption(
                    DarkThemeOption.SYSTEM,
                    DarkThemeOption.SYSTEM.label(),
                    NotesIcons.AutoBrightness,
                    NotesIcons.AutoBrightnessFill,
                ),
                ToggleOption(
                    DarkThemeOption.LIGHT,
                    DarkThemeOption.LIGHT.label(),
                    NotesIcons.LightMode,
                    NotesIcons.LightModeFill,
                ),
                ToggleOption(
                    DarkThemeOption.DARK,
                    DarkThemeOption.DARK.label(),
                    NotesIcons.DarkMode,
                    NotesIcons.DarkModeFill,
                ),
            ),
            selected = current.darkTheme,
            onSelect = settings::setDarkTheme,
            modifier = Modifier.padding(bottom = Spacing.s),
        )

        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.settings_pure_black),
                summary = stringResource(R.string.settings_pure_black_description),
                checked = current.pureBlack,
                onCheckedChange = settings::setPureBlack,
                // It has no effect at all in light mode; a switch you can flip that changes nothing
                // reads as broken.
                enabled = current.darkTheme != DarkThemeOption.LIGHT,
            )
        }

        SettingsGroup(stringResource(R.string.settings_library)) {
            ToggleRow(
                title = stringResource(R.string.settings_notes_layout),
                summary = stringResource(R.string.settings_notes_layout_description),
                options = listOf(
                    ToggleOption(
                        LibraryLayout.CARDS,
                        LibraryLayout.CARDS.label(),
                        NotesIcons.CardsLayout,
                        NotesIcons.CardsLayoutFill,
                    ),
                    ToggleOption(
                        LibraryLayout.LIST,
                        LibraryLayout.LIST.label(),
                        NotesIcons.ListLayout,
                        NotesIcons.ListLayoutFill,
                    ),
                ),
                selected = current.libraryLayout,
                onSelect = settings::setLibraryLayout,
            )
        }

        SettingsGroup(stringResource(R.string.settings_section_feel)) {
            SwitchRow(
                title = stringResource(R.string.settings_haptics),
                summary = stringResource(R.string.settings_haptics_description),
                checked = current.hapticsEnabled,
                onCheckedChange = settings::setHapticsEnabled,
                icon = NotesIcons.Vibration,
                polygon = MaterialShapes.Cookie4Sided,
            )
            SwitchRow(
                title = stringResource(R.string.settings_animated_numbers),
                summary = stringResource(R.string.settings_animated_numbers_description),
                checked = current.animatedNumbers,
                onCheckedChange = settings::setAnimatedNumbers,
                icon = NotesIcons.Numbers,
                polygon = MaterialShapes.Slanted,
            )
            SwitchRow(
                title = stringResource(R.string.settings_decorative_animations),
                summary = stringResource(R.string.settings_decorative_animations_description),
                checked = current.decorativeAnimations,
                onCheckedChange = settings::setDecorativeAnimations,
                icon = NotesIcons.Animation,
                polygon = MaterialShapes.Flower,
            )
            ToggleRow(
                title = stringResource(R.string.settings_progress_style),
                summary = stringResource(R.string.settings_progress_style_description),
                options = ProgressStyle.entries.map { ToggleOption(it, it.label()) },
                selected = current.progressStyle,
                onSelect = settings::setProgressStyle,
                icon = NotesIcons.Waves,
                polygon = MaterialShapes.Cookie6Sided,
                // The difference is quicker to see than to read about.
                preview = {
                    AppLinearProgress(
                        progress = { PROGRESS_PREVIEW },
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m),
                    )
                },
            )
        }
    }
}

/**
 * One theme, painted in its own colours.
 *
 * Split the way the platform's own theme picker splits it — the accent across the top, its two
 * supporting containers below — so the swatch shows what the theme will actually look like rather
 * than reducing it to a single dot. Choosing one morphs it from a circle into a cookie and gives it
 * a little spin; with decorative motion off it is simply a cookie.
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
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = decorativeSpec(MaterialTheme.motionScheme.defaultSpatialSpec()),
        label = "swatchMorph",
    )
    val interaction = remember { MutableInteractionSource() }
    val spin = decorativeTurn(SWATCH_SPIN)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .pressScale(interaction, pressedScale = 0.9f)
                .graphicsLayer { rotationZ = progress * spin }
                .clip(MorphShape(morph, progress.coerceIn(0f, 1f))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(scheme.primary, startAngle = 180f, sweepAngle = 180f, useCenter = true)
                drawArc(scheme.secondaryContainer, startAngle = 90f, sweepAngle = 90f, useCenter = true)
                drawArc(scheme.tertiaryContainer, startAngle = 0f, sweepAngle = 90f, useCenter = true)
            }
            Icon(
                imageVector = NotesIcons.Check,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .graphicsLayer {
                        // Against the spin, so the check stays upright while the cookie turns.
                        rotationZ = -progress * spin
                        scaleX = progress
                        scaleY = progress
                        alpha = progress.coerceIn(0f, 1f)
                    }
                    .size(24.dp),
            )
        }
        Text(
            text = theme.label(),
            style = if (isSelected) {
                MaterialTheme.typography.labelMediumEmphasized
            } else {
                MaterialTheme.typography.labelMedium
            },
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

private const val SWATCH_SPIN = 40f

/** How full the sample bar on the progress-style row is. Far enough along to show its shape. */
private const val PROGRESS_PREVIEW = 0.62f
