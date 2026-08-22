package pl.dakil.notes.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.LibraryLayout
import pl.dakil.notes.model.AppColorTheme
import pl.dakil.notes.model.DarkThemeOption
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PressureCurve

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
fun MeasurementUnit.label(): String = stringResource(
    when (this) {
        MeasurementUnit.CENTIMETRE -> R.string.unit_centimetres
        MeasurementUnit.MILLIMETRE -> R.string.unit_millimetres
        MeasurementUnit.INCH -> R.string.unit_inches
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
