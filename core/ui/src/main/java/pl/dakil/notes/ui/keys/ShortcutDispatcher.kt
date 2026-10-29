package pl.dakil.notes.ui.keys

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import pl.dakil.notes.model.KeyChord
import pl.dakil.notes.model.Keymap
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutContext

/**
 * Hands key presses from the activity to whichever screens are listening.
 *
 * Keys arrive at the activity rather than through Compose's key modifiers because those only fire
 * on a focused node, and nothing on the sheet is ever focused: the pen does not take focus, and a
 * canvas that grabbed it would show up as a stop in keyboard navigation. The activity sees every key
 * of its window before any view does — before a focused text field's own Ctrl+Z, which matters for
 * the sheet's text boxes. Dialogs are windows of their own, so nothing here fires while one is open.
 *
 * Not before the soft keyboard, though: while a text field has focus, the keyboard is shown each key
 * first and keeps the ones it has a use for — Escape, which hides it. So a text field also offers its
 * keys here on their way to the keyboard ([shortcutsBeforeSoftKeyboard]), and the same event reaching
 * the activity afterwards is recognised and not offered twice.
 */
class ShortcutDispatcher {

    var keymap: Keymap = Keymap.DEFAULT

    /** Answers [ShortcutAction.SHOW_SHORTCUTS], which belongs to the activity rather than a screen. */
    var onShowShortcuts: (() -> Unit)? = null

    private var lastOffered: KeyEvent? = null
    private var lastOfferedTime = 0L

    private val handlers = ArrayList<Handler>()

    internal class Handler(
        val context: () -> ShortcutContext,
        val enabled: () -> Boolean,
        val onAction: (ShortcutAction) -> Boolean,
        val consume: (KeyChord) -> Boolean,
    )

    internal fun add(handler: Handler) {
        handlers += handler
    }

    internal fun remove(handler: Handler) {
        handlers -= handler
    }

    /** Offers a key press to the shortcuts, once; true when one of them took it. */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event === lastOffered && event.eventTime == lastOfferedTime) return false
        lastOffered = event
        lastOfferedTime = event.eventTime
        val chord = event.toChord() ?: return false
        val repeat = event.repeatCount > 0
        val show = onShowShortcuts
        if (show != null && !repeat && chord in keymap.bindings(ShortcutAction.SHOW_SHORTCUTS)) {
            show()
            return true
        }
        return dispatch(chord, repeat)
    }

    /**
     * Offers [chord] to every live handler, newest first, until one takes it.
     *
     * A key held down only repeats what [ShortcutAction.repeats] allows, so holding Ctrl+Z walks back
     * through history while holding E does not flicker between the two erasers.
     */
    fun dispatch(chord: KeyChord, repeat: Boolean): Boolean {
        for (handler in handlers.asReversed().toList()) {
            if (!handler.enabled()) continue
            val action = keymap.actionFor(chord, handler.context())
            if (action != null && (!repeat || action.repeats) && handler.onAction(action)) return true
            if (handler.consume(chord)) return true
        }
        return false
    }
}

val LocalShortcutDispatcher = staticCompositionLocalOf<ShortcutDispatcher?> { null }

/**
 * Offers this text field's keys to the shortcuts before the soft keyboard sees them, which would
 * otherwise keep Escape for itself. See [ShortcutDispatcher].
 */
@Composable
fun Modifier.shortcutsBeforeSoftKeyboard(): Modifier {
    val dispatcher = LocalShortcutDispatcher.current ?: return this
    return onPreInterceptKeyBeforeSoftKeyboard { dispatcher.dispatch(it.nativeKeyEvent) }
}

/**
 * Listens for [context]'s shortcuts for as long as this is in composition and [enabled].
 *
 * [onAction] answers whether it did something: a shortcut that has nothing to act on — Escape with
 * nothing selected, Tab outside a list — returns false and the key carries on to wherever it was
 * going. [consume] swallows keys that are not bound here but must not reach anything else.
 */
@Composable
fun ShortcutHandler(
    context: ShortcutContext,
    enabled: Boolean = true,
    consume: (KeyChord) -> Boolean = { false },
    onAction: (ShortcutAction) -> Boolean,
) {
    val dispatcher = LocalShortcutDispatcher.current ?: return
    val currentContext by rememberUpdatedState(context)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentAction by rememberUpdatedState(onAction)
    val currentConsume by rememberUpdatedState(consume)
    DisposableEffect(dispatcher) {
        val handler = ShortcutDispatcher.Handler(
            context = { currentContext },
            enabled = { currentEnabled },
            onAction = { currentAction(it) },
            consume = { currentConsume(it) },
        )
        dispatcher.add(handler)
        onDispose { dispatcher.remove(handler) }
    }
}
