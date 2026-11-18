package pl.dakil.notes.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import pl.dakil.notes.ui.theme.Spacing

/**
 * A centred "nothing here" block: a big shaped glyph, a title, a line of help and optional actions.
 *
 * The shape settles into place with a quarter turn when it first appears — a small welcome rather
 * than a blank screen — unless decorative motion is off.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    polygon: RoundedPolygon = MaterialShapes.Cookie12Sided,
    actions: (@Composable () -> Unit)? = null,
) {
    val decorative = decorativeMotion
    val turn = remember { Animatable(if (decorative) -60f else 0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { turn.animateTo(0f, spec) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        ShapedIcon(
            icon = icon,
            polygon = polygon,
            size = 96.dp,
            iconSize = 44.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerRotation = turn.value,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actions != null) {
            Column(Modifier.padding(top = Spacing.m), horizontalAlignment = Alignment.CenterHorizontally) { actions() }
        }
    }
}
