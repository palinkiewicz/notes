package pl.dakil.notes.ui.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * A glyph sitting inside an expressive MaterialShapes polygon — settings rows, empty states, the
 * about page. [containerRotation] turns only the polygon; the glyph stays upright.
 */
@Composable
fun ShapedIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
    size: Dp = 40.dp,
    iconSize: Dp = size * 0.5f,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    contentDescription: String? = null,
    containerRotation: Float = 0f,
) {
    val shape = remember(polygon) { PolygonShape(polygon) }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = containerRotation }
                .background(containerColor, shape),
        )
        Icon(icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}

/**
 * An icon that cross-fades between its outlined and filled forms — the selected state of a
 * navigation item or a toggle, filling in as it is chosen.
 */
@Composable
fun AnimatedIcon(
    outlined: ImageVector,
    filled: ImageVector,
    selected: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Crossfade(
        targetState = selected,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        modifier = modifier,
        label = "iconFill",
    ) { isFilled ->
        Icon(if (isFilled) filled else outlined, contentDescription = contentDescription, tint = tint)
    }
}
