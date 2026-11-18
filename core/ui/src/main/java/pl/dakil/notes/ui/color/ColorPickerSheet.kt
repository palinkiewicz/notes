package pl.dakil.notes.ui.color

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.ui.R
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * A [ColorPicker] in a modal sheet, with live preview and a cancel path.
 *
 * Changes are applied as the user drags — seeing the page or the pen change underneath is the whole
 * point of picking a colour — but the value at open is kept so "Cancel" can genuinely put it back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    title: String,
    initial: Int,
    onColorChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onCommit: (Int) -> Unit = {},
    showAlpha: Boolean = true,
    presets: IntArray = ColorCodec.INK_PRESETS,
    recents: List<Int> = emptyList(),
    palette: SavedPalette? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val original = remember { initial }
    var current by remember { mutableIntStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = {
            onCommit(current)
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SheetGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title gives way, not the buttons: a long one wraps beside them, where a
                // squeezed button would stack its label a letter per line.
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            onColorChange(original)
                            onDismiss()
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.ui_cancel), maxLines = 1, softWrap = false) }
                    TextButton(
                        onClick = {
                            onCommit(current)
                            onDismiss()
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(
                            NotesIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Text(
                            stringResource(R.string.ui_done),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                        )
                    }
                }
            }

            ColorPicker(
                initial = initial,
                onColorChange = {
                    current = it
                    onColorChange(it)
                },
                showAlpha = showAlpha,
                presets = presets,
                recents = recents,
                palette = palette,
                horizontalPadding = SheetGutter,
            )
        }
    }
}

private val SheetGutter = 24.dp

/**
 * A labelled row that shows a colour and opens the picker when tapped.
 *
 * Used wherever a colour is one of several settings, so the picker itself never has to know what it
 * is editing.
 */
@Composable
fun ColorSettingRow(
    label: String,
    color: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting ?: ColorCodec.toHex(color),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ColorSwatch(color = color, selected = false, onClick = onClick, size = 40.dp)
    }
}
