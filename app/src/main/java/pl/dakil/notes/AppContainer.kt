package pl.dakil.notes

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.dakil.notes.data.FileNoteStore
import pl.dakil.notes.data.LibraryRootController
import pl.dakil.notes.data.NoteIndex
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.SwitchableNoteStore
import pl.dakil.notes.data.SyncProvider
import pl.dakil.notes.data.sync.BackupRunner
import pl.dakil.notes.data.sync.SyncCoordinator
import pl.dakil.notes.data.sync.TokenStore
import pl.dakil.notes.sync.SyncScheduler
import java.io.File

/**
 * Manual dependency wiring.
 *
 * No Hilt and no KSP: this graph is a handful of singletons that never change shape, and a
 * dependency-injection framework would add a processor to the build and a few hundred kilobytes to
 * the APK to save writing this file. It also keeps the codebase entirely reflection-free, which is
 * what lets R8 run in full mode with no keep rules at all.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives any screen, so a save in flight is not cancelled by a rotation. */
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val index: NoteIndex by lazy { NoteIndex(appContext) }

    /** Where the library lives when the user has not chosen a folder of their own. */
    private val internalRoot: File by lazy { File(appContext.filesDir, "notes").apply { mkdirs() } }

    /**
     * The active store.
     *
     * A [SwitchableNoteStore] rather than one backend, because the root is the user's to choose and
     * can change while the app is running. The indirection is what lets it: everything below holds
     * *this* object, so pointing it at a folder Syncthing watches never means rebuilding the
     * repository and dropping the write queued inside it.
     */
    val store: SwitchableNoteStore by lazy { SwitchableNoteStore(FileNoteStore(internalRoot)) }

    val repository: NoteRepository by lazy {
        NoteRepository(
            store = store,
            index = index,
            scope = applicationScope,
            deviceId = settings::deviceId,
        )
    }

    val libraryRoot: LibraryRootController by lazy {
        LibraryRootController(
            context = appContext,
            settings = settings,
            store = store,
            index = index,
            internalRoot = internalRoot,
        )
    }

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    /** Sync bookkeeping, app-private and never inside the user's library; see [FileSyncStateStore]. */
    private val syncStateDir: File by lazy { File(appContext.filesDir, "sync") }

    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            context = appContext,
            store = store,
            repository = repository,
            settings = settings,
            tokens = tokenStore,
            stateDirectory = syncStateDir,
        )
    }

    val backupRunner: BackupRunner by lazy {
        BackupRunner(
            context = appContext,
            store = store,
            repository = repository,
            settings = settings,
        )
    }

    /**
     * Points the store at the user's folder before anything reads it.
     *
     * Started rather than awaited: the library screen's first scan may briefly run against internal
     * storage and then be redone, which shows an empty list for a moment. Blocking the first frame
     * on a content-provider round trip would be the worse trade.
     */
    fun start() {
        applicationScope.launch {
            libraryRoot.restore()
            // Re-registered on every start: a periodic job does not survive an app upgrade, and a
            // backup the user believes is running and is not is worse than none at all.
            val current = settings.read()
            if (current.backupEnabled && current.backupDestination != null) {
                SyncScheduler.scheduleBackup(
                    appContext, current.backupFrequency, current.backupOnCharging, current.backupWifiOnly,
                )
            }
            if (current.syncProvider != SyncProvider.NONE) {
                SyncScheduler.scheduleSync(appContext, current.syncFrequency, current.syncWifiOnly)
            }
        }
    }
}
