package pl.dakil.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.editor.EditorScreen
import pl.dakil.notes.editor.NoteViewModel
import pl.dakil.notes.library.LibraryScreen
import pl.dakil.notes.library.LibraryViewModel
import pl.dakil.notes.ui.icons.NotesIcons

/** The app's top-level destinations. */
enum class Destination(val label: String) {
    LIBRARY("Notes"),
    SETTINGS("Settings"),
}

/**
 * The app shell.
 *
 * `NavigationSuiteScaffold` picks its own container from the window size class — a bottom bar on a
 * phone, a navigation rail on an unfolded foldable, a permanent drawer on a large tablet. That is
 * one composable covering three form factors with no manual branching, which is exactly what the
 * "use the platform's components" constraint is asking for.
 *
 * `navigation-compose` is deliberately absent: with two top-level destinations and an editor pushed
 * over them, a saved enum plus a nullable open-note reference is the whole state machine. That
 * saves a dependency and a second source of truth for "where am I".
 */
@Composable
fun NotesApp(container: AppContainer, darkTheme: Boolean) {
    var destination by rememberSaveable { mutableStateOf(Destination.LIBRARY) }
    var openNote by rememberSaveable { mutableStateOf<String?>(null) }

    // `WindowWidthSizeClass` is deprecated in window 1.5; breakpoints are the current API.
    // 840dp is the expanded threshold — the point at which a docked tool rail and a wider list
    // stop crowding the page.
    val expanded = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.factory(container.store, container.index, container.repository)
    )
    val noteViewModel: NoteViewModel = viewModel(
        factory = NoteViewModel.factory(container.repository, container.settings)
    )

    androidx.compose.runtime.LaunchedEffect(Unit) { libraryViewModel.start() }

    fun closeEditor() {
        noteViewModel.flush()
        openNote = null
        libraryViewModel.refresh()
    }

    // Without these the system back gesture falls through to the activity and finishes it, so
    // backing out of a note drops the user onto whatever app was behind this one. A destination you
    // can only leave by finding the right button on screen is not a navigated screen.
    BackHandler(enabled = openNote != null) { closeEditor() }
    BackHandler(enabled = openNote == null && destination != Destination.LIBRARY) {
        destination = Destination.LIBRARY
    }

    val currentNote = openNote
    if (currentNote != null) {
        // The editor takes the whole window: on a phone the navigation bar would eat scarce page
        // height, and on a tablet the docked tool rail already occupies that edge.
        androidx.compose.runtime.LaunchedEffect(currentNote) {
            noteViewModel.open(StoreRef(currentNote))
        }
        EditorScreen(
            viewModel = noteViewModel,
            onNavigateBack = ::closeEditor,
            darkTheme = darkTheme,
            expanded = expanded,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            for (entry in Destination.entries) {
                item(
                    selected = destination == entry,
                    onClick = { destination = entry },
                    icon = {
                        Icon(
                            imageVector = when (entry) {
                                Destination.LIBRARY -> NotesIcons.Note
                                Destination.SETTINGS -> NotesIcons.Settings
                            },
                            contentDescription = entry.label,
                        )
                    },
                    label = { Text(entry.label) },
                )
            }
        },
    ) {
        when (destination) {
            Destination.LIBRARY -> LibraryScreen(
                viewModel = libraryViewModel,
                onOpenNote = { openNote = it.value },
                expanded = expanded,
            )

            Destination.SETTINGS -> SettingsScreen(container.settings)
        }
    }
}
