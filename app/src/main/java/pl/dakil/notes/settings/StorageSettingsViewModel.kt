package pl.dakil.notes.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.dakil.notes.data.AdoptOutcome
import pl.dakil.notes.data.LibraryRootController
import pl.dakil.notes.data.LibraryRootKind
import pl.dakil.notes.data.SettingsRepository

/** What the storage screen has to say while a folder is being adopted. */
data class StorageUiState(
    val kind: LibraryRootKind = LibraryRootKind.INTERNAL,
    /** The chosen folder's tree URI, or null on app-private storage. */
    val root: String? = null,
    val busy: Boolean = false,
    /** The result of the last adopt, held until the user has seen it. */
    val outcome: AdoptOutcome? = null,
)

/**
 * The first settings screen that needs one of these.
 *
 * Every other settings page is a value in and a method reference out, because every other setting
 * is written the instant it is tapped. Adopting a folder is not: it copies a library, verifies it
 * and can fail halfway, and none of that fits a screen whose whole state is an [AppSettings].
 */
class StorageSettingsViewModel(
    private val controller: LibraryRootController,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        StorageUiState(kind = controller.kind, root = settings.read().libraryRoot)
    )
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    fun adopt(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, outcome = null) }
        viewModelScope.launch {
            // The grant is taken before anything is read: without it the folder is readable for
            // this one activity result and unreachable on the next launch, which would look like
            // the notes had vanished.
            runCatching { controller.takePermission(uri) }
            val outcome = controller.adopt(uri)
            _state.update {
                it.copy(
                    busy = false,
                    outcome = outcome,
                    kind = controller.kind,
                    root = settings.read().libraryRoot,
                )
            }
        }
    }

    fun revert() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, outcome = null) }
        viewModelScope.launch {
            controller.revertToInternal()
            _state.update {
                it.copy(busy = false, kind = controller.kind, root = settings.read().libraryRoot)
            }
        }
    }

    fun acknowledge() = _state.update { it.copy(outcome = null) }

    companion object {
        fun factory(
            controller: LibraryRootController,
            settings: SettingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StorageSettingsViewModel(controller, settings) as T
        }
    }
}
