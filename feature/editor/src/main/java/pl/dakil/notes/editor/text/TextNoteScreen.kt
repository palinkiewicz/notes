package pl.dakil.notes.editor.text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import pl.dakil.notes.data.SaveState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.editor.R
import pl.dakil.notes.editor.export.ExportSheet
import pl.dakil.notes.editor.export.exportText
import pl.dakil.notes.editor.export.shareExport
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.FormatPopup
import pl.dakil.notes.editor.markdown.MarkdownEditor
import pl.dakil.notes.editor.markdown.MarkdownFormatBar
import pl.dakil.notes.editor.markdown.MarkdownFormatRail
import pl.dakil.notes.editor.markdown.PendingStyles
import pl.dakil.notes.editor.markdown.ReferenceDialog
import pl.dakil.notes.editor.markdown.ReferenceKind
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.dialog.TagEditorDialog
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The plain-Markdown note editor: a page, a formatting bar, and one [MarkdownEditor] filling it.
 *
 * Everything about how Markdown reads and edits lives in that component, which the sheet's text
 * boxes mount too. What is left here is what a `.md` *file* needs and a box does not: a title that
 * is the file's own name, a source-mode toggle, and undo that belongs to the field because the
 * field is the whole document.
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
    onRenamed: (StoreRef) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text = viewModel.text
    val undo = text.undoState

    var popup by remember { mutableStateOf<FormatPopup?>(null) }
    // Screen state, not document state: it holds what the bar has been asked for at a bare caret
    // until there is text to put it round, and the field is the other half of that. See
    // [PendingStyles].
    val pending = remember { PendingStyles() }
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
                val markdown = viewModel.expandedText()
                exporting = true
                coroutineScope.launch(Dispatchers.Default) {
                    val result = runCatching { exportText(context, markdown, format, state.pandoc) }
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
            onConfirmDeviceImage = { label, mimeType, base64 ->
                reference = null
                val before = text.text.toString()
                val result = viewModel.insertBase64Image(
                    text = before,
                    start = text.selection.start,
                    end = text.selection.end,
                    alt = label,
                    mimeType = mimeType,
                    base64Data = base64,
                )
                text.edit {
                    replace(0, length, result.text)
                    selection = TextRange(result.selectionStart, result.selectionEnd)
                }
            },
        )
    }

    // A conflict is not a failure: the note saved, and the version that was on disk was kept
    // beside it. `error` is the wrong channel — it replaces the whole editor — so this gets the
    // stock transient surface instead, which is also the one place the copy's name can be read.
    val snackbarHostState = remember { SnackbarHostState() }
    val conflict = state.saveState as? SaveState.Conflicted
    val conflictMessage = conflict?.let { stringResource(R.string.editor_conflict_saved, it.copyName) }
    LaunchedEffect(conflictMessage) {
        conflictMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // On the whole scaffold rather than on the field: the formatting bar is the one control the
        // user needs *while* the keyboard is up, so the bar has to rise with it.
        modifier = modifier.fillMaxSize().imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    FilledIconToggleButton(
                        checked = state.sourceMode,
                        onCheckedChange = viewModel::setSourceMode,
                    ) {
                        Icon(
                            imageVector = if (state.sourceMode) NotesIcons.Preview else NotesIcons.Source,
                            contentDescription = stringResource(
                                if (state.sourceMode) R.string.editor_show_formatted
                                else R.string.editor_edit_source,
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded && !state.isLoading) {
                MarkdownFormatBar(
                    state = text,
                    pending = pending,
                    openPopup = popup,
                    onPopupChange = { popup = it },
                    onInsertLink = { reference = ReferenceKind.LINK },
                    onInsertImage = { reference = ReferenceKind.IMAGE },
                    // A `.md` file has to stay one every other editor renders sensibly, so the size
                    // and colour controls are offered only where the user has asked for them.
                    attributes = state.pandoc,
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
                            state = text,
                            pending = pending,
                            openPopup = popup,
                            onPopupChange = { popup = it },
                            onInsertLink = { reference = ReferenceKind.LINK },
                            onInsertImage = { reference = ReferenceKind.IMAGE },
                            attributes = state.pandoc,
                        )
                    }
                    MarkdownEditor(
                        state = text,
                        sourceMode = state.sourceMode,
                        pending = pending,
                        modifier = Modifier.fillMaxSize(),
                        // Only a note with nothing in it opens ready to type.
                        autoFocus = text.text.isEmpty(),
                        attributes = state.pandoc,
                        images = viewModel,
                    )
                }
            }
        }
    }
}
