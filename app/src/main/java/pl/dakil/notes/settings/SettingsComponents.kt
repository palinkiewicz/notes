package pl.dakil.notes.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The settings screens' whole visual vocabulary.
 *
 * Every row is built on one [SettingRow], so a switch, a slider and a submenu line up down the
 * screen without any of them naming a padding. That is the entire reason this file exists: the
 * previous settings screen hand-rolled a `Column` per control and they drifted apart.
 *
 * No cards and no dividers — grouping is a [SectionHeader] and whitespace, which is what the
 * platform's own settings look like and what a list of unrelated toggles actually needs.
 */

private const val DISABLED_ALPHA = 0.38f

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * The base every other row is made of.
 *
 * [trailing] is sized to its own content rather than a fixed column, so a switch, a chevron and a
 * value readout can all sit at the same edge without agreeing on a width beforehand.
 */
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = if (summary != null || supporting != null) {
            {
                Column {
                    summary?.let { Text(it) }
                    supporting?.invoke()
                }
            }
        } else {
            null
        },
        leadingContent = leading,
        trailingContent = trailing,
    )
}

@Composable
fun SwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        // The whole row is the target, not just the switch: a toggle you have to hit at the far edge
        // of a tablet is a toggle people miss.
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

/**
 * A continuous value.
 *
 * Float rather than the whole numbers a settings slider usually carries, because half of these tune
 * ink maths — a smoothing multiplier and a minimum pressure are genuinely fractional. [format] owns
 * the readout so the row never has to know whether it is showing milliseconds or a multiplier, and
 * it is composable because every one of those readouts is a string resource.
 */
@Composable
fun SliderRow(
    title: String,
    summary: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: @Composable (Float) -> String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        supporting = {
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                enabled = enabled,
            )
        },
        trailing = { Text(format(value)) },
    )
}

/** A short closed set of choices, picked from a menu rather than spread across the row. */
@Composable
fun <T> SelectRow(
    title: String,
    summary: String? = null,
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = { expanded = true },
        trailing = {
            // Anchored inside the trailing slot so the menu opens against the right edge, under the
            // value it is replacing, rather than over the title.
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded && enabled,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSelect(value)
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
    )
}

/** A row that opens another screen, marked with a trailing chevron. */
@Composable
fun NavigationRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        onClick = onClick,
        leading = icon?.let { { Icon(it, contentDescription = null) } },
        trailing = {
            Icon(
                imageVector = NotesIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
