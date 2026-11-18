package pl.dakil.notes.ui.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The check that pops into the corner of a picked card: a cookie that turns as it lands, the same
 * flourish as the theme swatches in Settings, settling still when decorative motion is off.
 */
@Composable
fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val motion = MaterialTheme.motionScheme
    val turn by animateFloatAsState(
        targetValue = if (selected) 0f else decorativeTurn(-90f),
        animationSpec = decorativeSpec(motion.defaultSpatialSpec()),
        label = "badgeTurn",
    )
    AnimatedVisibility(
        visible = selected,
        enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        ShapedIcon(
            icon = NotesIcons.Check,
            polygon = MaterialShapes.Cookie9Sided,
            size = size,
            iconSize = size * 0.64f,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            containerRotation = turn,
        )
    }
}
