package pl.dakil.notes.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ColorPalette
import pl.dakil.notes.ui.R
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The user's saved colours and what can be done to them, for a picker that offers the palette.
 *
 * One object rather than four parameters because every picker in the app takes all of it or none:
 * a picker that could show the palette but not save to it would be the confusing case.
 */
@Immutable
class SavedPalette(
    val colors: List<Int>,
    val onSave: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
    /** Moves a saved colour by this many places, negative being earlier. */
    val onMove: (Int, Int) -> Unit,
) {
    val isFull: Boolean get() = colors.size >= ColorPalette.MAX_SAVED
}

/**
 * A hue / saturation-value / alpha colour picker.
 *
 * Custom rather than a library: Material 3 has no colour picker, and every third-party one is
 * either a whole design system or carries bitmap assets. This is a few hundred lines of `DrawScope`
 * gradients with no assets, no dependency and nothing to load.
 *
 * HSV — not the ARGB channels — is the picker's own source of truth for as long as it is open.
 * Deriving hue back from RGB on every frame makes the hue slider jump to zero the moment the user
 * drags saturation or value to the edge, because grey has no hue to recover.
 */
@Composable
fun ColorPicker(
    initial: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showAlpha: Boolean = true,
    presets: IntArray = ColorCodec.INK_PRESETS,
    recents: List<Int> = emptyList(),
    palette: SavedPalette? = null,
    /**
     * Horizontal padding for everything but the swatch rows, which run to the edges and carry it
     * inside instead — so a colour scrolls all the way to the edge of the sheet rather than
     * vanishing a gutter's width short of it.
     */
    horizontalPadding: Dp = 0.dp,
) {
    val padded = Modifier.padding(horizontal = horizontalPadding)
    val hsv = remember(initial) { ColorCodec.toHsv(initial) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableIntStateOf(ColorCodec.alpha(initial)) }

    // The hex field keeps its own text so a half-typed value is not rewritten under the caret.
    var hexText by remember { mutableStateOf(ColorCodec.toHex(initial, includeAlpha = showAlpha)) }

    val current = ColorCodec.fromHsv(hue, saturation, value, if (showAlpha) alpha else 255)

    fun emit(next: Int) {
        hexText = ColorCodec.toHex(next, includeAlpha = showAlpha)
        onColorChange(next)
    }

    fun applyHsv() = emit(ColorCodec.fromHsv(hue, saturation, value, if (showAlpha) alpha else 255))

    /** Adopting an existing colour has to move HSV wholesale, or the sliders drift out of sync. */
    fun adopt(argb: Int) {
        val next = ColorCodec.toHsv(argb)
        hue = next[0]
        saturation = next[1]
        value = next[2]
        if (showAlpha) alpha = ColorCodec.alpha(argb)
        emit(if (showAlpha) argb else ColorCodec.withAlpha(argb, 255))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        SaturationValueField(
            modifier = padded,
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { s, v ->
                saturation = s
                value = v
                applyHsv()
            },
        )

        HueSlider(modifier = padded, hue = hue, onChange = { hue = it; applyHsv() })

        if (showAlpha) {
            AlphaSlider(
                modifier = padded,
                color = ColorCodec.fromHsv(hue, saturation, value, 255),
                alpha = alpha,
                onChange = { alpha = it; applyHsv() },
            )
        }

        Row(
            modifier = padded,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ColorSwatch(color = current, selected = false, onClick = null, size = 48.dp)
            OutlinedTextField(
                value = hexText,
                onValueChange = { text ->
                    hexText = text
                    ColorCodec.parse(text)?.let { parsed ->
                        val next = ColorCodec.toHsv(parsed)
                        hue = next[0]
                        saturation = next[1]
                        value = next[2]
                        // A 6-digit hex means "keep my alpha", not "become opaque".
                        if (showAlpha && text.trim().removePrefix("#").length.let { it == 4 || it == 8 }) {
                            alpha = ColorCodec.alpha(parsed)
                        }
                        onColorChange(
                            ColorCodec.fromHsv(hue, saturation, value, if (showAlpha) alpha else 255)
                        )
                    }
                },
                singleLine = true,
                isError = ColorCodec.parse(hexText) == null,
                label = { Text(stringResource(R.string.ui_color_hex)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                modifier = Modifier.weight(1f),
            )
            if (palette != null) SaveToggle(current = current, palette = palette)
        }

        // Saved first: it is the row the user built, and the one they came back for.
        if (palette != null && palette.colors.isNotEmpty()) {
            SwatchRow(
                label = stringResource(R.string.ui_color_saved),
                colors = palette.colors,
                selected = current,
                onSelect = ::adopt,
                horizontalPadding = horizontalPadding,
                menu = { color, dismiss -> SavedColorMenuItems(color, palette, dismiss) },
            )
        }

        if (presets.isNotEmpty()) {
            SwatchRow(
                label = stringResource(R.string.ui_color_presets),
                colors = presets.toList(),
                selected = current,
                onSelect = ::adopt,
                horizontalPadding = horizontalPadding,
                menu = palette?.let { saveMenu(it) },
            )
        }

        if (recents.isNotEmpty()) {
            SwatchRow(
                label = stringResource(R.string.ui_color_recent),
                colors = recents,
                selected = current,
                onSelect = ::adopt,
                horizontalPadding = horizontalPadding,
                menu = palette?.let { saveMenu(it) },
            )
        }

        if (palette != null && palette.colors.isEmpty()) {
            // The first time, nothing on screen says the palette exists; this is the one hint.
            Text(
                text = stringResource(R.string.ui_color_saved_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = padded,
            )
        }
    }
}

/**
 * Saves or unsaves the colour being mixed — where the user is looking when they decide to keep it.
 *
 * Filled when the exact colour, alpha included, is already saved, so the same button reads as the
 * state and as the way to change it. On a full palette an unsaved colour cannot be added, and the
 * button says so rather than quietly dropping an older colour.
 */
@Composable
private fun SaveToggle(current: Int, palette: SavedPalette) {
    val saved = current in palette.colors
    val enabled = saved || !palette.isFull
    IconButton(
        onClick = { if (saved) palette.onRemove(current) else palette.onSave(current) },
        enabled = enabled,
    ) {
        Icon(
            imageVector = if (saved) NotesIcons.BookmarkFilled else NotesIcons.Bookmark,
            contentDescription = when {
                saved -> stringResource(R.string.ui_color_unsave)
                enabled -> stringResource(R.string.ui_color_save)
                else -> pluralStringResource(
                    R.plurals.ui_color_palette_full, ColorPalette.MAX_SAVED, ColorPalette.MAX_SAVED,
                )
            },
            tint = if (saved) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Long-press menu on a preset or a recent: the one thing to do with it is keep it. */
private fun saveMenu(palette: SavedPalette): @Composable (Int, () -> Unit) -> Unit = { color, dismiss ->
    SaveColorMenuItem(color, palette, dismiss)
}

/**
 * The "Save colour" entry of a swatch's long-press menu, for every picker that offers colours it
 * did not save: one wording for "already saved" and "palette full" wherever it is asked.
 */
@Composable
fun SaveColorMenuItem(color: Int, palette: SavedPalette, dismiss: () -> Unit) {
    val saved = color in palette.colors
    DropdownMenuItem(
        text = {
            Text(
                when {
                    saved -> stringResource(R.string.ui_color_already_saved)
                    palette.isFull -> pluralStringResource(
                        R.plurals.ui_color_palette_full, ColorPalette.MAX_SAVED, ColorPalette.MAX_SAVED,
                    )
                    else -> stringResource(R.string.ui_color_save)
                }
            )
        },
        leadingIcon = { Icon(NotesIcons.Bookmark, contentDescription = null) },
        enabled = !saved && !palette.isFull,
        onClick = {
            palette.onSave(color)
            dismiss()
        },
    )
}

/**
 * Long-press menu on a saved colour.
 *
 * Moving by menu rather than by drag: the row scrolls sideways, and a drag along it is a scroll
 * until proven otherwise. Two taps is slower than a drag, but it is never the wrong one.
 */
@Composable
fun SavedColorMenuItems(color: Int, palette: SavedPalette, dismiss: () -> Unit) {
    val index = palette.colors.indexOf(color)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ui_color_move_earlier)) },
        leadingIcon = { Icon(NotesIcons.MoveEarlier, contentDescription = null) },
        enabled = index > 0,
        onClick = { palette.onMove(color, -1); dismiss() },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ui_color_move_later)) },
        leadingIcon = { Icon(NotesIcons.MoveLater, contentDescription = null) },
        enabled = index in 0 until palette.colors.lastIndex,
        onClick = { palette.onMove(color, 1); dismiss() },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ui_color_remove)) },
        leadingIcon = { Icon(NotesIcons.Delete, contentDescription = null) },
        onClick = { palette.onRemove(color); dismiss() },
    )
}

@Composable
private fun SwatchRow(
    label: String,
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    horizontalPadding: Dp,
    menu: (@Composable (color: Int, dismiss: () -> Unit) -> Unit)? = null,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = horizontalPadding).padding(bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                // Inside the scroll, so it is the content that starts inset, not the viewport.
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (color in colors) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    ColorSwatch(
                        color = color,
                        // Compare RGB only: a swatch is the same colour at any opacity.
                        selected = (color and 0x00FFFFFF) == (selected and 0x00FFFFFF),
                        onClick = { onSelect(color) },
                        onLongClick = menu?.let { { menuOpen = true } },
                    )
                    if (menu != null) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            menu(color) { menuOpen = false }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single colour chip.
 *
 * The check mark flips between black and white by luminance, because the user can pick pure white
 * and pure black and a fixed mark would vanish on one of them.
 *
 * The fill is inset by half the ring's width, so its edge lies under the ring rather than on the
 * ring's outer edge. Two anti-aliased edges on the same pixels let the chequerboard's white show
 * through between them as a pale fringe.
 */
@Composable
fun ColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val ring = if (selected) 3.dp else 1.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        @OptIn(ExperimentalFoundationApi::class)
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            )
            .border(
                width = ring,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .padding(ring / 2)
            .clip(CircleShape)
            .checkerboard()
            .background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = NotesIcons.Check,
                contentDescription = null,
                tint = if (ColorCodec.luminance(color) > 0.55f) Color.Black else Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** The saturation/value square for the current hue. */
@Composable
private fun SaturationValueField(
    modifier: Modifier,
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    var boxSize by remember { mutableStateOf(Size.Zero) }

    fun report(position: Offset) {
        if (boxSize.width <= 0f || boxSize.height <= 0f) return
        onChange(
            (position.x / boxSize.width).coerceIn(0f, 1f),
            1f - (position.y / boxSize.height).coerceIn(0f, 1f),
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    report(change.position)
                }
            },
    ) {
        boxSize = size

        // White to fully saturated hue, left to right...
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.White, Color(ColorCodec.fromHsv(hue, 1f, 1f))),
            )
        )
        // ...then transparent to black, top to bottom. The two together are the HSV square.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        drawThumb(
            center = Offset(saturation * size.width, (1f - value) * size.height),
            fill = Color(ColorCodec.fromHsv(hue, saturation, value)),
        )
    }
}

@Composable
private fun HueSlider(modifier: Modifier, hue: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }

    fun report(x: Float) {
        if (width <= 0f) return
        onChange((x / width).coerceIn(0f, 1f) * 360f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(Unit) { detectTapGestures { report(it.x) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    report(change.position.x)
                }
            },
    ) {
        width = size.width
        drawRect(
            Brush.horizontalGradient(
                List(HUE_STOPS + 1) { i ->
                    Color(ColorCodec.fromHsv(i * 360f / HUE_STOPS, 1f, 1f))
                }
            )
        )
        drawThumb(
            center = Offset(hue / 360f * size.width, size.height / 2f),
            fill = Color(ColorCodec.fromHsv(hue, 1f, 1f)),
        )
    }
}

@Composable
private fun AlphaSlider(modifier: Modifier, color: Int, alpha: Int, onChange: (Int) -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }

    fun report(x: Float) {
        if (width <= 0f) return
        onChange(((x / width).coerceIn(0f, 1f) * 255f).toInt())
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .checkerboard()
            .pointerInput(Unit) { detectTapGestures { report(it.x) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    report(change.position.x)
                }
            },
    ) {
        width = size.width
        drawRect(
            Brush.horizontalGradient(
                listOf(Color(color).copy(alpha = 0f), Color(color).copy(alpha = 1f)),
            )
        )
        drawThumb(
            center = Offset(alpha / 255f * size.width, size.height / 2f),
            fill = Color(ColorCodec.withAlpha(color, alpha)),
        )
    }
}

private fun DrawScope.drawThumb(center: Offset, fill: Color) {
    val radius = 11.dp.toPx()
    drawCircle(Color.White, radius, center, style = Stroke(width = 3.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.35f), radius + 1.5f.dp.toPx(), center, style = Stroke(1.dp.toPx()))
    drawCircle(fill, radius - 1.5f.dp.toPx(), center)
}

/**
 * The grey chequerboard that shows through a translucent colour.
 *
 * Drawn procedurally rather than tiled from a bitmap: it costs nothing in the APK and stays crisp
 * at any size. Placed before `background` in the chain so the colour composites over it.
 */
private fun Modifier.checkerboard(cell: androidx.compose.ui.unit.Dp = 7.dp): Modifier =
    this.drawBehind {
        val step = cell.toPx()
        if (step <= 0f) return@drawBehind
        drawRect(CHECKER_LIGHT)
        val columns = ceil(size.width / step).toInt()
        val rows = ceil(size.height / step).toInt()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if ((row + column) and 1 == 0) continue
                val left = column * step
                val top = row * step
                drawRect(
                    color = CHECKER_DARK,
                    topLeft = Offset(left, top),
                    size = Size(
                        minOf(step, size.width - left),
                        minOf(step, size.height - top),
                    ),
                )
            }
        }
    }

private val CHECKER_LIGHT = Color(0xFFFFFFFF)
private val CHECKER_DARK = Color(0xFFD8D8D8)

private const val HUE_STOPS = 6
