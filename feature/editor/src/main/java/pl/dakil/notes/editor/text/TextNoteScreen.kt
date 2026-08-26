package pl.dakil.notes.editor.text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.editor.R
import pl.dakil.notes.editor.export.ExportSheet
import pl.dakil.notes.editor.export.exportText
import pl.dakil.notes.editor.export.shareExport
import pl.dakil.notes.editor.markdown.FormatPopup
import pl.dakil.notes.editor.markdown.MarkdownFormatBar
import pl.dakil.notes.editor.markdown.MarkdownFormatRail
import pl.dakil.notes.editor.markdown.ReferenceDialog
import pl.dakil.notes.editor.markdown.ReferenceKind
import pl.dakil.notes.editor.markdown.RichMarkdownEditor
import pl.dakil.notes.editor.markdown.rememberRichMarkdown
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.dialog.TagEditorDialog
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The plain-Markdown note editor: a page, a formatting bar, and one [RichMarkdownEditor] filling it.
 *
 * Everything about how Markdown reads and edits lives in that component, which the sheet's text
 * boxes mount too. What is left here is what a `.md` *file* needs and a box does not: a title that
 * is the file's own name, and undo that belongs to the editor because the editor is the whole
 * document.
 *
 * The document itself is the editor's `RichTextState`, created here because that is Compose state.
 * The view model holds only the body it loaded and the Markdown this screen hands back, which is
 * why the serialise-and-report effect below is the one thing standing between a keystroke and the
 * file on disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextNoteScreen(
    viewModel: TextNoteViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onRenamed: (StoreRef) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rich = rememberRichMarkdown(viewModel.loaded, viewModel.documentKey)
    val undo = rich.history

    // Every edit, serialised back to Markdown for the view model to autosave. Keyed on the
    // annotated string rather than on a change callback because the library has no edit hook: this
    // is the one place that turns "the document changed" into "these are the bytes".
    LaunchedEffect(rich.annotatedString) { viewModel.onEdited(rich.toMarkdown()) }

    var popup by remember { mutableStateOf<FormatPopup?>(null) }
    var reference by remember { mutableStateOf<ReferenceKind?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    val title = state.title.ifBlank { stringResource(R.string.editor_untitled) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // A message that came up from the store already has its own words; the fallback arrives as a
    // resource id, because the view model has no `Context` to resolve one with.
    val errorMessage = state.error ?: state.errorRes?.let { stringResource(it) }


    if (renaming) {
        RenameNoteDialog(
            initial = title,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                viewModel.rename(name, onRenamed)
            },
        )
    }

    if (tagging) {
        TagEditorDialog(
            initial = state.tags,
            knownTags = state.knownTags,
            onDismiss = { tagging = false },
            onConfirm = { tags ->
                tagging = false
                viewModel.setTags(tags)
            },
        )
    }

    if (exportOpen) {
        ExportSheet(
            isInk = false,
            exporting = exporting,
            onDismiss = { if (!exporting) exportOpen = false },
            onExport = { _, format ->
                val markdown = rich.toMarkdown()
                exporting = true
                coroutineScope.launch(Dispatchers.Default) {
                    val result = runCatching { exportText(context, markdown, format) }
                    exporting = false
                    exportOpen = false
                    result.getOrNull()?.let { shareExport(context, it) }
                }
            },
        )
    }

    // The selector takes no focus, so back would otherwise leave the editor with one open.
    BackHandler(enabled = popup != null) { popup = null }

    reference?.let { kind ->
        ReferenceDialog(
            kind = kind,
            initialLabel = rich.annotatedString.text
                .substring(rich.selection.min, rich.selection.max),
            onDismiss = { reference = null },
            onConfirm = { label, url ->
                reference = null
                insertReference(rich, kind, label, url)
            },
        )
    }

    Scaffold(
        // On the whole scaffold rather than on the field: the formatting bar is the one control the
        // user needs *while* the keyboard is up, so the bar has to rise with it.
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                // Tapping the name renames the note, matching the ink editor and the library's
                // three-dot menu. For a `.md` note the name *is* the title: there is nowhere else
                // one could be kept.
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { renaming = true }
                            .padding(vertical = 8.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.editor_back))
                    }
                },
                actions = {
                    IconButton(onClick = { undo.undo() }, enabled = undo.canUndo) {
                        Icon(NotesIcons.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = { undo.redo() }, enabled = undo.canRedo) {
                        Icon(NotesIcons.Redo, contentDescription = stringResource(R.string.editor_redo))
                    }
                    Box {
                        IconButton(onClick = { moreMenuOpen = true }) {
                            Icon(NotesIcons.More, contentDescription = stringResource(R.string.editor_more))
                        }
                        DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_tags)) },
                                leadingIcon = { Icon(NotesIcons.Tag, contentDescription = null) },
                                onClick = {
                                    moreMenuOpen = false
                                    tagging = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_export)) },
                                leadingIcon = { Icon(NotesIcons.Export, contentDescription = null) },
                                onClick = {
                                    moreMenuOpen = false
                                    exportOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded && !state.isLoading) {
                MarkdownFormatBar(
                    state = rich,
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

                errorMessage != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> Row(Modifier.fillMaxSize()) {
                    if (expanded) {
                        MarkdownFormatRail(
                            state = rich,
                            openPopup = popup,
                            onPopupChange = { popup = it },
                            onInsertLink = { reference = ReferenceKind.LINK },
                            onInsertImage = { reference = ReferenceKind.IMAGE },
                        )
                    }
                    RichMarkdownEditor(
                        state = rich,
                        modifier = Modifier.fillMaxSize(),
                        // Only a note with nothing in it opens ready to type.
                        autoFocus = viewModel.loaded.isEmpty(),
                    )
                }
            }
        }
    }
}


/**
 * Puts a link or an image into the document.
 *
 * A link is the library's own span — it stays a link when the note is reopened, and the bar can
 * light up on it. An image has no such call, so it goes in as the Markdown for one; the parser
 * reads `![alt](src)` back as an image span, which is what makes that round-trip.
 */
private fun insertReference(
    state: com.mohamedrejeb.richeditor.model.RichTextState,
    kind: ReferenceKind,
    label: String,
    url: String,
) = when (kind) {
    ReferenceKind.LINK -> state.addLink(label.ifBlank { url }, url)
    ReferenceKind.IMAGE -> state.insertMarkdownAfterSelection("![" + label + "](" + url + ")")
}
