package pl.dakil.notes.editor.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.R
import pl.dakil.notes.model.ExportColorPreset

/** Which container an export lands in. Not stored anywhere, so it needs no locale-safe key. */
enum class ExportFormat { PDF, PNG }

/**
 * Export as PDF or PNG, with an optional colour transform.
 *
 * The colour section only appears for an ink note: a `.md` note's text has no stored colour to
 * transform — it is theme-rendered — so there is nothing here for the preset to act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    isInk: Boolean,
    exporting: Boolean,
    onDismiss: () -> Unit,
    onExport: (ExportColorPreset, ExportFormat) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var preset by remember { mutableStateOf(ExportColorPreset.AS_IS) }
    var format by remember { mutableStateOf(ExportFormat.PDF) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isInk) {
                SectionLabel(stringResource(R.string.editor_export_colors))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    ColorPresetRow(
                        label = stringResource(R.string.editor_export_as_is),
                        selected = preset == ExportColorPreset.AS_IS,
                        onClick = { preset = ExportColorPreset.AS_IS },
                    )
                    ColorPresetRow(
                        label = stringResource(R.string.editor_export_inversion),
                        selected = preset == ExportColorPreset.INVERSION,
                        onClick = { preset = ExportColorPreset.INVERSION },
                    )
                    ColorPresetRow(
                        label = stringResource(R.string.editor_export_intelligent),
                        selected = preset == ExportColorPreset.INTELLIGENT,
                        onClick = { preset = ExportColorPreset.INTELLIGENT },
                    )
                }
            }

            SectionLabel(stringResource(R.string.editor_export_format))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                SegmentedButton(
                    selected = format == ExportFormat.PDF,
                    onClick = { format = ExportFormat.PDF },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.editor_export_pdf)) }
                SegmentedButton(
                    selected = format == ExportFormat.PNG,
                    onClick = { format = ExportFormat.PNG },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.editor_export_png)) }
            }

            Button(
                onClick = { onExport(preset, format) },
                enabled = !exporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.editor_export_action))
                }
            }
        }
    }
}

@Composable
private fun ColorPresetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}
