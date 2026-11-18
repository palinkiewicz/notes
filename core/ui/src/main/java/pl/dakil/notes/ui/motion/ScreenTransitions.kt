package pl.dakil.notes.ui.motion

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

/**
 * The app's two screen transitions.
 *
 * Material describes two different moves, and the difference carries meaning: peers replace each
 * other in place, and a pushed screen arrives from the side over the one that spawned it. Both are
 * written against [AnimatedContentTransitionScope] rather than a navigation library's transition
 * slots, because this app's whole navigation state is a pair of saved values and `AnimatedContent`
 * gives the same helpers a `NavHost` would.
 *
 * The timings are the ones the author's other apps use, so moving between screens feels the same
 * in all of them: the push travels on the expressive "default spatial" spring, and the fades use the
 * emphasized curves. They are never gated by the decorative-motion setting — a transition says where
 * the user went, which is not decoration.
 */

// The expressive default spatial spring, a touch more damped so a full screen never overshoots and
// shows a sliver of what is behind it at the edge.
private val Spatial = spring(
    dampingRatio = 0.9f,
    stiffness = 700f,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Material fade-through: switching between two peer destinations.
 *
 * The outgoing screen fades first and the incoming one follows into the gap, which is what stops the
 * two from being briefly visible on top of each other.
 */
fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
    (
        fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate)) +
            scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate), initialScale = 0.94f)
        )
        .togetherWith(fadeOut(tween(90, easing = EmphasizedAccelerate)))
        // Without this `AnimatedContent` animates its own bounds between the two screens and clips
        // whichever is currently larger. The screens are the same size; there is nothing to animate.
        .using(SizeTransform(clip = false))

/**
 * Material shared axis X: pushing a screen over the one that opened it.
 *
 * The incoming screen travels a fifth of the width and the outgoing one a tenth, both on a spring,
 * with the newcomer fading in a beat after it starts to move. [forward] is what separates a push
 * from the pop of the same pair — the direction is the only thing that tells a user whether they
 * went deeper or came back.
 */
fun <S> AnimatedContentTransitionScope<S>.pushScreen(forward: Boolean): ContentTransform {
    val enter = fadeIn(tween(220, delayMillis = 60, easing = EmphasizedDecelerate))
    val exit = fadeOut(tween(120, easing = EmphasizedAccelerate))
    val transform = if (forward) {
        (slideInHorizontally(Spatial) { it / 5 } + enter) togetherWith (slideOutHorizontally(Spatial) { -it / 10 } + exit)
    } else {
        (slideInHorizontally(Spatial) { -it / 10 } + enter) togetherWith (slideOutHorizontally(Spatial) { it / 5 } + exit)
    }
    // The pop puts the screen being left on top, so it fades away over the one returned to rather
    // than the returning screen covering it at once.
    if (!forward) transform.targetContentZIndex = -1f
    return transform.using(SizeTransform(clip = false))
}

/**
 * The opaque ground an animating screen needs under it.
 *
 * Both transitions cross-fade, and for part of their run neither screen is fully opaque — the
 * fade-through even goes through a moment where the outgoing screen has reached zero and the
 * incoming one has not started. Whatever is behind shows through then, and behind is the platform's
 * window background, which is a stock near-white the app never chose. The result is a white flash
 * in the middle of every navigation.
 *
 * So every `AnimatedContent` that swaps screens paints the theme's own background first. It costs
 * one rectangle and it is the difference between a transition and a flicker.
 */
@Composable
fun Modifier.screenTransitionGround(): Modifier =
    fillMaxSize().background(MaterialTheme.colorScheme.background)

/**
 * Swallows touches on content that is animating away.
 *
 * `AnimatedContent` keeps the outgoing screen composed and hit-testable for the whole transition.
 * On a list that is harmless. On the editor it is half a second in which a stylus can lay ink into a
 * sheet that is sliding off the screen — a stroke the user will find later in a note they thought
 * they had closed.
 *
 * Consumed at [PointerEventPass.Initial] so it wins before any child sees the event, including
 * `InkOverlay`'s `pointerInteropFilter`.
 */
@Composable
fun AnimatedVisibilityScope.blockInputWhileExiting(): Modifier {
    val exiting = transition.targetState != EnterExitState.Visible
    return if (!exiting) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }
}

/**
 * Predictive back for a pushed screen: while the back gesture is held, the screen shrinks, rounds
 * off and leans toward the finger, and letting go runs [onBack] and the ordinary pop transition from
 * wherever it had got to. Abandoning the gesture springs it back.
 *
 * Put it on the screen's own root inside the `AnimatedContent`, with [enabled] false once the screen
 * is on its way out: the state then stays with the outgoing screen, so it leaves from the pose the
 * gesture left it in instead of snapping back to full size first. Only the layer changes, so a held
 * gesture recomposes nothing.
 */
@Composable
fun Modifier.predictiveBack(enabled: Boolean, onBack: () -> Unit): Modifier {
    val progress = remember { Animatable(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    val currentOnBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    val settle = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                fromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                progress.snapTo(event.progress)
            }
            currentOnBack()
        } catch (cancelled: CancellationException) {
            // Launched outside: this coroutine is already over, and could not animate anything.
            scope.launch { progress.animateTo(0f, settle) }
            throw cancelled
        }
    }
    return graphicsLayer {
        val amount = progress.value.coerceIn(0f, 1f)
        val scale = 1f - amount * BACK_PEEK_SHRINK
        scaleX = scale
        scaleY = scale
        translationX = amount * BACK_PEEK_SHIFT.toPx() * if (fromLeft) 1f else -1f
        shape = RoundedCornerShape(BACK_PEEK_CORNER * amount)
        clip = amount > 0f
    }
}

private const val BACK_PEEK_SHRINK = 0.08f
private val BACK_PEEK_SHIFT = 24.dp
private val BACK_PEEK_CORNER = 28.dp
