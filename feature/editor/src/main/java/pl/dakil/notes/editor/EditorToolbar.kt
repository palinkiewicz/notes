package pl.dakil.notes.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.util.Locale
import pl.dakil.notes.editor.R
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.ui.color.ColorSwatch
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The tool palette: four things a touch can be — select, text, draw, erase — and the two settings
 * of whichever one is drawing or erasing.
 *
 * Each button *shows* what it holds: the pen button wears the pen you last drew with, the colour
 * button is the colour, the size button is the number. The variants live one tap deeper, on the
 * button that already represents them — pressing the pen when the pen is already selected offers
 * the other pens rather than switching to something. Nothing is ever more than two taps away, and
 * the bar reads as state rather than as a menu.
 */
@Composable
fun EditorToolbar(
    state: EditorUiState,
    openPopup: ToolPopup?,
    onPopupChange: (ToolPopup?) -> Unit,
    onSelectTool: (ToolId) -> Unit,
    onSelectTextTool: () -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onToggleFingerDrawing: (Boolean) -> Unit,
    onToggleRuler: (Boolean) -> Unit,
    onOpenColorPicker: (ToolId) -> Unit,
    /**
     * Whether the formatting bar is stacked above this one.
     *
     * Two app bars at their full height take a third of a phone screen and leave most of it empty,
     * so the pair is drawn tighter than either would be alone. The buttons do not change size —
     * only the air around them does.
     */
    compact: Boolean = false,
) {
    var optionsFor by remember { mutableStateOf<ToolId?>(null) }

    BottomAppBar(modifier = if (compact) Modifier.height(CompactBarHeight) else Modifier) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolControls(
                state = state,
                placement = PopupPlacement.ABOVE,
                openPopup = openPopup,
                onPopupChange = onPopupChange,
                onSelectTool = onSelectTool,
                onSelectTextTool = onSelectTextTool,
                onUpdateTool = onUpdateTool,
                onOpenColorPicker = onOpenColorPicker,
                onOpenOptions = { optionsFor = it },
            )
        }
        Box(Modifier.width(8.dp))
        // The two switches, held out of the scrolling row because neither is a tool: they change
        // what the surface does, not what the pen is, and both have to stay reachable. Without the
        // first, a stylus-less phone cannot draw at all.
        FilledIconToggleButton(
            checked = state.inputConfig.fingerDrawingEnabled,
            onCheckedChange = {
                onPopupChange(null)
                onToggleFingerDrawing(it)
            },
        ) {
            Icon(
                imageVector = NotesIcons.FingerDraw,
                contentDescription = stringResource(R.string.editor_finger_drawing),
            )
        }
        RulerToggle(checked = state.rulerEnabled, onCheckedChange = {
            onPopupChange(null)
            onToggleRuler(it)
        })
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

/** The tablet form: the same buttons docked vertically so the page keeps its full height. */
@Composable
fun EditorToolRail(
    state: EditorUiState,
    openPopup: ToolPopup?,
    onPopupChange: (ToolPopup?) -> Unit,
    onSelectTool: (ToolId) -> Unit,
    onSelectTextTool: () -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onToggleFingerDrawing: (Boolean) -> Unit,
    onToggleRuler: (Boolean) -> Unit,
    onOpenColorPicker: (ToolId) -> Unit,
) {
    var optionsFor by remember { mutableStateOf<ToolId?>(null) }

    NavigationRail(Modifier.verticalScroll(rememberScrollState())) {
        ToolControls(
            state = state,
            placement = PopupPlacement.END,
            openPopup = openPopup,
            onPopupChange = onPopupChange,
            onSelectTool = onSelectTool,
            onSelectTextTool = onSelectTextTool,
            onUpdateTool = onUpdateTool,
            onOpenColorPicker = onOpenColorPicker,
            onOpenOptions = { optionsFor = it },
        )
        FilledIconToggleButton(
            checked = state.inputConfig.fingerDrawingEnabled,
            onCheckedChange = {
                onPopupChange(null)
                onToggleFingerDrawing(it)
            },
        ) {
            Icon(
                imageVector = NotesIcons.FingerDraw,
                contentDescription = stringResource(R.string.editor_finger_drawing),
            )
        }
        RulerToggle(checked = state.rulerEnabled, onCheckedChange = {
            onPopupChange(null)
            onToggleRuler(it)
        })
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

/**
 * The buttons themselves, in one place because the bar and the rail differ only in which way they
 * stack them and which side their popups open on.
 */
@Composable
private fun ToolControls(
    state: EditorUiState,
    placement: PopupPlacement,
    openPopup: ToolPopup?,
    onPopupChange: (ToolPopup?) -> Unit,
    onSelectTool: (ToolId) -> Unit,
    onSelectTextTool: () -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onOpenColorPicker: (ToolId) -> Unit,
    onOpenOptions: (ToolId) -> Unit,
) {
    val active = state.tool.tool
    val drawing = !state.textToolActive && active.isDrawing
    val erasing = !state.textToolActive && active.isEraser

    ToolBarButton(
        icon = NotesIcons.Lasso,
        label = stringResource(R.string.editor_tool_select),
        selected = !state.textToolActive && active == ToolId.LASSO,
        onClick = {
            onPopupChange(null)
            onSelectTool(ToolId.LASSO)
        },
    )

    ToolBarButton(
        icon = NotesIcons.TextBox,
        label = stringResource(R.string.editor_tool_text),
        selected = state.textToolActive,
        onClick = {
            onPopupChange(null)
            onSelectTextTool()
        },
    )

    // Pens and erasers work the same way: the button carries the last one used, and pressing it
    // again — once it is already the selected tool — offers the others.
    VariantButton(
        current = if (drawing) active else state.lastDrawingTool,
        selected = drawing,
        variants = DRAWING_TOOLS,
        popup = ToolPopup.TOOLS,
        placement = placement,
        openPopup = openPopup,
        onPopupChange = onPopupChange,
        onSelectTool = onSelectTool,
        onOpenOptions = onOpenOptions,
    )

    VariantButton(
        current = if (erasing) active else state.lastEraser,
        selected = erasing,
        variants = ERASER_TOOLS,
        popup = ToolPopup.ERASERS,
        placement = placement,
        openPopup = openPopup,
        onPopupChange = onPopupChange,
        onSelectTool = onSelectTool,
        onOpenOptions = onOpenOptions,
    )

    if (drawing) {
        ColorButton(
            state = state,
            placement = placement,
            open = openPopup == ToolPopup.COLOR,
            onOpenChange = { onPopupChange(if (it) ToolPopup.COLOR else null) },
            onUpdateTool = onUpdateTool,
            onOpenPicker = onOpenColorPicker,
        )
    }

    if (drawing || erasing) {
        SizeButton(
            spec = state.tool,
            placement = placement,
            open = openPopup == ToolPopup.SIZE,
            onOpenChange = { onPopupChange(if (it) ToolPopup.SIZE else null) },
            onUpdateTool = onUpdateTool,
            onOpenOptions = { onOpenOptions(state.tool.tool) },
        )
    }
}

/**
 * The straightedge switch.
 *
 * A toggle rather than a tool, and sitting outside the tool row on purpose: the ruler is on the
 * page alongside whatever the pen is doing, so it has to be reachable — and readable as on or off —
 * without disturbing the pen you are holding.
 */
@Composable
private fun RulerToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    FilledIconToggleButton(checked = checked, onCheckedChange = onCheckedChange) {
        Icon(NotesIcons.Ruler, contentDescription = stringResource(R.string.editor_ruler))
    }
}

/**
 * Which selector is open, if any.
 *
 * Hoisted out of the bar because closing one is not the bar's business alone: a tap on the sheet or
 * the app bar closes it too, and switching between two of them has to be a single tap.
 */
enum class ToolPopup { TOOLS, ERASERS, COLOR, SIZE }

/**
 * Closes an open selector on the next press anywhere in [this], without taking that press.
 *
 * Watching the initial pass and consuming nothing is the whole trick: the same tap that dismisses
 * the popup still lands on whatever it hit, so the button under it needs one press rather than two
 * and a stroke started on the sheet is drawn rather than swallowed. It stays mounted whether or not
 * a popup is open — a pointer node appearing and vanishing under an in-flight stroke is exactly the
 * kind of thing the ink overlay cannot afford.
 */
@Composable
fun Modifier.dismissToolPopupOnPress(onDismiss: () -> Unit): Modifier {
    val dismiss by rememberUpdatedState(onDismiss)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) dismiss()
            }
        }
    }
}

/** Which side of the button the popup opens on: above the bottom bar, beside the rail. */
internal enum class PopupPlacement { ABOVE, END }

/**
 * A tool button: an icon, contained while its tool is the selected one.
 *
 * Not an `IconToggleButton` because these carry a long press as well, and Material's icon buttons
 * take a click and nothing else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolBarButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // Where a variant popup hangs, so it is anchored on the button it belongs to.
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/**
 * A button standing for a family of tools — the pens, or the erasers.
 *
 * The first press selects; a press while already selected opens the family. That ordering is what
 * keeps the common case at one tap: switching to the pen you were already using should never cost
 * a trip through a menu.
 */
@Composable
private fun VariantButton(
    current: ToolId,
    selected: Boolean,
    variants: List<ToolEntry>,
    popup: ToolPopup,
    placement: PopupPlacement,
    openPopup: ToolPopup?,
    onPopupChange: (ToolPopup?) -> Unit,
    onSelectTool: (ToolId) -> Unit,
    onOpenOptions: (ToolId) -> Unit,
) {
    val open = openPopup == popup
    val entry = variants.firstOrNull { it.tool == current } ?: variants.first()

    ToolBarButton(
        icon = entry.icon,
        label = stringResource(entry.label),
        selected = selected,
        onClick = {
            if (selected) {
                onPopupChange(if (open) null else popup)
            } else {
                onPopupChange(null)
                onSelectTool(current)
            }
        },
        onLongClick = { onOpenOptions(current) },
    ) {
        if (open) {
            InlineSelector(placement = placement, onDismiss = { onPopupChange(null) }) {
                for (variant in variants) {
                    SelectorItem(
                        icon = variant.icon,
                        label = stringResource(variant.label),
                        selected = variant.tool == current,
                        onClick = {
                            onPopupChange(null)
                            onSelectTool(variant.tool)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The ink colour, shown as the colour.
 *
 * A plain filled circle with a hairline ring: the swatch has to read as the ink itself, and any
 * heavier outline reads as a control drawn around a colour instead.
 */
@Composable
private fun ColorButton(
    state: EditorUiState,
    placement: PopupPlacement,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onOpenPicker: (ToolId) -> Unit,
) {
    val spec = state.tool

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { onOpenChange(!open) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(26.dp),
            shape = CircleShape,
            color = Color(spec.effectiveColor),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = {},
        )

        if (open) {
            InlineSelector(placement = placement, onDismiss = { onOpenChange(false) }) {
                // Presets keep the tool's own opacity; a recent colour carries its own alpha,
                // because that is what was mixed and saved.
                for (color in ColorCodec.INK_PRESETS) {
                    SwatchItem(
                        color = color,
                        selected = (color and 0x00FFFFFF) == (spec.color and 0x00FFFFFF),
                        onClick = {
                            val alpha = ColorCodec.alpha(spec.color)
                            onUpdateTool(spec.copy(color = ColorCodec.withAlpha(color, alpha)))
                            onOpenChange(false)
                        },
                    )
                }
                for (color in state.recentColors) {
                    SwatchItem(
                        color = color,
                        selected = (color and 0x00FFFFFF) == (spec.color and 0x00FFFFFF),
                        onClick = {
                            onUpdateTool(spec.copy(color = color))
                            onOpenChange(false)
                        },
                    )
                }
                SelectorItem(
                    icon = NotesIcons.Palette,
                    label = stringResource(R.string.editor_custom_colour),
                    selected = false,
                    onClick = {
                        onOpenChange(false)
                        onOpenPicker(spec.tool)
                    },
                )
            }
        }
    }
}

/**
 * The stroke width — or, for the erasers, the eraser radius — as the number it is.
 *
 * A dot scaled to the width could only ever suggest an ordering; the number is the setting, and it
 * is the same number the slider under it moves.
 */
@Composable
private fun SizeButton(
    spec: ToolSpec,
    placement: PopupPlacement,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onUpdateTool: (ToolSpec) -> Unit,
    onOpenOptions: () -> Unit,
) {
    val erasing = spec.tool.isEraser
    val value = if (erasing) spec.eraserRadius else spec.width
    val range = if (erasing) ERASER_RANGE else WIDTH_RANGE

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { onOpenChange(!open) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatSize(value),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )

        if (open) {
            InlineSelector(placement = placement, onDismiss = { onOpenChange(false) }) {
                Text(
                    text = formatSize(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(40.dp).padding(start = 8.dp),
                )
                Slider(
                    value = value,
                    onValueChange = {
                        onUpdateTool(
                            if (erasing) spec.copy(eraserRadius = it) else spec.copy(width = it)
                        )
                    },
                    valueRange = range,
                    modifier = Modifier.width(196.dp).padding(horizontal = 12.dp),
                )
                // The one visible way to the deep settings; long-pressing a tool is the other, and
                // a control nobody can find is a control that does not exist.
                SelectorItem(
                    icon = NotesIcons.More,
                    label = stringResource(R.string.editor_more_options),
                    selected = false,
                    onClick = {
                        onOpenChange(false)
                        onOpenOptions()
                    },
                )
            }
        }
    }
}

/** Three significant digits and one decimal: `2.0`, `16.5`, `40.0` — a stable width to lay out. */
private fun formatSize(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

/**
 * The popup itself: one row, one row high, floating clear of the bar that opened it.
 *
 * Deliberately *not* focusable. A focusable popup takes the whole window's touch input, so the tap
 * that dismisses it is eaten and everything behind it needs pressing twice; the screen it hovers
 * over stays live instead, and [dismissToolPopupOnPress] does the closing. Back is handled by the
 * caller for the same reason.
 */
@Composable
internal fun InlineSelector(
    placement: PopupPlacement,
    onDismiss: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(placement, gap) { InlineSelectorPosition(placement, gap) }

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(56.dp)
                    // More choices than fit a narrow phone is normal; scrolling the popup beats
                    // dropping any of them from it.
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Centres the row on its button and keeps it inside the window, whichever edge the button is on. */
private class InlineSelectorPosition(
    private val placement: PopupPlacement,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = when (placement) {
        PopupPlacement.ABOVE -> IntOffset(
            x = clamp(
                anchorBounds.center.x - popupContentSize.width / 2,
                windowSize.width - popupContentSize.width - gap,
            ),
            y = anchorBounds.top - popupContentSize.height - gap,
        )

        PopupPlacement.END -> IntOffset(
            x = if (layoutDirection == LayoutDirection.Rtl) {
                anchorBounds.left - popupContentSize.width - gap
            } else {
                anchorBounds.right + gap
            },
            y = clamp(
                anchorBounds.center.y - popupContentSize.height / 2,
                windowSize.height - popupContentSize.height - gap,
            ),
        )
    }

    /** A popup wider than the window still has to start somewhere: at the near edge, not off it. */
    private fun clamp(wanted: Int, max: Int): Int = wanted.coerceIn(gap, maxOf(gap, max))
}

@Composable
internal fun SelectorItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One colour in the popup, drawn exactly like the button that opened it.
 *
 * Selection is the ring and a little more size rather than a tick over the colour: at this size the
 * swatch has room to be a colour or to be a control, and the colour is the thing being chosen.
 */
@Composable
private fun SwatchItem(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(if (selected) 32.dp else 28.dp),
            shape = CircleShape,
            color = Color(color),
            border = BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
            content = {},
        )
    }
}

private val WIDTH_RANGE = 0.5f..40f
private val ERASER_RANGE = 2f..48f

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
                text = stringResource(
                    TOOL_ENTRIES.firstOrNull { it.tool == spec.tool }?.label ?: R.string.editor_tool,
                ),
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

                LabelledSlider(stringResource(R.string.editor_width), spec.width, WIDTH_RANGE) {
                    onSpecChange(spec.copy(width = it))
                }
                LabelledSlider(stringResource(R.string.editor_opacity), spec.opacity, 0.05f..1f) {
                    onSpecChange(spec.copy(opacity = it))
                }
                LabelledSlider(stringResource(R.string.editor_smoothing), spec.smoothing, 0f..1f) {
                    onSpecChange(spec.copy(smoothing = it))
                }
                LabelledSlider(
                    stringResource(R.string.editor_pressure_sensitivity),
                    spec.pressureInfluence,
                    0f..1f,
                ) {
                    onSpecChange(spec.copy(pressureInfluence = it))
                }
                LabelledSlider(
                    stringResource(R.string.editor_speed_thinning),
                    spec.speedInfluence,
                    0f..1f,
                ) {
                    onSpecChange(spec.copy(speedInfluence = it))
                }
            } else {
                LabelledSlider(
                    stringResource(R.string.editor_eraser_size),
                    spec.eraserRadius,
                    ERASER_RANGE,
                ) {
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
                // Both separators, like `MeasurementUnit.format`: a comma is what most of Europe
                // gets back from `String.format`, and trimming only the point leaves it stranded.
                text = String.format(Locale.getDefault(), "%.2f", value)
                    .trimEnd('0')
                    .trimEnd('.', ','),
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
        Text(stringResource(R.string.editor_colour), style = MaterialTheme.typography.labelLarge)
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
                    contentDescription = stringResource(R.string.editor_custom_colour),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private data class ToolEntry(
    val tool: ToolId,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

private val DRAWING_TOOLS = listOf(
    ToolEntry(ToolId.PEN, R.string.editor_tool_pen, NotesIcons.Pen),
    ToolEntry(ToolId.FOUNTAIN_PEN, R.string.editor_tool_fountain, NotesIcons.FountainPen),
    ToolEntry(ToolId.PENCIL, R.string.editor_tool_pencil, NotesIcons.Pencil),
    ToolEntry(ToolId.HIGHLIGHTER, R.string.editor_tool_highlighter, NotesIcons.Highlighter),
)

private val ERASER_TOOLS = listOf(
    ToolEntry(ToolId.ERASER_STROKE, R.string.editor_tool_erase_stroke, NotesIcons.EraserStroke),
    ToolEntry(ToolId.ERASER_POINT, R.string.editor_tool_erase_area, NotesIcons.EraserPoint),
)

private val TOOL_ENTRIES = DRAWING_TOOLS + ERASER_TOOLS +
    ToolEntry(ToolId.LASSO, R.string.editor_tool_select, NotesIcons.Lasso)

/** The height of the tool bar when it is the lower of two. See [EditorToolbar]'s `compact`. */
private val CompactBarHeight = 56.dp
