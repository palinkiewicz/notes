package pl.dakil.notes.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Calls [save] when the note is closed and whenever the app leaves the foreground.
 *
 * Closing alone is not enough: an app swiped away from recents is killed without its screens ever
 * being disposed, so the last chance to write where the reader was is the moment it was hidden.
 */
@Composable
internal fun SaveViewOnLeave(save: () -> Unit) {
    val current by rememberUpdatedState(save)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            current()
        }
    }
}
