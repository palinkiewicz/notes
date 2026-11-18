package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import pl.dakil.notes.ui.designsystem.AppMenu
import pl.dakil.notes.ui.designsystem.AppMenuChoiceItem
import pl.dakil.notes.ui.designsystem.AppMenuGroup
import pl.dakil.notes.ui.designsystem.AppSlider
import pl.dakil.notes.ui.designsystem.BackButton
import pl.dakil.notes.ui.designsystem.CollapsingScaffold
import pl.dakil.notes.ui.designsystem.ConnectedToggleRow
import pl.dakil.notes.ui.designsystem.PageColumn
import pl.dakil.notes.ui.designsystem.RollingText
import pl.dakil.notes.ui.designsystem.SegmentedGroup
import pl.dakil.notes.ui.designsystem.ShapedIcon
import pl.dakil.notes.ui.designsystem.ToggleOption
import pl.dakil.notes.ui.designsystem.checkableItemColors
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.designsystem.segmentedInnerShapes
import pl.dakil.notes.ui.designsystem.segmentedItemColors
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.Spacing
import pl.dakil.notes.ui.theme.tabular

/**
 * The settings screens' whole visual vocabulary.
 *
 * Every row is a segmented tile built on one [SettingRow], so a switch, a slider and a submenu line
 * up down the screen without any of them naming a padding. Rows go in a [SettingsGroup], which joins
 * them into one rounded card under a heading.
 */

private const val DISABLED_ALPHA = 0.38f

/** The shell of every settings subscreen: a collapsing title over a scrolling page. */
@Composable
fun SettingsSubscreen(
    title: String,
    onNavigateBack: () -> Unit,
    spacing: Dp = Spacing.xs,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    CollapsingScaffold(
        title = title,
        navigationIcon = { BackButton(onNavigateBack) },
        actions = actions,
        snackbarHost = snackbarHost,
    ) { padding ->
        PageColumn(padding, spacing = spacing, content = content)
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Spacing.l, top = Spacing.l, end = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** Rows that belong together: one rounded card, under a [title] if the group needs naming. */
@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    title?.let { SectionHeader(it) }
    SegmentedGroup(modifier, content)
}

/** The glyph a row leads with, in a polygon of its own so a list of them is easy to tell apart. */
@Composable
private fun RowIcon(
    icon: ImageVector,
    polygon: RoundedPolygon,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    ShapedIcon(icon = icon, polygon = polygon, containerColor = containerColor, contentColor = contentColor)
}

/**
 * The base every other row is made of.
 *
 * [trailing] is sized to its own content rather than a fixed column, so a switch, a chevron and a
 * readout can all sit at the same edge without agreeing on a width beforehand. [value] sits at the
 * end of the title's line instead, which leaves the whole width below it to [supporting].
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
    value: (@Composable () -> Unit)? = null,
) {
    val supportingContent: (@Composable () -> Unit)? = if (summary != null || supporting != null) {
        {
            Column {
                summary?.let { Text(it) }
                supporting?.invoke()
            }
        }
    } else {
        null
    }
    val headline: @Composable () -> Unit = {
        if (value == null) {
            Text(title)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).padding(end = Spacing.s))
                value()
            }
        }
    }
    // Centred against the words however many lines they wrap to; a control below them is what makes
    // a row tall enough that its icon belongs back up beside the title.
    val alignment = if (supporting == null) Alignment.CenterVertically else ListItemDefaults.verticalAlignment()
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = segmentedInnerShapes(),
            enabled = enabled,
            colors = segmentedItemColors(),
            leadingContent = leading,
            supportingContent = supportingContent,
            trailingContent = trailing,
            verticalAlignment = alignment,
            content = headline,
        )
    } else {
        SegmentedListItem(
            shapes = segmentedInnerShapes(),
            enabled = enabled,
            colors = segmentedItemColors(),
            leadingContent = leading,
            supportingContent = supportingContent,
            trailingContent = trailing,
            verticalAlignment = alignment,
            content = headline,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
) {
    val haptics = rememberHaptics()
    // The whole row is the target, not just the switch: a toggle you have to hit at the far edge of
    // a tablet is a toggle people miss.
    SegmentedListItem(
        checked = checked,
        onCheckedChange = {
            haptics.toggle(it)
            onCheckedChange(it)
        },
        shapes = segmentedInnerShapes(),
        enabled = enabled,
        colors = checkableItemColors(),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = icon?.let { { RowIcon(it, polygon) } },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(NotesIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    ) { Text(title) }
}

/**
 * A continuous value.
 *
 * Float rather than the whole numbers a settings slider usually carries, because half of these tune
 * ink maths — a smoothing multiplier and a minimum pressure are genuinely fractional. [format] owns
 * the readout so the row never has to know whether it is showing milliseconds or a multiplier, and
 * it is composable because every one of those readouts is a string resource.
 *
 * The readout shares the title's line, so a value growing a digit cannot resize the track under the
 * finger. It rolls only on a stepped slider: a continuous one changes it on every drag frame.
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
    /** Stops between the ends, as `Slider` counts them; 0 is a continuous slider. */
    steps: Int = 0,
) {
    val readout = format(value)
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        supporting = {
            AppSlider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                enabled = enabled,
            )
        },
        value = {
            val style = MaterialTheme.typography.titleMediumEmphasized.tabular()
            val color = accentColor(enabled)
            if (steps > 0) {
                RollingText(readout, style = style, color = color)
            } else {
                Text(readout, style = style, color = color, maxLines = 1)
            }
        },
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
                val color = accentColor(enabled)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 128.dp),
                    )
                    Icon(NotesIcons.ArrowDropDown, contentDescription = null, tint = color)
                }
                AppMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    AppMenuGroup {
                        options.forEach { (option, label) ->
                            AppMenuChoiceItem(
                                text = label,
                                selected = option == selected,
                                onClick = {
                                    onSelect(option)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * Two or three choices worth seeing all at once, as a connected button row under the title.
 * [preview] sits between the words and the buttons, for a choice that is easier shown than named.
 */
@Composable
fun <T> ToggleRow(
    title: String,
    summary: String? = null,
    options: List<ToggleOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
    preview: (@Composable () -> Unit)? = null,
) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        leading = icon?.let { { RowIcon(it, polygon) } },
        supporting = {
            Column(Modifier.padding(top = Spacing.s)) {
                preview?.invoke()
                ConnectedToggleRow(options = options, selected = selected, onSelect = onSelect, enabled = enabled)
            }
        },
    )
}

/** A row that opens another screen: a shaped icon if it has one, and a trailing chevron. */
@Composable
fun NavigationRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        onClick = onClick,
        leading = icon?.let { { RowIcon(it, polygon, iconContainerColor, iconContentColor) } },
        trailing = {
            Icon(
                imageVector = NotesIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** A row's current value: the accent colour, greyed with the row when it is disabled. */
@Composable
private fun accentColor(enabled: Boolean): Color =
    if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    }
