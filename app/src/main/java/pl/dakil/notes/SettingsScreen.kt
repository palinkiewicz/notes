package pl.dakil.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PressureCurve
import kotlin.math.roundToInt

/**
 * The power-user surface.
 *
 * Everything here has a sensible default that a casual note-taker never has to look at. It is not
 * behind an "advanced mode" flag, because hiding settings behind a mode makes them undiscoverable
 * without making the default any simpler — it is simply one level down from the tools themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsRepository) {
    val current by settings.settings.collectAsState(initial = remember { settings.read() })

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Input")

            ListItem(
                headlineContent = { Text("Draw with finger") },
                supportingContent = {
                    Text("Turn on if your device has no stylus. Two fingers still pan and zoom.")
                },
                trailingContent = {
                    Switch(
                        checked = current.input.fingerDrawingEnabled,
                        onCheckedChange = settings::setFingerDrawing,
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Two fingers navigate") },
                supportingContent = { Text("Keeps pan and zoom available while drawing with a finger.") },
                trailingContent = {
                    Switch(
                        checked = current.input.multiTouchNavigates,
                        onCheckedChange = settings::setMultiTouchNavigates,
                    )
                },
            )

            SettingSlider(
                title = "Palm rejection window",
                detail = "Touches within this time of a stylus sample are ignored. " +
                    "Raise it if your hand still draws; lower it if touch feels unresponsive.",
                value = current.input.palmRejectionWindowMs.toFloat(),
                range = 0f..400f,
                format = { "${it.toInt()} ms" },
                onChange = { settings.setPalmRejectionWindowMs(it.toLong()) },
            )

            SettingSlider(
                title = "Palm size threshold",
                detail = "Contact patches wider than this are treated as a palm rather than a fingertip.",
                value = current.input.palmTouchMajorThreshold,
                range = 0f..250f,
                format = { if (it < 1f) "Off" else "${it.toInt()} px" },
                onChange = settings::setPalmTouchMajorThreshold,
            )

            SettingSlider(
                title = "Stroke smoothing",
                detail = "A global multiplier on every tool's own smoothing.",
                value = current.input.smoothingScale,
                range = 0f..2f,
                format = { String.format("%.2f×", it) },
                onChange = settings::setSmoothingScale,
            )

            SettingSlider(
                title = "Minimum pressure",
                detail = "Ignores the faint tail of a lifting pen.",
                value = current.input.minPressure,
                range = 0f..0.5f,
                format = { String.format("%.2f", it) },
                onChange = settings::setMinPressure,
            )

            PressureCurvePicker(current, settings)

            ListItem(
                headlineContent = { Text("Auto-shape") },
                supportingContent = {
                    Text(
                        "Hold the pen still at the end of a stroke to snap it to a line, square, " +
                            "circle or polygon. Lift normally and the stroke is left as drawn.",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = current.input.autoShapeEnabled,
                        onCheckedChange = settings::setAutoShape,
                    )
                },
            )

            // Only worth showing once the feature it tunes is on.
            if (current.input.autoShapeEnabled) {
                SettingSlider(
                    title = "Hold to snap",
                    detail = "How long the pen must rest before a shape is guessed.",
                    value = current.input.autoShapeHoldMs.toFloat(),
                    range = 250f..1000f,
                    format = { "${it.roundToInt()} ms" },
                    onChange = { settings.setAutoShapeHoldMs(it.roundToInt().toLong()) },
                )
            }

            HorizontalDivider()
            SectionHeader("Appearance")

            MeasurementUnitPicker(current, settings)

            ListItem(
                headlineContent = { Text("Show paper pattern in text mode") },
                supportingContent = { Text("Keeps grid or rule lines behind reflowing text.") },
                trailingContent = {
                    Switch(
                        checked = current.patternInDocumentMode,
                        onCheckedChange = settings::setPatternInDocumentMode,
                    )
                },
            )
        }
    }
}

/**
 * The unit every paper measurement is shown and typed in.
 *
 * Presentation only — the document is always points — so switching it never rewrites a note, and
 * the same file reads as 21 cm on one device and 8.27 in on another.
 */
@Composable
private fun MeasurementUnitPicker(current: AppSettings, settings: SettingsRepository) {
    val units = listOf(
        "Centimetres" to MeasurementUnit.CENTIMETRE,
        "Millimetres" to MeasurementUnit.MILLIMETRE,
        "Inches" to MeasurementUnit.INCH,
    )

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Measurements", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "The unit page margins, rule spacing and paper sizes are shown in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            units.forEachIndexed { i, (label, unit) ->
                SegmentedButton(
                    selected = current.measurementUnit == unit,
                    onClick = { settings.setMeasurementUnit(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = units.size),
                ) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PressureCurvePicker(current: AppSettings, settings: SettingsRepository) {
    val presets = listOf(
        "Soft" to PressureCurve.SOFT,
        "Linear" to PressureCurve.LINEAR,
        "Firm" to PressureCurve.FIRM,
    )

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Pressure response", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Soft reaches full width with a lighter touch; firm needs more force.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            presets.forEachIndexed { i, (label, curve) ->
                SegmentedButton(
                    selected = current.input.pressureCurve == curve,
                    onClick = { settings.setPressureCurve(curve) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = presets.size),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    detail: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
        Text(
            text = format(value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
