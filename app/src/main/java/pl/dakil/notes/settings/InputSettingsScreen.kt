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
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons
import java.util.Locale
import kotlin.math.roundToInt

/** How the pen, and the hand resting beside it, are read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputSettingsScreen(
    current: AppSettings,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_input)) },
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
            val input = current.input

            SwitchRow(
                title = stringResource(R.string.settings_finger_drawing),
                summary = stringResource(R.string.settings_finger_drawing_description),
                checked = input.fingerDrawingEnabled,
                onCheckedChange = settings::setFingerDrawing,
            )

            SwitchRow(
                title = stringResource(R.string.settings_multitouch_nav),
                summary = stringResource(R.string.settings_multitouch_nav_description),
                checked = input.multiTouchNavigates,
                onCheckedChange = settings::setMultiTouchNavigates,
            )

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
                range = 0f..250f,
                format = {
                    // Zero is not "a zero-pixel palm", it is the feature switched off, and the
                    // readout should say which.
                    if (it < 1f) stringResource(R.string.settings_off)
                    else stringResource(R.string.settings_pixels_x, it.toInt())
                },
                onChange = settings::setPalmTouchMajorThreshold,
            )

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
    }
}
