package pl.dakil.notes.ui.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Whether progress bars are drawn wavy, from Settings → Appearance → Feel. */
val wavyProgress: Boolean
    @Composable get() = LocalMotionPreferences.current.wavyProgress

/**
 * A linear progress bar in the style chosen in the Feel settings, wavy or straight: determinate with
 * a [progress], indeterminate without one.
 *
 * @param still for a determinate bar that shows a state rather than work going on: the wave stays
 *   put instead of travelling for as long as the bar is on screen.
 */
@Composable
fun AppLinearProgress(modifier: Modifier = Modifier, progress: (() -> Float)? = null, still: Boolean = false) {
    val wavy = wavyProgress
    when {
        !wavy && progress != null -> LinearProgressIndicator(progress = progress, modifier = modifier)
        !wavy -> LinearProgressIndicator(modifier = modifier)
        progress != null && still -> LinearWavyProgressIndicator(progress = progress, modifier = modifier, waveSpeed = 0.dp)
        progress != null -> LinearWavyProgressIndicator(progress = progress, modifier = modifier)
        else -> LinearWavyProgressIndicator(modifier = modifier)
    }
}

/**
 * A small indeterminate spinner in the chosen style, for work going on inside a row or a button,
 * where a full `LoadingIndicator` would be too much. The line and the wave are drawn finer than the
 * defaults, which are proportioned for a spinner twice this size and fill one this small solid.
 */
@Composable
fun AppCircularProgress(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = ProgressIndicatorDefaults.circularColor,
) {
    if (wavyProgress) {
        val density = LocalDensity.current
        val stroke = remember(density) {
            Stroke(width = with(density) { SpinnerStroke.toPx() }, cap = StrokeCap.Round)
        }
        CircularWavyProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = Color.Transparent,
            stroke = stroke,
            trackStroke = stroke,
            wavelength = SpinnerWavelength,
        )
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = SpinnerStroke)
    }
}

private val SpinnerStroke = 2.5.dp
private val SpinnerWavelength = 12.dp
