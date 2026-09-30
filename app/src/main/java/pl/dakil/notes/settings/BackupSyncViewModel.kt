package pl.dakil.notes.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.SyncFrequency
import pl.dakil.notes.data.SyncProvider
import pl.dakil.notes.data.sync.BackupResult
import pl.dakil.notes.data.sync.BackupRunner
import pl.dakil.notes.data.sync.RestoreResult
import pl.dakil.notes.data.sync.SyncCoordinator
import pl.dakil.notes.sync.SyncOutcome
import pl.dakil.notes.sync.SyncScheduler
import pl.dakil.notes.sync.remote.GoogleCredential
import pl.dakil.notes.sync.remote.GoogleOAuth
import pl.dakil.notes.sync.remote.PkceChallenge
import pl.dakil.notes.sync.remote.WebDavAccount
import pl.dakil.notes.sync.remote.WebDavBackend

/** Where the Drive wizard has got to. */
enum class DriveStep { INTRODUCTION, CREDENTIALS, AUTHORIZING, CONNECTED }

data class BackupSyncUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val lastBackup: BackupResult? = null,
    val lastSync: SyncOutcome? = null,
    val lastRestore: RestoreResult? = null,
    val driveStep: DriveStep = DriveStep.INTRODUCTION,
    val driveConnected: Boolean = false,
    /** Set while the browser is open, so the screen can offer to cancel. */
    val pendingAuthorizationUrl: String? = null,
    val webDavProbe: String? = null,
)

/**
 * The screen state for everything that runs in the background.
 *
 * The other settings pages are a value in and a method reference out, because every other setting
 * takes effect the instant it is tapped. None of this does: a backup copies a library, a sync talks
 * to a server, and an authorisation waits on a browser the user may never come back from.
 */
class BackupSyncViewModel(
    private val context: Context,
    private val settings: SettingsRepository,
    private val backup: BackupRunner,
    private val sync: SyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupSyncUiState(driveConnected = sync.isDriveConnected()))
    val state: StateFlow<BackupSyncUiState> = _state.asStateFlow()

    private var pkce: PkceChallenge? = null
    private var catcher: GoogleOAuth.LoopbackCatcher? = null

    // ---- Backup ---------------------------------------------------------------------------------

    fun setBackupDestination(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.setBackupDestination(uri.toString())
        reschedule()
    }

    fun setBackupEnabled(on: Boolean) {
        settings.setBackupEnabled(on)
        reschedule()
    }

    fun setBackupFrequency(v: SyncFrequency) {
        settings.setBackupFrequency(v)
        reschedule()
    }

    fun setBackupKeep(n: Int) = settings.setBackupKeep(n)

    fun setBackupOnCharging(on: Boolean) {
        settings.setBackupOnCharging(on)
        reschedule()
    }

    fun setBackupWifiOnly(on: Boolean) {
        settings.setBackupWifiOnly(on)
        reschedule()
    }

    fun backUpNow() = busy {
        val result = backup.backUpNow()
        _state.update { it.copy(lastBackup = result) }
    }

    fun restoreFrom(uri: Uri) = busy {
        val result = backup.restoreFrom(uri)
        _state.update { it.copy(lastRestore = result) }
    }

    // ---- Sync -----------------------------------------------------------------------------------

    fun setSyncFrequency(v: SyncFrequency) {
        settings.setSyncFrequency(v)
        reschedule()
    }

    fun setSyncWifiOnly(on: Boolean) {
        settings.setSyncWifiOnly(on)
        reschedule()
    }

    fun setSyncTwoWay(on: Boolean) = settings.setSyncTwoWay(on)

    fun syncNow(confirmDeletes: Boolean = false) = busy {
        val outcome = sync.syncNow(confirmDeletes)
        _state.update { it.copy(lastSync = outcome) }
    }

    fun disconnect() {
        sync.disconnect()
        SyncScheduler.cancelAll(context)
        _state.update { it.copy(driveConnected = false, driveStep = DriveStep.INTRODUCTION, lastSync = null) }
    }

    // ---- WebDAV ---------------------------------------------------------------------------------

    /**
     * Saves the account and immediately proves it works.
     *
     * A form that accepts a typo and only fails hours later inside a background job is a form that
     * has told the user nothing, so the credentials are exercised against the server before this
     * counts as set up.
     */
    fun connectWebDav(url: String, user: String, password: String) = busy {
        val normalised = url.trim().trimEnd('/')
        val probe = withContext(Dispatchers.IO) {
            runCatching {
                val backend = WebDavBackend(WebDavAccount(normalised, user.trim(), password))
                backend.ensureRoot()
                backend.listTree()
            }
        }
        if (probe.isFailure || probe.getOrNull()?.complete != true) {
            _state.update {
                it.copy(webDavProbe = probe.exceptionOrNull()?.message ?: "The server could not be reached")
            }
            return@busy
        }
        settings.setWebDav(normalised, user.trim())
        sync.saveWebDavPassword(password)
        settings.setSyncProvider(SyncProvider.WEBDAV)
        reschedule()
        _state.update { it.copy(webDavProbe = null, message = "Connected") }
    }

    // ---- Drive ----------------------------------------------------------------------------------

    fun driveStep(step: DriveStep) = _state.update { it.copy(driveStep = step) }

    fun saveDriveCredentials(clientId: String, clientSecret: String) {
        settings.setDriveClientId(clientId.trim())
        sync.saveDriveSecret(clientSecret.trim())
        _state.update { it.copy(driveStep = DriveStep.CREDENTIALS) }
    }

    /**
     * Opens the browser and waits on a loopback socket for the redirect.
     *
     * No Google Play Services anywhere in it: the browser does the interactive part, and a one-shot
     * socket on `127.0.0.1` catches the answer. That is what makes this work on a device with no
     * Google apps installed at all.
     */
    fun beginDriveAuthorization(open: (String) -> Unit) {
        val credential = sync.driveCredential()
        if (credential == null || !credential.isUsable) {
            _state.update { it.copy(message = "Enter a client ID first", driveStep = DriveStep.CREDENTIALS) }
            return
        }
        val challenge = PkceChallenge.generate().also { pkce = it }
        val listener = GoogleOAuth.LoopbackCatcher().also { catcher = it }
        val url = GoogleOAuth.authorizationUrl(credential, challenge, listener.redirectUri)
        _state.update { it.copy(driveStep = DriveStep.AUTHORIZING, pendingAuthorizationUrl = url) }
        open(url)

        viewModelScope.launch {
            val code = listener.awaitCode()
            listener.close()
            catcher = null
            if (code == null) {
                _state.update {
                    it.copy(driveStep = DriveStep.CREDENTIALS, pendingAuthorizationUrl = null, message = "Not connected")
                }
                return@launch
            }
            finishDriveAuthorization(credential, challenge, listener.redirectUri, code)
        }
    }

    private suspend fun finishDriveAuthorization(
        credential: GoogleCredential,
        challenge: PkceChallenge,
        redirectUri: String,
        code: String,
    ) {
        val tokens = GoogleOAuth.exchange(credential, challenge, redirectUri, code)
        tokens.onSuccess { granted ->
            sync.saveDriveRefreshToken(granted.refreshToken)
            settings.setSyncProvider(SyncProvider.DRIVE)
            reschedule()
            _state.update {
                it.copy(
                    driveStep = DriveStep.CONNECTED,
                    driveConnected = true,
                    pendingAuthorizationUrl = null,
                    message = null,
                )
            }
        }.onFailure { cause ->
            _state.update {
                it.copy(
                    driveStep = DriveStep.CREDENTIALS,
                    pendingAuthorizationUrl = null,
                    message = cause.message ?: "Could not complete sign-in",
                )
            }
        }
    }

    fun cancelDriveAuthorization() {
        catcher?.close()
        catcher = null
        _state.update { it.copy(driveStep = DriveStep.CREDENTIALS, pendingAuthorizationUrl = null) }
    }

    fun acknowledge() = _state.update {
        it.copy(message = null, lastBackup = null, lastSync = null, lastRestore = null, webDavProbe = null)
    }

    override fun onCleared() {
        catcher?.close()
    }

    private fun reschedule() {
        val current = settings.read()
        if (current.backupEnabled && current.backupDestination != null) {
            SyncScheduler.scheduleBackup(
                context, current.backupFrequency, current.backupOnCharging, current.backupWifiOnly,
            )
        } else {
            SyncScheduler.scheduleBackup(context, SyncFrequency.MANUAL, false, false)
        }
        if (current.syncProvider != SyncProvider.NONE) {
            SyncScheduler.scheduleSync(context, current.syncFrequency, current.syncWifiOnly)
        } else {
            SyncScheduler.scheduleSync(context, SyncFrequency.MANUAL, false)
        }
    }

    private fun busy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { cause ->
                _state.update { it.copy(message = cause.message ?: "Something went wrong") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        fun factory(
            context: Context,
            settings: SettingsRepository,
            backup: BackupRunner,
            sync: SyncCoordinator,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BackupSyncViewModel(context.applicationContext, settings, backup, sync) as T
        }
    }
}
