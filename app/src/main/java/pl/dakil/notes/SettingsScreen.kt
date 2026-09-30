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
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.settings.AppearanceSettingsScreen
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
private enum class SettingsPage { ROOT, APPEARANCE, INPUT, STORAGE, BACKUP, SYNC, SYNC_DRIVE, SYNC_WEBDAV }

/**
 * The power-user surface.
 *
 * Everything here has a sensible default that a casual note-taker never has to look at. It is not
 * behind an "advanced mode" flag, because hiding settings behind a mode makes them undiscoverable
 * without making the default any simpler — it is one level down from the tools themselves, split in
 * two so neither list is long enough to scroll past what you came for.
 *
 * A saved enum rather than a nav graph, for the same reason the app shell uses one: four pages with
 * a single way in and out is not a state machine that needs a library.
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    libraryRoot: LibraryRootController,
    backup: BackupRunner,
    sync: SyncCoordinator,
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
                    onNavigateToStorage = { page = SettingsPage.STORAGE },
                    onNavigateToBackup = { page = SettingsPage.BACKUP },
                    onNavigateToSync = { page = SettingsPage.SYNC },
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

                SettingsPage.STORAGE -> StorageSettingsScreen(
                    viewModel = viewModel(
                        factory = StorageSettingsViewModel.factory(libraryRoot, settings)
                    ),
                    onNavigateBack = { page = SettingsPage.ROOT },
                )

                SettingsPage.BACKUP -> BackupSettingsScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateBack = { page = SettingsPage.ROOT },
                )

                SettingsPage.SYNC -> SyncSettingsScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateToDrive = { page = SettingsPage.SYNC_DRIVE },
                    onNavigateToWebDav = { page = SettingsPage.SYNC_WEBDAV },
                    onNavigateBack = { page = SettingsPage.ROOT },
                )

                SettingsPage.SYNC_DRIVE -> DriveSetupScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateBack = { page = SettingsPage.SYNC },
                )

                SettingsPage.SYNC_WEBDAV -> WebDavSetupScreen(
                    current = current,
                    viewModel = backupSyncViewModel,
                    onNavigateBack = { page = SettingsPage.SYNC },
                )
            }
        }
    }
}
