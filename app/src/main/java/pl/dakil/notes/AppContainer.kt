package pl.dakil.notes

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import pl.dakil.notes.data.FileNoteStore
import pl.dakil.notes.data.NoteIndex
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.NoteStore
import pl.dakil.notes.data.SettingsRepository
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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val index: NoteIndex by lazy { NoteIndex(appContext) }

    /**
     * The active store.
     *
     * Currently app-private storage. Swapping in [pl.dakil.notes.data.SafNoteStore] to point at a
     * user-chosen folder — one that Syncthing or a cloud client already watches — is a change to
     * this one property, because nothing above [NoteStore] knows where bytes live.
     */
    val store: NoteStore by lazy {
        FileNoteStore(File(appContext.filesDir, "notes").apply { mkdirs() })
    }

    val repository: NoteRepository by lazy {
        NoteRepository(store = store, index = index, scope = applicationScope)
    }
}
