package pl.dakil.notes.data.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.sync.SyncState
import pl.dakil.notes.sync.SyncStateCodec
import pl.dakil.notes.sync.SyncStateStore
import java.io.File

/**
 * Sync state on app-private storage, written temp-then-rename.
 *
 * Deliberately **not** inside the user's library folder: it would sync itself to the remote and to
 * Syncthing, and another device's bookkeeping arriving as your own is the one input that makes the
 * planner delete things. App-private storage is also the only place a rename is genuinely atomic,
 * which matters for a file that is rewritten after every applied action.
 */
class FileSyncStateStore(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncStateStore {

    override suspend fun load(key: String): SyncState? = withContext(io) {
        val file = File(directory, "$key.json")
        if (!file.exists()) return@withContext null
        runCatching { SyncStateCodec.read(file.readText()) }.getOrNull()
    }

    override suspend fun save(key: String, state: SyncState) = withContext(io) {
        directory.mkdirs()
        val target = File(directory, "$key.json")
        val temp = File(directory, "$key.json.tmp")
        runCatching {
            temp.writeText(SyncStateCodec.write(state))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        Unit
    }
}
