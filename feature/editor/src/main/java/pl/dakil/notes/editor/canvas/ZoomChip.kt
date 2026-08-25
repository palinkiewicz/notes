package pl.dakil.notes.editor.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import pl.dakil.notes.editor.R
import pl.dakil.notes.ui.icons.NotesIcons
import kotlin.math.roundToInt

/**
 * The zoom indicator: a pill over the top of the page showing the current scale, with a lock and a
 * menu of scales to jump to.
 *
 * ### Why it comes and goes
 *
 * A permanent readout would be one more thing between the reader and the paper, and the scale is
 * only interesting while it is changing. So the chip fades in on a zoom and back out [LINGER_MS]
 * after the last one — except when the zoom is locked, where it stays put, because a lock that is
 * in force but invisible is a bug report waiting to happen ("pinching stopped working").
 *
 * ### Why zoom is never read during composition here
 *
 * `transform.zoom` changes at input pace, so reading it in this function's body would recompose the
 * chip on every frame of a pinch — the exact cost the editor is built to avoid. Two things keep
 * that off the composition path: the percentage is read inside [ZoomPercentLabel], whose whole body
 * is one `Text`, and the fade is driven from a `snapshotFlow` over
 * [SheetTransform.zoomEpoch] inside a `LaunchedEffect`, which observes without recomposing anything
 * at all.
 */
@Composable
fun ZoomChip(
    transform: SheetTransform,
    presets: List<Int>,
    pageHeightPx: Float,
    onAddPreset: (Int) -> Unit,
    onRemovePreset: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var customDialogOpen by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(false) }
    val locked = transform.zoomLocked

    // `collectLatest` is the timer: each new zoom cancels the pending hide and starts the wait
    // again, so a slow pinch does not blink the chip away between two of its frames.
    LaunchedEffect(transform) {
        snapshotFlow { transform.zoomEpoch to transform.zoomLocked }
            // The value on arrival is the state the note opened in, not something the user did.
            .drop(1)
            .collectLatest {
                lingering = true
                delay(LINGER_MS)
                lingering = false
            }
    }

    AnimatedVisibility(
        visible = lingering || locked || menuOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { transform.zoomLocked = !locked },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (locked) NotesIcons.Lock else NotesIcons.LockOpen,
                        contentDescription = stringResource(
                            if (locked) R.string.editor_zoom_unlock else R.string.editor_zoom_lock,
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = if (locked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                ZoomPercentLabel(
                    percent = { transform.percent() },
                    onClick = { customDialogOpen = true },
                )

                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = NotesIcons.ExpandMore,
                            contentDescription = stringResource(R.string.editor_zoom_set),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ZoomMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        transform = transform,
                        presets = presets,
                        pageHeightPx = pageHeightPx,
                        onAddPreset = onAddPreset,
                        onRemovePreset = onRemovePreset,
                    )
                }
            }
        }
    }

    if (customDialogOpen) {
        ZoomCustomDialog(
            initialPercent = transform.percent(),
            onDismiss = { customDialogOpen = false },
            onConfirm = { percent, saveAsPreset ->
                customDialogOpen = false
                transform.zoomTo(percent / 100f)
                if (saveAsPreset) onAddPreset(percent)
            },
        )
    }
}

/**
 * The scale, as a whole percentage, and a tap target for typing an exact one.
 *
 * Its own composable purely so that the zoom is read here and not in [ZoomChip]: this is then the
 * only node that recomposes while the fingers move. The width is fixed, so the two buttons either
 * side do not shuffle as the number gains and loses a digit. `indication = null` because a chip this
 * small showing a ripple reads as a button being pressed, not a label being tapped.
 */
@Composable
private fun ZoomPercentLabel(percent: () -> Int, onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.editor_zoom_percent_x, percent()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(min = 40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/**
 * Typing an exact zoom, with the option to keep it as a preset in the same step.
 *
 * The checkbox does what [ZoomMenu]'s "make preset" entry does, folded into confirmation instead of
 * a second trip through the menu — offering it unconditionally, since a value typed by hand is by
 * definition not yet on the list.
 */
@Composable
private fun ZoomCustomDialog(
    initialPercent: Int,
    onDismiss: () -> Unit,
    onConfirm: (percent: Int, saveAsPreset: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(initialPercent.toString()) }
    var saveAsPreset by remember { mutableStateOf(false) }
    val parsed = text.toIntOrNull()?.takeIf { it in MIN_ZOOM_PERCENT..MAX_ZOOM_PERCENT }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_zoom_set_custom)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    suffix = { Text("%") },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { saveAsPreset = !saveAsPreset },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = saveAsPreset, onCheckedChange = { saveAsPreset = it })
                    Text(stringResource(R.string.editor_zoom_make_preset))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onConfirm(it, saveAsPreset) } },
            ) { Text(stringResource(R.string.editor_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

private val MIN_ZOOM_PERCENT = (SheetTransform.MIN_ZOOM * 100f).roundToInt()
private val MAX_ZOOM_PERCENT = (SheetTransform.MAX_ZOOM * 100f).roundToInt()

/**
 * The scales that can be jumped to.
 *
 * Fill width and fill height are computed from the window, so they are named rather than given a
 * number; everything below the divider is a fixed percentage, with 100% — true physical size — as
 * the one that is always there.
 */
@Composable
private fun ZoomMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    transform: SheetTransform,
    presets: List<Int>,
    pageHeightPx: Float,
    onAddPreset: (Int) -> Unit,
    onRemovePreset: (Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Reading the zoom inside the menu's content is safe: it is composed only while the menu is
        // open, which is not a moment anyone is pinching.
        val current = transform.percent()
        // 100% is offered unconditionally, so it counts as taken for the purposes of pinning.
        val fixed = (listOf(100) + presets).distinct().sorted()

        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_zoom_fill_width)) },
            leadingIcon = { Icon(NotesIcons.FillWidth, contentDescription = null) },
            onClick = {
                transform.zoomTo(transform.fitWidthZoom())
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_zoom_fill_height)) },
            leadingIcon = { Icon(NotesIcons.FillHeight, contentDescription = null) },
            onClick = {
                transform.zoomTo(transform.fitHeightZoom(pageHeightPx))
                onDismiss()
            },
        )
        HorizontalDivider()

        for (percent in fixed) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_zoom_percent_x, percent)) },
                leadingIcon = {
                    // An empty slot on the others, rather than no slot: the labels have to line up
                    // or the ticked entry looks like it belongs to a different list.
                    if (percent == current) {
                        Icon(
                            imageVector = NotesIcons.Check,
                            contentDescription = stringResource(R.string.editor_zoom_current),
                        )
                    } else {
                        Box(Modifier.size(24.dp))
                    }
                },
                trailingIcon = if (percent == 100) null else {
                    {
                        IconButton(onClick = { onRemovePreset(percent) }) {
                            Icon(
                                imageVector = NotesIcons.Close,
                                contentDescription = stringResource(R.string.editor_zoom_forget_x, percent),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                onClick = {
                    transform.zoomTo(percent / 100f)
                    onDismiss()
                },
            )
        }

        // Offered only where it would do something. At a scale that is already on the list the
        // entry would be a no-op that silently changes nothing when tapped.
        if (current !in fixed) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_zoom_make_preset)) },
                leadingIcon = { Icon(NotesIcons.Add, contentDescription = null) },
                onClick = {
                    onAddPreset(current)
                    onDismiss()
                },
            )
        }
    }
}

private fun SheetTransform.percent(): Int = (zoom * 100f).roundToInt()

/** Long enough to read the number after a pinch settles, short enough not to sit in the way. */
private const val LINGER_MS = 3_000L
