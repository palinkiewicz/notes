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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import pl.dakil.notes.data.LibraryRootController
import pl.dakil.notes.data.sync.BackupRunner
import pl.dakil.notes.data.sync.SyncCoordinator
import pl.dakil.notes.data.NoteViewStates
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.settings.AppearanceSettingsScreen
import pl.dakil.notes.settings.DrawingOrderSettingsScreen
import pl.dakil.notes.settings.EditorSettingsScreen
import pl.dakil.notes.settings.PaperColoursSettingsScreen
import pl.dakil.notes.settings.PaperThemesScreen
import pl.dakil.notes.settings.SavedColorsScreen
import pl.dakil.notes.settings.InputSettingsScreen
import pl.dakil.notes.settings.BackupSettingsScreen
import pl.dakil.notes.settings.BackupSyncViewModel
import pl.dakil.notes.settings.DriveSetupScreen
import pl.dakil.notes.settings.SettingsRootScreen
import pl.dakil.notes.settings.SyncSettingsScreen
import pl.dakil.notes.settings.WebDavSetupScreen
import pl.dakil.notes.settings.StorageSettingsScreen
import pl.dakil.notes.settings.StorageSettingsViewModel
import pl.dakil.notes.ui.motion.blockInputWhileExiting
import pl.dakil.notes.ui.motion.pushScreen
import pl.dakil.notes.ui.motion.screenTransitionGround

/** The pages of the settings section. The root is the index; the rest are pushed over it. */
private enum class SettingsPage {
    ROOT, APPEARANCE, EDITOR, DRAWING_ORDER, PAPER_COLOURS, PAPER_THEMES, SAVED_COLORS, INPUT, STORAGE,
    BACKUP, SYNC, SYNC_DRIVE, SYNC_WEBDAV;

    /**
     * Where back goes — the system gesture and the toolbar arrow alike, so the two cannot disagree.
     * A page missing from here falls back to the root, which is how Drive and WebDAV setup used to
     * skip straight past Sync.
     */
    val parent: SettingsPage
        get() = when (this) {
            DRAWING_ORDER -> INPUT
            PAPER_THEMES, SAVED_COLORS -> PAPER_COLOURS
            SYNC_DRIVE, SYNC_WEBDAV -> SYNC
            else -> ROOT
        }

    /** How far down the stack, so going back animates as going back. Derived, never listed. */
    val depth: Int
        get() = if (this == ROOT) 0 else parent.depth + 1
}

/**
 * The power-user surface.
 *
 * Everything here has a sensible default that a casual note-taker never has to look at. It is not
 * behind an "advanced mode" flag, because hiding settings behind a mode makes them undiscoverable
 * without making the default any simpler — it is one level down from the tools themselves, split in
 * two so neither list is long enough to scroll past what you came for.
 *
 * A saved enum rather than a nav graph, for the same reason the app shell uses one: a shallow tree
 * of pages, each with a single way in and out, is not a state machine that needs a library.
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    libraryRoot: LibraryRootController,
    backup: BackupRunner,
    sync: SyncCoordinator,
    viewStates: NoteViewStates,
) {
    val context = LocalContext.current
    val current by settings.settings.collectAsState(initial = remember { settings.read() })
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    // Hoisted above the AnimatedContent so the Drive wizard keeps its place in the flow while the
    // browser is in front of the app — an authorisation that reset itself on every return would be
    // impossible to finish.
    val backupSyncViewModel: BackupSyncViewModel = viewModel(
        factory = BackupSyncViewModel.factory(context, settings, backup, sync)
    )

    // Registered deeper than the shell's "back returns to the library" handler, and back handlers
    // run innermost first, so this one takes the gesture while a subscreen is open and yields it at
    // the root without the two needing to know about each other.
    BackHandler(enabled = page != SettingsPage.ROOT) { page = page.parent }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.screenTransitionGround(),
        transitionSpec = { pushScreen(forward = targetState.depth > initialState.depth) },
        label = "settingsPage",
    ) { target ->
        Box(Modifier.fillMaxSize().then(blockInputWhileExiting())) {
            // `target`, not `page`: the outgoing screen is still composed during the transition,
            // and its arrow has to lead to its own parent.
            val back = { page = target.parent }
            when (target) {
                SettingsPage.ROOT -> SettingsRootScreen(
                    onNavigateToAppearance = { page = SettingsPage.APPEARANCE },
                    onNavigateToEditor = { page = SettingsPage.EDITOR },
                    onNavigateToPaperColours = { page = SettingsPage.PAPER_COLOURS },
                    onNavigateToInput = { page = SettingsPage.INPUT },
                    onNavigateToStorage = { page = SettingsPage.STORAGE },
                    onNavigateToBackup = { page = SettingsPage.BACKUP },
                    onNavigateToSync = { page = SettingsPage.SYNC },
                )

                SettingsPage.APPEARANCE -> AppearanceSettingsScreen(
                    current = current,
                    settings = settings,
                    onNavigateBack = back,
                )

                SettingsPage.EDITOR -> EditorSettingsScreen(
                    current = current,
                    settings = settings,
                    onForgetPositions = viewStates::clear,
                    onNavigateBack = back,
                )

                SettingsPage.DRAWING_ORDER -> DrawingOrderSettingsScreen(
                    current = current,
                    settings = settings,
                    onNavigateBack = back,
                )

                SettingsPage.PAPER_COLOURS -> PaperColoursSettingsScreen(
                    current = current,
                    settings = settings,
                    onOpenPaperThemes = { page = SettingsPage.PAPER_THEMES },
                    onOpenSavedColors = { page = SettingsPage.SAVED_COLORS },
                    onNavigateBack = back,
                )

                SettingsPage.PAPER_THEMES -> PaperThemesScreen(
                    current = current,
                    settings = settings,
                    onNavigateBack = back,
                )

                SettingsPage.SAVED_COLORS -> SavedColorsScreen(
                    current = current,
                    settings = settings,
                    onNavigateBack = back,
                )

                SettingsPage.INPUT -> InputSettingsScreen(
                    current = current,
                    settings = settings,
                    onOpenDrawingOrder = { page = SettingsPage.DRAWING_ORDER },
                    onNavigateBack = back,
                )

                SettingsPage.STORAGE -> StorageSettingsScreen(
                    viewModel = viewModel(
                        factory = StorageSettingsViewModel.factory(libraryRoot, settings)
                    ),
                    onNavigateBack = back,
                )

                SettingsPage.BACKUP -> BackupSettingsScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateBack = back,
                )

                SettingsPage.SYNC -> SyncSettingsScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateToDrive = { page = SettingsPage.SYNC_DRIVE },
                    onNavigateToWebDav = { page = SettingsPage.SYNC_WEBDAV },
                    onNavigateBack = back,
                )

                SettingsPage.SYNC_DRIVE -> DriveSetupScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateBack = back,
                )

                SettingsPage.SYNC_WEBDAV -> WebDavSetupScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateBack = back,
                )
            }
        }
    }
}
