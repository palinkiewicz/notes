package pl.dakil.notes.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.R
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * Adds and removes a note's tags.
 *
 * Chips plus one field rather than a comma-separated text box: a tag is a thing you either have or
 * do not, and typing them as a list makes a typo in the middle silently invent a new one. The field
 * commits on Enter and on the add button, so a burst of tags is one gesture each.
 *
 * Shared by both editors, because a tag means the same thing on a sheet as in a `.md` file even
 * though the two keep it in completely different places — a manifest and a frontmatter block.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val tags = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var draft by remember { mutableStateOf("") }

    fun commitDraft() {
        val tag = draft.trim()
        // Silently ignoring a duplicate rather than warning: the user asked for the note to have
        // this tag, and it does.
        if (tag.isNotEmpty() && tags.none { it.equals(tag, ignoreCase = true) }) tags += tag
        draft = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NotesIcons.Tag, contentDescription = null) },
        title = { Text(stringResource(R.string.ui_tags_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_tags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (tag in tags.toList()) {
                            InputChip(
                                selected = false,
                                onClick = { tags.remove(tag) },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = NotesIcons.Close,
                                        contentDescription = stringResource(R.string.ui_tags_remove_x, tag),
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ui_tags_add_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitDraft() }),
                    trailingIcon = {
                        if (draft.isNotBlank()) {
                            IconButton(onClick = { commitDraft() }) {
                                Icon(
                                    imageVector = NotesIcons.Add,
                                    contentDescription = stringResource(R.string.ui_tags_add),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // A tag typed but never committed is one the user meant to add; losing it because
                // they reached for Save instead of Enter would be a small betrayal.
                commitDraft()
                onConfirm(tags.toList())
            }) { Text(stringResource(R.string.ui_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}
