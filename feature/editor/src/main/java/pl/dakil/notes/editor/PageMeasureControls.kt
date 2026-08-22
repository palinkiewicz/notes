package pl.dakil.notes.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.R
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.model.MarginLink
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.link
import pl.dakil.notes.model.summary

/**
 * A settings row that reads out a length on the paper and opens an editor for it.
 *
 * The whole row is the target and the value is the only thing on the right, so the panel stays a
 * list of statements about the page rather than a wall of sliders. Editing happens in a dialog that
 * has to be confirmed: that is what makes a length something you *set* rather than something you
 * hunt for with a drag, and it collapses what used to be one document edit per slider sample into a
 * single undo step.
 */
@Composable
fun LengthRow(
    label: String,
    valuePt: Float,
    unit: MeasurementUnit,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
    supporting: String? = null,
    /** What to show instead of "0" when zero means something other than nothing. */
    zeroLabel: String? = null,
) {
    var editing by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = if (zeroLabel != null && valuePt <= 0f) zeroLabel else unit.format(valuePt),
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = NotesIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    if (editing) {
        LengthDialog(
            title = label,
            initial = valuePt,
            unit = unit,
            range = range,
            onDismiss = { editing = false },
            onConfirm = {
                editing = false
                onCommit(it)
            },
        )
    }
}

/**
 * Type it or drag it, then confirm.
 *
 * Both controls edit one number held here, so neither can drift from the other, and nothing reaches
 * the document until OK — Cancel really does leave the page as it was.
 */
@Composable
private fun LengthDialog(
    title: String,
    initial: Float,
    unit: MeasurementUnit,
    range: ClosedFloatingPointRange<Float>,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var points by remember { mutableStateOf(initial.coerceIn(range)) }
    var text by remember { mutableStateOf(unit.format(initial, withSuffix = false)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LengthTextField(
                    label = null,
                    text = text,
                    unit = unit,
                    onTextChange = { typed ->
                        text = typed
                        unit.parse(typed)?.let { points = it.coerceIn(range) }
                    },
                )
                Slider(
                    value = points,
                    onValueChange = {
                        points = it
                        text = unit.format(it, withSuffix = false)
                    },
                    valueRange = range,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(unit.snapPoints(points).coerceIn(range)) }) { Text(stringResource(R.string.editor_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

/** One numeric length field, with the unit as a suffix so the number never has to carry it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LengthTextField(
    label: String?,
    text: String,
    unit: MeasurementUnit,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = label?.let { { Text(it) } },
        suffix = { Text(unit.suffix) },
        singleLine = true,
        // Decimal, not Number: a margin of 1,5 cm has to be typeable.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = text.isNotBlank() && unit.parse(text) == null,
        modifier = modifier,
    )
}

/**
 * The four page margins on one row, written in CSS shorthand.
 *
 * Four numbers would be four rows for a value almost every note leaves symmetric, so the row shows
 * the shorthand and the dialog carries the detail.
 */
@Composable
fun MarginsRow(
    margins: PageMargins,
    unit: MeasurementUnit,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (PageMargins) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_margins), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.editor_margins_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = margins.summary(unit),
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = NotesIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    if (editing) {
        MarginsDialog(
            initial = margins,
            unit = unit,
            range = range,
            onDismiss = { editing = false },
            onConfirm = {
                editing = false
                onCommit(it)
            },
        )
    }
}

/**
 * Margins, with the sides tied together as loosely as the user wants.
 *
 * The anchor starts at whatever the current values already imply, so a note that has never been
 * touched opens on one field rather than four. Loosening it copies the value outwards instead of
 * resetting, which means switching anchor never silently changes the page.
 */
@Composable
private fun MarginsDialog(
    initial: PageMargins,
    unit: MeasurementUnit,
    range: ClosedFloatingPointRange<Float>,
    onDismiss: () -> Unit,
    onConfirm: (PageMargins) -> Unit,
) {
    var link by remember { mutableStateOf(initial.link()) }
    var top by remember { mutableStateOf(unit.format(initial.top, withSuffix = false)) }
    var right by remember { mutableStateOf(unit.format(initial.right, withSuffix = false)) }
    var bottom by remember { mutableStateOf(unit.format(initial.bottom, withSuffix = false)) }
    var left by remember { mutableStateOf(unit.format(initial.left, withSuffix = false)) }

    fun retie(next: MarginLink) {
        // Tightening has to pick a winner; top and left are the ones the fields are read from.
        when (next) {
            MarginLink.ALL -> {
                right = top; bottom = top; left = top
            }

            MarginLink.AXES -> {
                bottom = top; right = left
            }

            MarginLink.EACH -> Unit
        }
        link = next
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_margins)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        stringResource(R.string.editor_margins_link_all) to MarginLink.ALL,
                        stringResource(R.string.editor_margins_link_axes) to MarginLink.AXES,
                        stringResource(R.string.editor_margins_link_each) to MarginLink.EACH,
                    )
                    options.forEachIndexed { i, (label, mode) ->
                        SegmentedButton(
                            selected = link == mode,
                            onClick = { retie(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        ) { Text(label) }
                    }
                }

                // Top, right, bottom, left — the order the shorthand on the row is read in.
                when (link) {
                    MarginLink.ALL -> LengthTextField(
                        label = stringResource(R.string.editor_margins_all_sides),
                        text = top,
                        unit = unit,
                        onTextChange = { top = it; right = it; bottom = it; left = it },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    MarginLink.AXES -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LengthTextField(
                            label = stringResource(R.string.editor_margins_vertical),
                            text = top,
                            unit = unit,
                            onTextChange = { top = it; bottom = it },
                            modifier = Modifier.weight(1f),
                        )
                        LengthTextField(
                            label = stringResource(R.string.editor_margins_horizontal),
                            text = left,
                            unit = unit,
                            onTextChange = { left = it; right = it },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    MarginLink.EACH -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LengthTextField(
                                stringResource(R.string.editor_margin_top),
                                top, unit, { top = it }, Modifier.weight(1f),
                            )
                            LengthTextField(
                                stringResource(R.string.editor_margin_right),
                                right, unit, { right = it }, Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LengthTextField(
                                stringResource(R.string.editor_margin_bottom),
                                bottom, unit, { bottom = it }, Modifier.weight(1f),
                            )
                            LengthTextField(
                                stringResource(R.string.editor_margin_left),
                                left, unit, { left = it }, Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val parsed = listOf(top, right, bottom, left).map { unit.parse(it) }
            TextButton(
                enabled = parsed.all { it != null },
                onClick = {
                    val (t, r, b, l) = parsed.map { (it ?: 0f).coerceIn(range) }
                    onConfirm(PageMargins(left = l, top = t, right = r, bottom = b))
                },
            ) { Text(stringResource(R.string.editor_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}
