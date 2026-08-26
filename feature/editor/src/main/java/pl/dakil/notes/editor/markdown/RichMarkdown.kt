package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import pl.dakil.notes.editor.R

/**
 * The Markdown engine: Compose Rich Editor, wrapped once for the whole app.
 *
 * Everything the editor knows about what Markdown looks like and what a keystroke means now lives
 * in [RichTextState]. This file is the only place that touches the library, so the `.md` note
 * screen and the sheet's text boxes cannot end up rendering the same document two ways — the same
 * rule the hand-rolled renderer was built around, kept for the same reason.
 *
 * ### Rich text, not source text
 *
 * The old engine edited the *source*: a caret sat between two asterisks and the syntax was merely
 * hidden. This one edits a document, and Markdown is what it is serialised to on the way out. Two
 * consequences worth knowing about rather than discovering:
 *
 * - The offsets a caret moves through are the reader's characters, not the file's. There is no
 *   source mode any more, because there is no source to put a caret into.
 * - What survives a save is what [RichTextState.toMarkdown] can write. Font size, text colour and
 *   highlight are real while the document is open and are *dropped* when it is written, so no
 *   control here offers them. See the note in `MarkdownFormatBar`.
 */

/** A state seeded from [markdown], rebuilt when [key] says a different document has been opened. */
@Composable
fun rememberRichMarkdown(markdown: String, key: Any?): RichTextState {
    val state = rememberRichTextState()
    // Keyed on the document rather than on the text: re-seeding on every keystroke would fight the
    // user for the caret. Only opening a *different* note reloads the field.
    LaunchedEffect(key) { state.setMarkdown(markdown) }
    return state
}

/**
 * A Markdown document in one editable field.
 *
 * Shared by every caller in the app: the `.md` note screen wraps it in a page of its own, and each
 * text box on a sheet mounts one when the user taps into it. The parts that differ between them are
 * parameters here, because the parts that do not differ are exactly the ones that must never
 * disagree between one kind of note and the other.
 */
@Composable
fun RichMarkdownEditor(
    state: RichTextState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /**
     * Whether mounting this takes the caret.
     *
     * True by default because that is what every caller but one wants: a text box on a sheet is
     * mounted *because* the user just tapped into it, and a box that appeared without a keyboard
     * would need a second tap to type in. The `.md` screen is the exception and says so.
     */
    autoFocus: Boolean = true,
    readOnly: Boolean = false,
    /** Where the caret is, so whoever owns the scrolling can bring it into view. */
    onCaretBounds: ((top: Float, bottom: Float) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Line height left unspecified: with it fixed, a heading's larger glyphs are clipped by the
    // body line height. The same reason the previous engine left it so.
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = colors.onSurface,
        lineHeight = TextUnit.Unspecified,
    )

    // Keyed on nothing, so it fires once for this mounting and not again when the flag is read
    // differently on a later recomposition.
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }

    // The caret band is reported from the layout rather than watched as state, so a caret move
    // costs a read of a result the field has already measured.
    LaunchedEffect(layout, state.selection) {
        val result = layout ?: return@LaunchedEffect
        val report = onCaretBounds ?: return@LaunchedEffect
        val at = state.selection.start.coerceIn(0, result.layoutInput.text.length)
        val line = result.getLineForOffset(at)
        report(result.getLineTop(line), result.getLineBottom(line))
    }

    Box(modifier) {
        if (placeholder.isNotEmpty() && state.annotatedString.isEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicRichTextEditor(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            readOnly = readOnly,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            onTextLayout = { layout = it },
        )
    }
}

/**
 * Markdown rendered for reading rather than for editing.
 *
 * A sheet can carry any number of text boxes and only one of them is ever being typed in, so the
 * rest are drawn without a field behind them. That is what keeps a tap on a box a tap on a *box*,
 * to be routed by the tool in the user's hand, rather than something a field has already swallowed
 * to place a caret.
 */
@Composable
fun RichMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val colors = MaterialTheme.colorScheme
    // One state per box, seeded from the box's own text. Heavier than the render plan this
    // replaced — a parsed document per box rather than a shared list of spans — but the library
    // draws list markers and code spans itself, and there is no way to reach them without one.
    val state = rememberRichTextState()
    LaunchedEffect(markdown) { state.setMarkdown(markdown) }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = colors.onSurface,
        lineHeight = TextUnit.Unspecified,
    )

    Box(modifier.fillMaxWidth()) {
        if (markdown.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicRichText(state = state, modifier = Modifier.fillMaxWidth(), style = textStyle)
    }
}

internal enum class ReferenceKind { LINK, IMAGE }

/**
 * The link and image dialog.
 *
 * An address is asked for up front because the formatted view hides it: unlike every other control
 * on the bar, this is one the user could not finish afterwards by typing.
 */
@Composable
internal fun ReferenceDialog(
    kind: ReferenceKind,
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (label: String, url: String) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (kind == ReferenceKind.LINK) R.string.markdown_insert_link
                    else R.string.markdown_insert_image,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = {
                        Text(
                            stringResource(
                                if (kind == ReferenceKind.LINK) R.string.markdown_link_text
                                else R.string.markdown_image_description,
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.markdown_address)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label, url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.markdown_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}
