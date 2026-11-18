package pl.dakil.notes.ui.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One choice in a [ConnectedToggleRow]. [icon] and [selectedIcon] are the outlined and filled forms;
 * the button fills its icon in as it is checked.
 */
@Immutable
data class ToggleOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val selectedIcon: ImageVector? = icon,
    /** Show only the icon; [label] then becomes its content description. */
    val iconOnly: Boolean = false,
)

/**
 * M3 Expressive connected button group used as a single-select segmented control: the checked
 * button morphs to a fully round shape and its neighbours keep their connected inner corners.
 *
 * [dense] is for four or more options on a phone. Every label keeps one small size, and only the
 * checked button shows its icon and takes the room for it, so no word has to shrink or be cut off
 * however long the translation runs.
 */
@Composable
fun <T> ConnectedToggleRow(
    options: List<ToggleOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    enabled: Boolean = true,
    dense: Boolean = false,
    colors: ToggleButtonColors = raisedToggleColors(),
) {
    val haptics = rememberHaptics()
    val motion = MaterialTheme.motionScheme
    Row(
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val checked = option.value == selected
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val width by animateFloatAsState(
                targetValue = if (dense && checked) DENSE_CHECKED_WEIGHT else 1f,
                animationSpec = motion.fastSpatialSpec(),
                label = "toggleWidth",
            )
            val content: @Composable RowScope.() -> Unit = {
                if (dense) {
                    AnimatedVisibility(
                        visible = checked && option.icon != null,
                        enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                        exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                    ) {
                        Row {
                            Icon(
                                imageVector = option.selectedIcon ?: option.icon ?: return@Row,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(DenseIconSpacing))
                        }
                    }
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    if (option.icon != null) {
                        AnimatedIcon(
                            outlined = option.icon,
                            filled = option.selectedIcon ?: option.icon,
                            selected = checked,
                            contentDescription = if (option.iconOnly) option.label else null,
                            modifier = Modifier.size(18.dp),
                        )
                        if (!option.iconOnly) Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                    }
                    if (!option.iconOnly) {
                        // Shrinks a long label (Polish "Systemowy") rather than clipping it.
                        Text(
                            option.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 10.sp,
                                maxFontSize = LocalTextStyle.current.fontSize,
                            ),
                        )
                    }
                }
            }
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (!checked) {
                        haptics.tick()
                        onSelect(option.value)
                    }
                },
                modifier = (if (fillWidth) Modifier.weight(width) else Modifier).semantics { role = Role.RadioButton },
                enabled = enabled,
                shapes = shapes,
                colors = colors,
                contentPadding = if (dense) DensePadding else ButtonDefaults.ContentPadding,
                content = content,
            )
        }
    }
}

private const val DENSE_CHECKED_WEIGHT = 1.4f
private val DenseIconSpacing = 6.dp
private val DensePadding = PaddingValues(horizontal = 6.dp)
