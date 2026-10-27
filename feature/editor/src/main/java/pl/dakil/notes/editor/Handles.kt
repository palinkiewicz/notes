package pl.dakil.notes.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The grips that stand round something on the sheet: a text box, or a selection.
//
// Shared between TextBoxChrome and SelectionChrome because both put their controls *outside* the
// sheet's scale transform, for the same reason: a note opens at roughly a quarter scale on a phone,
// and a 32 dp grip laid out inside the paper would be 8 dp of glass that nobody can hit — then a
// saucer covering the work at four times zoom. So they are laid out in window pixels at their
// natural size and merely *positioned* from the transform.

/**
 * One draggable grip.
 *
 * A `Surface` rather than an `IconButton` because it has to be *dragged* rather than pressed, and a
 * button reports a drag as nothing at all — whatever it controls would sit still however far the
 * finger went and then take the whole journey in one jump, or not at all.
 */
@Composable
internal fun Grip(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = HandleSize,
    /**
     * Turns the glyph, in degrees, for a grip whose icon has to point the way the drag goes.
     *
     * A lambda read in the draw layer, so a grip that turns with every input sample — the rotate
     * grip while a selection turns — does so without recomposing. Only the glyph turns: turning the
     * grip itself would turn the coordinates its own drag is reported in.
     */
    rotation: () -> Float = { 0f },
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    HandleSurface(
        modifier = modifier.gripDrag(onStart, onEnd, onDrag),
        icon = icon,
        description = description,
        size = size,
        rotation = rotation,
        container = container,
        content = content,
    )
}

/**
 * A grip on a selection's frame: a small round button with nothing on it, standing exactly on a
 * corner or an edge's middle.
 *
 * No icon, and small, because there are eight of them round the work and each is only a mark of
 * where to take hold — the frame says what they do. The disc is drawn at [FrameGripSize] but takes
 * touches across a full [HandleSize], so it stays easy to hit while covering little of the ink.
 */
@Composable
internal fun FrameGrip(
    description: String,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    Box(
        modifier = modifier
            .size(HandleSize)
            .gripDrag(onStart, onEnd, onDrag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // The same surface as the other grips, so the frame's grips and the rotate grip read as
        // one set of controls at two sizes.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 2.dp,
            modifier = Modifier.size(FrameGripSize),
        ) {}
    }
}

/**
 * The drag every grip shares.
 *
 * Every frame is consumed. Unlike the taps on the sheet below, which are watched and left alone so
 * that a drag can still be a pan, a finger on a grip is unambiguous: it came down on a control that
 * is only there because the user put something under it.
 */
@Composable
private fun Modifier.gripDrag(
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
): Modifier {
    // The gesture loop is started once and never restarted, so it would otherwise hold the very
    // first `onDrag` it was given — one that closes over the geometry as it stood before the finger
    // moved. Every frame would then apply a single frame's delta to the *original* position, and
    // the grip would sit shivering in place instead of following the hand.
    val currentDrag by rememberUpdatedState(onDrag)
    val currentStart by rememberUpdatedState(onStart)
    val currentEnd by rememberUpdatedState(onEnd)

    return pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { currentStart() },
            onDragEnd = { currentEnd() },
            onDragCancel = { currentEnd() },
        ) { change, delta ->
            change.consume()
            currentDrag(delta.x, delta.y)
        }
    }
}

/** A grip that is a button: whatever it does, there is nothing to drag about it. */
@Composable
internal fun HandleButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    elevation: Dp = 2.dp,
    onClick: () -> Unit,
) {
    val current by rememberUpdatedState(onClick)
    HandleSurface(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                val up = waitForUpOrCancellation()
                if (up != null) {
                    up.consume()
                    current()
                }
            }
        },
        icon = icon,
        description = description,
        container = container,
        content = content,
        elevation = elevation,
    )
}

@Composable
internal fun HandleSurface(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = HandleSize,
    rotation: () -> Float = { 0f },
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    elevation: Dp = 2.dp,
) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = elevation,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(size / 2).graphicsLayer { rotationZ = rotation() },
            )
        }
    }
}

/** Comfortably tappable at any zoom, because it is never scaled by one. */
internal val HandleSize = 32.dp

/** The visible disc of a [FrameGrip]; its touch area is a full [HandleSize]. */
internal val FrameGripSize = 14.dp

/** How far clear of what it belongs to a grip sits, in window pixels. */
internal const val HANDLE_GAP = 8f
