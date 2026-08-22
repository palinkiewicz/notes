package pl.dakil.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.settings.AppearanceSettingsScreen
import pl.dakil.notes.settings.InputSettingsScreen
import pl.dakil.notes.settings.SettingsRootScreen
import pl.dakil.notes.ui.motion.blockInputWhileExiting
import pl.dakil.notes.ui.motion.pushScreen
import pl.dakil.notes.ui.motion.screenTransitionGround

/** The pages of the settings section. The root is the index; the rest are pushed over it. */
private enum class SettingsPage { ROOT, APPEARANCE, INPUT }

/**
 * The power-user surface.
 *
 * Everything here has a sensible default that a casual note-taker never has to look at. It is not
 * behind an "advanced mode" flag, because hiding settings behind a mode makes them undiscoverable
 * without making the default any simpler — it is one level down from the tools themselves, split in
 * two so neither list is long enough to scroll past what you came for.
 *
 * A saved enum rather than a nav graph, for the same reason the app shell uses one: three pages with
 * a single way in and out is not a state machine that needs a library.
 */
@Composable
fun SettingsScreen(settings: SettingsRepository) {
    val current by settings.settings.collectAsState(initial = remember { settings.read() })
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    // Registered deeper than the shell's "back returns to the library" handler, and back handlers
    // run innermost first, so this one takes the gesture while a subscreen is open and yields it at
    // the root without the two needing to know about each other.
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.screenTransitionGround(),
        transitionSpec = { pushScreen(forward = targetState != SettingsPage.ROOT) },
        label = "settingsPage",
    ) { target ->
        Box(Modifier.fillMaxSize().then(blockInputWhileExiting())) {
            when (target) {
                SettingsPage.ROOT -> SettingsRootScreen(
                    onNavigateToAppearance = { page = SettingsPage.APPEARANCE },
                    onNavigateToInput = { page = SettingsPage.INPUT },
                )

                SettingsPage.APPEARANCE -> AppearanceSettingsScreen(
                    current = current,
                    settings = settings,
                    onNavigateBack = { page = SettingsPage.ROOT },
                )

                SettingsPage.INPUT -> InputSettingsScreen(
                    current = current,
                    settings = settings,
                    onNavigateBack = { page = SettingsPage.ROOT },
                )
            }
        }
    }
}
