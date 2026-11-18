package pl.dakil.notes.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.StylusButtonAction
import java.util.Locale
import kotlin.math.roundToInt

/** How the pen, and the hand resting beside it, are read. */
@Composable
fun InputSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onOpenDrawingOrder: () -> Unit,
    onOpenKeyboardShortcuts: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val input = current.input

    SettingsSubscreen(stringResource(R.string.settings_input), onNavigateBack) {
        SettingsGroup(stringResource(R.string.settings_drawing)) {
            SwitchRow(
                title = stringResource(R.string.settings_finger_drawing),
                summary = stringResource(R.string.settings_finger_drawing_description),
                checked = current.fingerDrawingAvailable,
                onCheckedChange = settings::setFingerDrawing,
            )

            // Switching it off forgets the kept tools, like the remember-place switches do.
            SwitchRow(
                title = stringResource(R.string.settings_remember_tools),
                summary = stringResource(R.string.settings_remember_tools_description),
                checked = current.rememberTools,
                onCheckedChange = {
                    settings.setRememberTools(it)
                    if (!it) settings.clearToolState()
                },
            )
            SelectRow(
                title = stringResource(R.string.settings_stylus_button),
                summary = stringResource(R.string.settings_stylus_button_description),
                selected = input.stylusButtonAction,
                options = StylusButtonAction.entries.map { it to it.label() },
                onSelect = settings::setStylusButtonAction,
            )
            NavigationRow(
                title = stringResource(R.string.settings_drawing_order),
                summary = stringResource(R.string.settings_drawing_order_summary),
                onClick = onOpenDrawingOrder,
            )
        }

        SettingsGroup(stringResource(R.string.settings_palm_rejection)) {
            SliderRow(
                title = stringResource(R.string.settings_palm_window),
                summary = stringResource(R.string.settings_palm_window_description),
                value = input.palmRejectionWindowMs.toFloat(),
                range = 0f..400f,
                format = { stringResource(R.string.settings_milliseconds_x, it.roundToInt()) },
                onChange = { settings.setPalmRejectionWindowMs(it.toLong()) },
            )

            SliderRow(
                title = stringResource(R.string.settings_palm_size),
                summary = stringResource(R.string.settings_palm_size_description),
                value = input.palmTouchMajorThreshold,
                range = 0f..300f,
                format = {
                    // Zero is not "a zero-pixel palm", it is the feature switched off, and the
                    // readout should say which.
                    if (it < 1f) stringResource(R.string.settings_off)
                    else stringResource(R.string.settings_pixels_x, it.toInt())
                },
                onChange = settings::setPalmTouchMajorThreshold,
            )
        }

        SettingsGroup(stringResource(R.string.settings_pressure)) {
            SliderRow(
                title = stringResource(R.string.settings_smoothing),
                summary = stringResource(R.string.settings_smoothing_description),
                value = input.smoothingScale,
                range = 0f..2f,
                format = {
                    stringResource(
                        R.string.settings_multiplier_x,
                        String.format(Locale.getDefault(), "%.2f", it),
                    )
                },
                onChange = settings::setSmoothingScale,
            )

            SliderRow(
                title = stringResource(R.string.settings_min_pressure),
                summary = stringResource(R.string.settings_min_pressure_description),
                value = input.minPressure,
                range = 0f..0.5f,
                format = { String.format(Locale.getDefault(), "%.2f", it) },
                onChange = settings::setMinPressure,
            )

            SelectRow(
                title = stringResource(R.string.settings_pressure_curve),
                summary = stringResource(R.string.settings_pressure_curve_description),
                selected = input.pressureCurve,
                options = PressureCurvePresets.map { it to it.label() },
                onSelect = settings::setPressureCurve,
            )
        }

        SettingsGroup(
            title = stringResource(R.string.settings_section_shapes),
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            SwitchRow(
                title = stringResource(R.string.settings_auto_shape),
                summary = stringResource(R.string.settings_auto_shape_description),
                checked = input.autoShapeEnabled,
                onCheckedChange = settings::setAutoShape,
            )

            // Only worth showing once the feature it tunes is on.
            if (input.autoShapeEnabled) {
                SliderRow(
                    title = stringResource(R.string.settings_auto_shape_hold),
                    summary = stringResource(R.string.settings_auto_shape_hold_description),
                    value = input.autoShapeHoldMs.toFloat(),
                    range = 250f..1000f,
                    format = { stringResource(R.string.settings_milliseconds_x, it.roundToInt()) },
                    onChange = { settings.setAutoShapeHoldMs(it.roundToInt().toLong()) },
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_keyboard)) {
            NavigationRow(
                title = stringResource(R.string.settings_keyboard_shortcuts),
                summary = stringResource(R.string.settings_keyboard_shortcuts_summary),
                onClick = onOpenKeyboardShortcuts,
            )
        }
    }
}
