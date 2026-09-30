package pl.dakil.notes.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.dakil.notes.R

/**
 * What happened, once something that took a while has finished.
 *
 * A dialog rather than a snackbar: these run from a settings screen the user may have navigated
 * away from and back to, and "your backup failed" is not a message to show for four seconds and
 * then discard.
 */
@Composable
fun OutcomeDialog(message: String?, title: String, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
