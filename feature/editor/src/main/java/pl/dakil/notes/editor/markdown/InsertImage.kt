package pl.dakil.notes.editor.markdown

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.dakil.notes.data.ImageImport
import pl.dakil.notes.data.ImageInsertMode
import pl.dakil.notes.data.ImportedImage
import pl.dakil.notes.editor.R
import pl.dakil.notes.ui.icons.NotesIcons

/** Which step of inserting an image is on screen. */
internal enum class ImageInsertStep { CHOOSE, LINK }

/**
 * The one way every screen inserts an image: from the device or from an address.
 *
 * [start] is what a tap on an image button does — the choice, or the photo picker straight away,
 * as the user set it — and [startLink] what a long press does in the second case. The dialogs are
 * drawn by [InsertImageDialogs]; the screen decides only what to do with what comes back.
 */
@Stable
class ImageInsertState internal constructor(
    private val mode: () -> ImageInsertMode,
    private val pick: () -> Unit,
) {
    internal var step by mutableStateOf<ImageInsertStep?>(null)

    fun start() {
        if (mode() == ImageInsertMode.PICKER) pick() else step = ImageInsertStep.CHOOSE
    }

    fun startLink() {
        step = ImageInsertStep.LINK
    }

    /** Whether a long press has anything to do — only when a tap no longer offers the address. */
    val longPressOpensLink: Boolean get() = mode() == ImageInsertMode.PICKER

    internal fun pickFromDevice() {
        step = null
        pick()
    }
}

/**
 * Remembers an [ImageInsertState] whose photo picker hands [onPicked] an image ready to store.
 *
 * The picked file is read and, if it needs it, scaled down off the main thread — a camera photo is
 * megabytes — so [onPicked] is called a moment after the picker closes, on the main thread.
 * [onFailed] is for a file that could not be read as an image at all.
 */
@Composable
fun rememberImageInsertState(
    mode: ImageInsertMode,
    onPicked: (ImportedImage) -> Unit,
    onFailed: () -> Unit,
): ImageInsertState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentMode by rememberUpdatedState(mode)
    val currentPicked by rememberUpdatedState(onPicked)
    val currentFailed by rememberUpdatedState(onFailed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        scope.launch {
            val image = withContext(Dispatchers.IO) { runCatching { ImageImport.read(resolver, uri) }.getOrNull() }
            if (image != null) currentPicked(image) else currentFailed()
        }
    }
    return remember(launcher) {
        ImageInsertState(
            mode = { currentMode },
            pick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

/**
 * The dialogs behind [ImageInsertState]: the two-way choice, and the address form.
 *
 * The choice is two list rows and a Cancel, nothing more. A photo — the common case — is one tap
 * away and asks for nothing; only an image by address needs fields, so only that path shows any.
 */
@Composable
fun InsertImageDialogs(
    state: ImageInsertState,
    onLink: (alt: String, url: String) -> Unit,
) {
    when (state.step) {
        ImageInsertStep.CHOOSE -> AlertDialog(
            onDismissRequest = { state.step = null },
            icon = { Icon(NotesIcons.Image, contentDescription = null) },
            title = { Text(stringResource(R.string.markdown_insert_image)) },
            text = {
                Column {
                    ChoiceRow(
                        headline = stringResource(R.string.markdown_image_from_device),
                        supporting = stringResource(R.string.markdown_image_from_device_detail),
                        icon = { Icon(NotesIcons.Image, contentDescription = null) },
                        onClick = state::pickFromDevice,
                    )
                    ChoiceRow(
                        headline = stringResource(R.string.markdown_image_from_link),
                        supporting = stringResource(R.string.markdown_image_from_link_detail),
                        icon = { Icon(NotesIcons.Link, contentDescription = null) },
                        onClick = { state.step = ImageInsertStep.LINK },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { state.step = null }) { Text(stringResource(R.string.editor_cancel)) }
            },
        )

        ImageInsertStep.LINK -> ReferenceDialog(
            kind = ReferenceKind.IMAGE,
            initialLabel = "",
            onDismiss = { state.step = null },
            onConfirm = { alt, url ->
                state.step = null
                onLink(alt, url)
            },
        )

        null -> Unit
    }
}

@Composable
private fun ChoiceRow(
    headline: String,
    supporting: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = icon,
        // The dialog's own surface shows through, so the rows read as part of it, not as cards on it.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
