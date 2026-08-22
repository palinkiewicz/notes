package pl.dakil.notes.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The app's two screen transitions.
 *
 * Material describes two different moves, and the difference carries meaning: peers replace each
 * other in place, and a pushed screen arrives from the side over the one that spawned it. Both are
 * written against [AnimatedContentTransitionScope] rather than a navigation library's transition
 * slots, because this app's whole navigation state is a pair of saved values and `AnimatedContent`
 * gives the same `slideIntoContainer` helpers a `NavHost` would.
 */

private const val PUSH_MS = 500
private const val FADE_THROUGH_IN_MS = 220
private const val FADE_THROUGH_OUT_MS = 90

/**
 * Material fade-through: switching between two peer destinations.
 *
 * The outgoing screen fades first and the incoming one follows into the gap, which is what stops the
 * two from being briefly visible on top of each other.
 */
fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
    (
        fadeIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS),
            )
        )
        .togetherWith(fadeOut(tween(FADE_THROUGH_OUT_MS)))
        // Without this `AnimatedContent` animates its own bounds between the two screens and clips
        // whichever is currently larger. The screens are the same size; there is nothing to animate.
        .using(SizeTransform(clip = false))

/**
 * Material shared axis X: pushing a screen over the one that opened it.
 *
 * [forward] is what separates a push from the pop of the same pair — the direction is the only thing
 * that tells a user whether they went deeper or came back.
 */
fun <S> AnimatedContentTransitionScope<S>.pushScreen(forward: Boolean): ContentTransform {
    val towards = if (forward) SlideDirection.Start else SlideDirection.End
    return (slideIntoContainer(towards, tween(PUSH_MS)) + fadeIn(tween(PUSH_MS)))
        .togetherWith(slideOutOfContainer(towards, tween(PUSH_MS)) + fadeOut(tween(PUSH_MS)))
        .using(SizeTransform(clip = false))
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
