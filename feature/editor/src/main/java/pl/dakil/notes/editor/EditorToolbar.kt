package pl.dakil.notes.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.ui.color.ColorSwatch
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The tool palette.
 *
 * The casual/power split here is by *placement*, not by a mode switch: the bar shows only the
 * tools, and every deep control — width, opacity, smoothing, pressure response — lives one
 * long-press away in a `ModalBottomSheet`. Nothing is hidden behind a "pro" toggle; it is simply
 * one level down.
 */
@Composable
fun EditorToolbar(
    state: EditorUiState,
    onSelectTool: (ToolId) -> Unit,
    onSelectTextTool: () -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onToggleFingerDrawing: (Boolean) -> Unit,
    onOpenColorPicker: (ToolId) -> Unit,
    onAddPage: () -> Unit,
) {
    var optionsFor by remember { mutableStateOf<ToolId?>(null) }

    BottomAppBar {
        // The tools scroll and the finger-draw toggle does not. Eight 48dp targets plus the toggle
        // overflow a phone in portrait, and the toggle is the one control that must never be the
        // thing pushed off the edge: without it, a stylus-less phone cannot draw at all.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextToolButton(selected = state.textToolActive, onClick = onSelectTextTool)
            for (entry in TOOL_ENTRIES) {
                ToolButton(
                    entry = entry,
                    selected = !state.textToolActive && state.tool.tool == entry.tool,
                    onClick = { onSelectTool(entry.tool) },
                    onLongClick = { optionsFor = entry.tool },
                )
            }
        }
        Box(Modifier.width(8.dp))
        // Adding a page is a one-tap action here rather than two taps into page setup: you reach
        // for it at the bottom of a page, mid-thought, and that is not a moment to go looking
        // through a settings sheet. Removing one stays in page setup — it is rarer, and it is the
        // direction that wants a moment's deliberation.
        IconButton(onClick = onAddPage) {
            Icon(NotesIcons.AddPage, contentDescription = "Add page")
        }
        FilledIconToggleButton(
            checked = state.inputConfig.fingerDrawingEnabled,
            onCheckedChange = onToggleFingerDrawing,
        ) {
            Icon(NotesIcons.FingerDraw, contentDescription = "Draw with finger")
        }
        // Reaching tool options by long-press alone is undiscoverable, and on a phone it is where
        // colour and width live.
        IconButton(onClick = { optionsFor = state.tool.tool }) {
            Icon(NotesIcons.More, contentDescription = "Tool options")
        }
    }

    optionsFor?.let { tool ->
        ToolOptionsSheet(
            spec = state.toolPresets.firstOrNull { it.tool == tool } ?: ToolSpec.defaultFor(tool),
            recentColors = state.recentColors,
            onSpecChange = onUpdateTool,
            onOpenPicker = {
                optionsFor = null
                onOpenColorPicker(tool)
            },
            onDismiss = { optionsFor = null },
        )
    }
}

/** The tablet form: the same tools docked vertically so the page keeps its full height. */
@Composable
fun EditorToolRail(
    state: EditorUiState,
    onSelectTool: (ToolId) -> Unit,
    onSelectTextTool: () -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onToggleFingerDrawing: (Boolean) -> Unit,
    onOpenColorPicker: (ToolId) -> Unit,
    onAddPage: () -> Unit,
) {
    var optionsFor by remember { mutableStateOf<ToolId?>(null) }

    NavigationRail(Modifier.verticalScroll(rememberScrollState())) {
        NavigationRailItem(
            selected = state.textToolActive,
            onClick = onSelectTextTool,
            icon = { Icon(NotesIcons.TextBox, contentDescription = "Text") },
            label = { Text("Text") },
        )
        for (entry in TOOL_ENTRIES) {
            NavigationRailItem(
                selected = !state.textToolActive && state.tool.tool == entry.tool,
                onClick = { onSelectTool(entry.tool) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
            )
        }
        IconButton(onClick = { optionsFor = state.tool.tool }) {
            Icon(NotesIcons.More, contentDescription = "Tool options")
        }
        IconButton(onClick = onAddPage) {
            Icon(NotesIcons.AddPage, contentDescription = "Add page")
        }
        FilledIconToggleButton(
            checked = state.inputConfig.fingerDrawingEnabled,
            onCheckedChange = onToggleFingerDrawing,
        ) {
            Icon(NotesIcons.FingerDraw, contentDescription = "Draw with finger")
        }
    }

    optionsFor?.let { tool ->
        ToolOptionsSheet(
            spec = state.toolPresets.firstOrNull { it.tool == tool } ?: ToolSpec.defaultFor(tool),
            recentColors = state.recentColors,
            onSpecChange = onUpdateTool,
            onOpenPicker = {
                optionsFor = null
                onOpenColorPicker(tool)
            },
            onDismiss = { optionsFor = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolOptionsSheet(
    spec: ToolSpec,
    recentColors: List<Int>,
    onSpecChange: (ToolSpec) -> Unit,
    onOpenPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                // Colour plus five sliders does not fit a phone in landscape, and a control you
                // cannot reach is the same as one that does not exist.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = TOOL_ENTRIES.firstOrNull { it.tool == spec.tool }?.label ?: "Tool",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (spec.tool.isDrawing) {
                ColorSection(
                    selected = spec.color,
                    recents = recentColors,
                    onSelect = { onSpecChange(spec.copy(color = it)) },
                    onOpenPicker = onOpenPicker,
                )

                LabelledSlider("Width", spec.width, 0.5f..40f) {
                    onSpecChange(spec.copy(width = it))
                }
                LabelledSlider("Opacity", spec.opacity, 0.05f..1f) {
                    onSpecChange(spec.copy(opacity = it))
                }
                LabelledSlider("Smoothing", spec.smoothing, 0f..1f) {
                    onSpecChange(spec.copy(smoothing = it))
                }
                LabelledSlider("Pressure sensitivity", spec.pressureInfluence, 0f..1f) {
                    onSpecChange(spec.copy(pressureInfluence = it))
                }
                LabelledSlider("Speed thinning", spec.speedInfluence, 0f..1f) {
                    onSpecChange(spec.copy(speedInfluence = it))
                }
            } else {
                LabelledSlider("Eraser size", spec.eraserRadius, 2f..48f) {
                    onSpecChange(spec.copy(eraserRadius = it))
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = String.format("%.2f", value).trimEnd('0').trimEnd('.'),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * Pen colour: presets, the user's recent mixes, and a full picker.
 *
 * The presets are ink colours, not Material scheme colours — pen colour is document content. It has
 * to mean the same thing on every device and still be that colour when the note is reopened years
 * later, neither of which is true of a dynamic palette.
 */
@Composable
private fun ColorSection(
    selected: Int,
    recents: List<Int>,
    onSelect: (Int) -> Unit,
    onOpenPicker: () -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (color in ColorCodec.INK_PRESETS) {
                ColorSwatch(
                    color = color,
                    selected = (color and 0x00FFFFFF) == (selected and 0x00FFFFFF),
                    onClick = { onSelect(ColorCodec.withAlpha(color, ColorCodec.alpha(selected))) },
                )
            }
            for (color in recents) {
                ColorSwatch(
                    color = color,
                    selected = (color and 0x00FFFFFF) == (selected and 0x00FFFFFF),
                    onClick = { onSelect(color) },
                )
            }
            // Opens the full picker; shows the current colour so it doubles as the state readout.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onOpenPicker),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NotesIcons.Palette,
                    contentDescription = "Custom colour",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private data class ToolEntry(val tool: ToolId, val label: String, val icon: ImageVector)

private val TOOL_ENTRIES = listOf(
    ToolEntry(ToolId.PEN, "Pen", NotesIcons.Pen),
    ToolEntry(ToolId.FOUNTAIN_PEN, "Fountain", NotesIcons.FountainPen),
    ToolEntry(ToolId.PENCIL, "Pencil", NotesIcons.Pencil),
    ToolEntry(ToolId.HIGHLIGHTER, "Highlighter", NotesIcons.Highlighter),
    ToolEntry(ToolId.ERASER_STROKE, "Erase stroke", NotesIcons.EraserStroke),
    ToolEntry(ToolId.ERASER_POINT, "Erase area", NotesIcons.EraserPoint),
    ToolEntry(ToolId.LASSO, "Select", NotesIcons.Lasso),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    entry: ToolEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.label,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The text tool.
 *
 * Selecting it means a finger tap lands in the text rather than laying down ink. A stylus ignores
 * it entirely and always draws — which is the whole point of holding a pen.
 */
@Composable
private fun TextToolButton(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NotesIcons.TextBox,
            contentDescription = "Text",
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
