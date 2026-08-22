package pl.dakil.notes.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * Asks for a name before making a folder.
 *
 * Unlike a new note, which is created as "Untitled" and named by whatever the user types into it, a
 * folder has nothing inside it to name it after — so the name is the whole act of creating one.
 */
@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NotesIcons.NewFolder, contentDescription = null) },
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (name.isNotBlank()) onConfirm(name.trim())
                }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirms a delete.
 *
 * There is no undo and no trash: the files go. A folder takes everything inside it, which is the
 * part worth spelling out — the row on screen says "Recipes" and gives no hint of the forty notes
 * under it.
 */
@Composable
fun ConfirmDeleteDialog(
    items: List<LibraryItem>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val folders = items.count { it is LibraryItem.Folder }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NotesIcons.Delete, contentDescription = null) },
        title = {
            Text(
                if (items.size == 1) "Delete “${items.first().name.ifBlank { "Untitled" }}”?"
                else "Delete ${items.size} items?"
            )
        },
        text = {
            Column {
                if (folders > 0) {
                    Text(
                        if (folders == 1 && items.size == 1) "Everything inside this folder goes too."
                        else "Everything inside the selected folders goes too."
                    )
                }
                Text(
                    text = "This cannot be undone.",
                    modifier = Modifier.padding(top = if (folders > 0) 8.dp else 0.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}