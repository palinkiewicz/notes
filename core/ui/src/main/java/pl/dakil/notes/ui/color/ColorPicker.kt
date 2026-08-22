package pl.dakil.notes.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.ui.R
import pl.dakil.notes.ui.icons.NotesIcons

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
) {
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
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { s, v ->
                saturation = s
                value = v
                applyHsv()
            },
        )

        HueSlider(hue = hue, onChange = { hue = it; applyHsv() })

        if (showAlpha) {
            AlphaSlider(
                color = ColorCodec.fromHsv(hue, saturation, value, 255),
                alpha = alpha,
                onChange = { alpha = it; applyHsv() },
            )
        }

        Row(
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
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (presets.isNotEmpty()) {
            SwatchRow(
                label = stringResource(R.string.ui_color_presets),
                colors = presets.toList(),
                selected = current,
                onSelect = ::adopt,
            )
        }

        if (recents.isNotEmpty()) {
            SwatchRow(
                label = stringResource(R.string.ui_color_recent),
                colors = recents,
                selected = current,
                onSelect = ::adopt,
            )
        }
    }
}

@Composable
private fun SwatchRow(
    label: String,
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (color in colors) {
                ColorSwatch(
                    color = color,
                    // Compare RGB only: a swatch is the same colour at any opacity.
                    selected = (color and 0x00FFFFFF) == (selected and 0x00FFFFFF),
                    onClick = { onSelect(color) },
                )
            }
        }
    }
}

/**
 * A single colour chip.
 *
 * The check mark flips between black and white by luminance, because the user can pick pure white
 * and pure black and a fixed mark would vanish on one of them.
 */
@Composable
fun ColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .checkerboard()
            .background(Color(color), CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
        modifier = Modifier
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
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }

    fun report(x: Float) {
        if (width <= 0f) return
        onChange((x / width).coerceIn(0f, 1f) * 360f)
    }

    Canvas(
        modifier = Modifier
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
private fun AlphaSlider(color: Int, alpha: Int, onChange: (Int) -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }

    fun report(x: Float) {
        if (width <= 0f) return
        onChange(((x / width).coerceIn(0f, 1f) * 255f).toInt())
    }

    Canvas(
        modifier = Modifier
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
