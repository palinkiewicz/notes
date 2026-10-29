package pl.dakil.notes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutGroup
import pl.dakil.notes.settings.labelRes
import pl.dakil.notes.settings.titleRes
import pl.dakil.notes.ui.keys.LocalShortcutDispatcher
import pl.dakil.notes.ui.keys.ShortcutDispatcher
import pl.dakil.notes.ui.keys.metaState
import pl.dakil.notes.ui.theme.NotesTheme
import pl.dakil.notes.ui.theme.resolveDark

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    /** Where key presses go; see [ShortcutDispatcher] for why it is the activity that hears them. */
    private val shortcuts = ShortcutDispatcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppGraph.get(this)
        shortcuts.onShowShortcuts = ::requestShowKeyboardShortcuts
        // Only a fresh start: a recreated activity was handed this intent already, and handling it
        // again would import the same PDF twice.
        if (savedInstanceState == null) receive(intent)
        // Every start, not only the first: a change of the app's language recreates the activity,
        // and this is when the shortcuts' labels catch up with it.
        container.applicationScope.launch { LauncherShortcuts.publish(this@MainActivity) }

        enableEdgeToEdge()
        setContent {
            // Read straight from the repository rather than through a view model: this is the one
            // place that has to see a theme change immediately, and routing it through a saved state
            // holder would only add a frame of the old colours.
            val settings by container.settings.settings
                .collectAsState(initial = remember { container.settings.read() })

            val dark = settings.darkTheme.resolveDark()
            SideEffect { shortcuts.keymap = settings.keymap }

            NotesTheme(
                colorTheme = settings.colorTheme,
                darkThemeOption = settings.darkTheme,
                pureBlack = settings.pureBlack,
            ) {
                // The canvas needs to know the theme directly: page colours come from the document,
                // not from the M3 scheme, so it picks the light or dark paper itself.
                CompositionLocalProvider(LocalShortcutDispatcher provides shortcuts) {
                    NotesApp(container = container, darkTheme = dark)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        shortcuts.dispatch(event) || super.dispatchKeyEvent(event)

    /**
     * The app's shortcuts for Android's own overview (Meta+/, or [ShortcutAction.SHOW_SHORTCUTS]),
     * built from the keymap as it is now, so a changed key is listed as changed.
     */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val keymap = shortcuts.keymap
        for (group in ShortcutGroup.entries) {
            val items = ShortcutAction.entries.filter { it.group == group }.flatMap { action ->
                val label = getString(action.labelRes())
                keymap.bindings(action).map { chord -> KeyboardShortcutInfo(label, chord.keyCode, chord.metaState()) }
            }
            if (items.isNotEmpty()) data += KeyboardShortcutGroup(getString(group.titleRes()), items)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    /**
     * Hands a PDF another app opened with this one, or shared to it, to the app to import — or a
     * launcher shortcut's new note to the app to make.
     *
     * Not when the activity was relaunched from Recents: that replays the intent it was first
     * started with, and the PDF it names is already a note, as is the one the shortcut asked for.
     */
    private fun receive(intent: Intent?) {
        intent ?: return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        LauncherShortcuts.requestedKind(intent)?.let { kind ->
            LauncherShortcuts.reportUsed(this, intent)
            container.requestedNote.value = kind
            return
        }
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        container.incomingPdf.value = uri
    }
}
