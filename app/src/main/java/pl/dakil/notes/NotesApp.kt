package pl.dakil.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.editor.EditorScreen
import pl.dakil.notes.editor.NoteViewModel
import pl.dakil.notes.editor.text.TextNoteScreen
import pl.dakil.notes.editor.text.TextNoteViewModel
import pl.dakil.notes.format.NoteKind
import pl.dakil.notes.library.LibraryScreen
import pl.dakil.notes.library.LibraryViewModel
import pl.dakil.notes.library.R as LibraryR
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.motion.blockInputWhileExiting
import pl.dakil.notes.ui.motion.fadeThrough
import pl.dakil.notes.ui.motion.pushScreen
import pl.dakil.notes.ui.motion.screenTransitionGround

/** The app's top-level destinations. */
enum class Destination {
    LIBRARY,
    SETTINGS,
}

/**
 * What a destination is called in the navigation container.
 *
 * Kept out of the enum: a constructor argument would put English in a type that four other files
 * pattern-match on, and there is no `Context` there to translate it with.
 */
@Composable
private fun Destination.label(): String = stringResource(
    when (this) {
        Destination.LIBRARY -> R.string.nav_notes
        Destination.SETTINGS -> R.string.nav_settings
    },
)

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
 *
 * The two `AnimatedContent`s are what makes that pair of values read as navigation. They carry the
 * two different Material moves on purpose: the destinations are peers and fade through each other,
 * while the editor arrives over the shell from the side and leaves the way it came.
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
        factory = LibraryViewModel.factory(
            container.store,
            container.index,
            container.repository,
            container.settings,
            // Resolved here: the view model puts both into text the user reads, and has no context
            // of its own to translate them with.
            rootFolderName = stringResource(LibraryR.string.library_root_folder),
            untitledName = stringResource(LibraryR.string.library_untitled),
        )
    )
    val noteViewModel: NoteViewModel = viewModel(
        factory = NoteViewModel.factory(container.repository, container.settings)
    )
    val textNoteViewModel: TextNoteViewModel = viewModel(
        factory = TextNoteViewModel.factory(container.repository)
    )

    LaunchedEffect(Unit) { libraryViewModel.start() }

    // Only the editor that is actually open is flushed: the other one still holds the last note it
    // had, and flushing that would rewrite a file nobody has touched.
    //
    // The flush happens before `openNote` is cleared, so the save is issued as the closing animation
    // starts rather than after half a second of sliding.
    fun closeEditor() {
        val ref = openNote
        if (ref != null && NoteKind.of(ref.substringAfterLast('/')) == NoteKind.TEXT) {
            textNoteViewModel.flush()
        } else {
            noteViewModel.flush()
        }
        openNote = null
        libraryViewModel.refresh()
    }

    // Without these the system back gesture falls through to the activity and finishes it, so
    // backing out of a note drops the user onto whatever app was behind this one. A destination you
    // can only leave by finding the right button on screen is not a navigated screen.
    //
    // They stay here, outside the animation: `AnimatedContent` composes both the outgoing and the
    // incoming screen at once, and a back handler registered inside would briefly exist twice.
    BackHandler(enabled = openNote != null) { closeEditor() }
    BackHandler(enabled = openNote == null && destination != Destination.LIBRARY) {
        destination = Destination.LIBRARY
    }

    AnimatedContent(
        targetState = openNote,
        modifier = Modifier.screenTransitionGround(),
        // Opening a note pushes; closing it pops. The direction is the only thing that tells a user
        // which of the two just happened.
        transitionSpec = { pushScreen(forward = targetState != null) },
        label = "editor",
    ) { note ->
        Box(Modifier.fillMaxSize().then(blockInputWhileExiting())) {
            if (note != null) {
                // The editor takes the whole window: on a phone the navigation bar would eat scarce
                // page height, and on a tablet the docked tool rail already occupies that edge.
                //
                // The file extension picks the editor. Nothing extra is saved for it, because a note
                // that could disagree with its own file name about what it is would be worse than no
                // record.
                if (NoteKind.of(note.substringAfterLast('/')) == NoteKind.TEXT) {
                    LaunchedEffect(note) { textNoteViewModel.open(StoreRef(note)) }
                    TextNoteScreen(
                        viewModel = textNoteViewModel,
                        onNavigateBack = ::closeEditor,
                        expanded = expanded,
                        // A note's file name is its identity here, so a rename hands back a new ref.
                        // The editors keep the document they already have — their `open` is a no-op
                        // for a note they are holding — but leaving this pointing at the old name
                        // would flush the note to a file that no longer exists, and reopen nothing
                        // after a process death.
                        onRenamed = { openNote = it.value },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LaunchedEffect(note) { noteViewModel.open(StoreRef(note)) }
                    EditorScreen(
                        viewModel = noteViewModel,
                        onNavigateBack = ::closeEditor,
                        darkTheme = darkTheme,
                        expanded = expanded,
                        onRenamed = { openNote = it.value },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        // `navigationSuiteItems` is a plain builder, not a composable scope, so
                        // the label is resolved inside the slots rather than hoisted above them.
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
                                        contentDescription = entry.label(),
                                    )
                                },
                                label = { Text(entry.label()) },
                            )
                        }
                    },
                ) {
                    AnimatedContent(
                        targetState = destination,
                        modifier = Modifier.screenTransitionGround(),
                        transitionSpec = { fadeThrough() },
                        label = "destination",
                    ) { screen ->
                        when (screen) {
                            Destination.LIBRARY -> LibraryScreen(
                                viewModel = libraryViewModel,
                                onOpenNote = { openNote = it.value },
                            )

                            Destination.SETTINGS -> SettingsScreen(container.settings)
                        }
                    }
                }
            }
        }
    }
}
