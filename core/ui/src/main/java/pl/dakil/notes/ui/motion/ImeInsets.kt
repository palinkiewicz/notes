package pl.dakil.notes.ui.motion

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Makes sure Compose hears the keyboard's final size once it has finished moving.
 *
 * Compose ignores inset changes while an animation runs, and picks up the last one when the
 * animation ends, but only if that animation has a positive duration. A keyboard dismissed by the
 * predictive back gesture is driven by the system with no duration at all, so `imePadding` would
 * keep the height of a keyboard that is gone. Asking for the insets again once the animation is over
 * delivers them through the ordinary path.
 *
 * Call it on a parent of the Compose view: Compose owns the animation callback on its own view.
 */
fun View.keepImeInsetsCurrent() {
    val view = this
    ViewCompat.setWindowInsetsAnimationCallback(
        view,
        object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat = insets

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                    // Posted, because parents hear of the end first and Compose has yet to.
                    view.post { ViewCompat.requestApplyInsets(view) }
                }
            }
        },
    )
}
