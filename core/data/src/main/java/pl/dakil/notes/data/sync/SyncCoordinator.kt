package pl.dakil.notes.data.sync

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.NoteStore
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.SyncProvider
import pl.dakil.notes.sync.RemoteBackend
import pl.dakil.notes.sync.SyncDirection
import pl.dakil.notes.sync.SyncEngine
import pl.dakil.notes.sync.SyncOutcome
import pl.dakil.notes.sync.TokenProvider
import pl.dakil.notes.sync.remote.DriveBackend
import pl.dakil.notes.sync.remote.GoogleCredential
import pl.dakil.notes.sync.remote.GoogleOAuth
import pl.dakil.notes.sync.remote.WebDavAccount
import pl.dakil.notes.sync.remote.WebDavBackend
import java.io.File

/**
 * Builds the configured backend and runs a pass against it.
 *
 * The one place that knows which provider is selected, so nothing above has to care: the engine,
 * the planner and the mirror are all written against `RemoteBackend` and never learn whether the
 * bytes went to Drive, to a Nextcloud, or nowhere at all.
 */
class SyncCoordinator(
    private val context: Context,
    private val store: NoteStore,
    private val repository: NoteRepository,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val stateDirectory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun backendOrNull(): RemoteBackend? {
        val current = settings.read()
        return when (current.syncProvider) {
            SyncProvider.NONE -> null

            SyncProvider.WEBDAV -> {
                val password = tokens.get(KEY_WEBDAV_PASSWORD).orEmpty()
                if (current.webDavUrl.isBlank() || current.webDavUser.isBlank()) null
                else WebDavBackend(WebDavAccount(current.webDavUrl, current.webDavUser, password), io)
            }

            SyncProvider.DRIVE -> {
                val credential = driveCredential() ?: return null
                if (tokens.get(KEY_DRIVE_REFRESH).isNullOrEmpty()) null
                else DriveBackend(driveTokens(credential), current.driveFolder, io)
            }
        }
    }

    fun driveCredential(): GoogleCredential? {
        val current = settings.read()
        if (current.driveClientId.isBlank()) return null
        return GoogleCredential(current.driveClientId, tokens.get(KEY_DRIVE_SECRET).orEmpty())
    }

    /**
     * Keeps one access token in memory and refreshes it when it lapses.
     *
     * In memory rather than stored: it is worthless in an hour, and writing it out would be one
     * more secret to seal, exclude from backup and remember to clear on sign-out.
     */
    private fun driveTokens(credential: GoogleCredential): TokenProvider = object : TokenProvider {
        @Volatile private var access: String? = null
        @Volatile private var expiresAt = 0L

        override suspend fun bearer(): String? {
            access?.takeIf { clock() < expiresAt }?.let { return it }
            val refresh = tokens.get(KEY_DRIVE_REFRESH) ?: return null
            val fresh = GoogleOAuth.refresh(credential, refresh, clock(), io).getOrNull() ?: return null
            // Google may hand back a new refresh token; storing it is what keeps the connection
            // alive past the point the old one is retired.
            if (fresh.refreshToken.isNotEmpty() && fresh.refreshToken != refresh) {
                tokens.put(KEY_DRIVE_REFRESH, fresh.refreshToken)
            }
            access = fresh.accessToken
            expiresAt = fresh.expiresAt
            return fresh.accessToken
        }

        override suspend fun invalidate() {
            access = null
            expiresAt = 0L
        }
    }

    fun saveWebDavPassword(password: String) = tokens.put(KEY_WEBDAV_PASSWORD, password)

    fun saveDriveSecret(secret: String) = tokens.put(KEY_DRIVE_SECRET, secret)

    fun saveDriveRefreshToken(token: String) = tokens.put(KEY_DRIVE_REFRESH, token)

    fun isDriveConnected(): Boolean = !tokens.get(KEY_DRIVE_REFRESH).isNullOrEmpty()

    fun disconnect() {
        tokens.clear("sync.")
        tokens.put(KEY_DRIVE_REFRESH, "")
        tokens.put(KEY_DRIVE_SECRET, "")
        tokens.put(KEY_WEBDAV_PASSWORD, "")
        settings.setSyncProvider(SyncProvider.NONE)
    }

    suspend fun syncNow(confirmDeletes: Boolean = false): SyncOutcome = withContext(io) {
        val backend = backendOrNull()
            ?: return@withContext SyncOutcome(failure = "No sync account is set up")
        val root = store.root()?.value
            ?: return@withContext SyncOutcome(failure = "No library")
        val current = settings.read()

        val engine = SyncEngine(
            local = StoreMirror(store, repository, io),
            remote = backend,
            stateStore = FileSyncStateStore(stateDirectory, io),
            deviceId = settings.deviceId(),
            localRoot = root,
            clock = clock,
        )
        val outcome = engine.run(
            direction = if (current.syncTwoWay) SyncDirection.TWO_WAY else SyncDirection.UPLOAD_ONLY,
            confirmDeletes = confirmDeletes,
        )
        if (outcome.failure == null) settings.setLastSyncAt(clock())
        outcome
    }

    private companion object {
        const val KEY_WEBDAV_PASSWORD = "sync.webdav.password"
        const val KEY_DRIVE_SECRET = "sync.drive.secret"
        const val KEY_DRIVE_REFRESH = "sync.drive.refresh"
    }
}
