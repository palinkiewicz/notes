package pl.dakil.notes.editor.text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownOutputTransformation
import pl.dakil.notes.editor.markdown.rememberMarkdownStyles
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.MonospaceStyle

/**
 * The plain-Markdown note editor.
 *
 * One text field, two presentations. By default the syntax is gone — headings are big, bold is bold,
 * a bullet is a bullet — and the formatting bar is how the user creates any of it. The top bar's
 * toggle swaps in the raw source in a monospace font for people who would rather type the markup
 * themselves. Both are the same field over the same string, so switching never costs an edit.
 */
// `undoState` is still an experimental foundation API; the alternative is a hand-rolled undo stack
// for a plain string, which is strictly worse code for the same behaviour.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TextNoteScreen(
    viewModel: TextNoteViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text = viewModel.text
    val undo = text.undoState

    var popup by remember { mutableStateOf<FormatPopup?>(null) }
    var reference by remember { mutableStateOf<ReferenceKind?>(null) }

    // The selector takes no focus, so back would otherwise leave the editor with one open.
    BackHandler(enabled = popup != null) { popup = null }

    reference?.let { kind ->
        ReferenceDialog(
            kind = kind,
            initialLabel = text.text.substring(text.selection.min, text.selection.max),
            onDismiss = { reference = null },
            onConfirm = { label, url ->
                reference = null
                val before = text.text.toString()
                val result = when (kind) {
                    ReferenceKind.LINK ->
                        MarkdownActions.insertLink(before, text.selection.start, text.selection.end, label, url)

                    ReferenceKind.IMAGE ->
                        MarkdownActions.insertImage(before, text.selection.start, text.selection.end, label, url)
                }
                text.edit {
                    replace(0, length, result.text)
                    selection = TextRange(result.selectionStart, result.selectionEnd)
                }
            },
        )
    }

    Scaffold(
        // On the whole scaffold rather than on the field: the formatting bar is the one control the
        // user needs *while* the keyboard is up, so the bar has to rise with it.
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { "Untitled" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { undo.undo() }, enabled = undo.canUndo) {
                        Icon(NotesIcons.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { undo.redo() }, enabled = undo.canRedo) {
                        Icon(NotesIcons.Redo, contentDescription = "Redo")
                    }
                    FilledIconToggleButton(
                        checked = state.sourceMode,
                        onCheckedChange = viewModel::setSourceMode,
                    ) {
                        Icon(
                            imageVector = if (state.sourceMode) NotesIcons.Preview else NotesIcons.Source,
                            contentDescription = if (state.sourceMode) {
                                "Show formatted text"
                            } else {
                                "Edit Markdown source"
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded && !state.isLoading) {
                MarkdownFormatBar(
                    state = text,
                    openPopup = popup,
                    onPopupChange = { popup = it },
                    onInsertLink = { reference = ReferenceKind.LINK },
                    onInsertImage = { reference = ReferenceKind.IMAGE },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> Row(Modifier.fillMaxSize()) {
                    if (expanded) {
                        MarkdownFormatRail(
                            state = text,
                            openPopup = popup,
                            onPopupChange = { popup = it },
                            onInsertLink = { reference = ReferenceKind.LINK },
                            onInsertImage = { reference = ReferenceKind.IMAGE },
                        )
                    }
                    MarkdownField(
                        viewModel = viewModel,
                        sourceMode = state.sourceMode,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownField(
    viewModel: TextNoteViewModel,
    sourceMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.text
    val colors = MaterialTheme.colorScheme
    val styles = rememberMarkdownStyles()
    val transformation = remember(styles) { MarkdownOutputTransformation(styles) }
    val focusRequester = remember { FocusRequester() }

    val textStyle = if (sourceMode) {
        MonospaceStyle.copy(color = colors.onSurface)
    } else {
        // Line height is left unspecified on purpose: with it fixed, a heading's larger glyphs get
        // clipped by the body line height, and the whole point of this mode is that a heading looks
        // like a heading.
        MaterialTheme.typography.bodyLarge.copy(
            color = colors.onSurface,
            lineHeight = TextUnit.Unspecified,
        )
    }

    BasicTextField(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            // Top only. A bottom margin here is not a margin, it is a strip of page the text can
            // never reach — the bar below already separates the two.
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        lineLimits = TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        inputTransformation = ContinueList,
        // Null in source mode: that *is* the source, unchanged and unhidden.
        outputTransformation = if (sourceMode) null else transformation,
        decorator = TextFieldDecorator { field ->
            Box(Modifier.fillMaxWidth()) {
                if (state.text.isEmpty()) {
                    Text(
                        text = "Write something…",
                        style = textStyle,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                field()
            }
        },
    )

    // Only a note with nothing in it opens ready to type. Throwing the keyboard up over a note the
    // user came back to *read* costs them half the page and a tap to get it back.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state.text.isEmpty()) focusRequester.requestFocus()
    }
}

private enum class ReferenceKind { LINK, IMAGE }

@Composable
private fun ReferenceDialog(
    kind: ReferenceKind,
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (label: String, url: String) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == ReferenceKind.LINK) "Insert link" else "Insert image") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(if (kind == ReferenceKind.LINK) "Text" else "Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Address") },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label, url) },
                // An address is the one part that cannot be filled in later from the formatted
                // view, because the formatted view hides it.
                enabled = url.isNotBlank(),
            ) {
                Text("Insert")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
