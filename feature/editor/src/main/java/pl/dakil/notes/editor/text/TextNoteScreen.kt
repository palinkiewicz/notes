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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import pl.dakil.notes.editor.SaveViewOnLeave
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.withContext
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.editor.R
import pl.dakil.notes.editor.export.ExportSheet
import pl.dakil.notes.editor.export.TextPageExporter
import pl.dakil.notes.editor.export.ExportAction
import pl.dakil.notes.editor.export.rememberExportSaver
import pl.dakil.notes.editor.export.exportText
import pl.dakil.notes.editor.export.shareExport
import pl.dakil.notes.editor.markdown.InsertImageDialogs
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.rememberImageInsertState
import pl.dakil.notes.editor.markdown.FormatPopup
import pl.dakil.notes.editor.markdown.MarkdownEditor
import pl.dakil.notes.editor.markdown.MarkdownFormatBar
import pl.dakil.notes.editor.markdown.MarkdownFormatRail
import pl.dakil.notes.editor.markdown.PendingStyles
import pl.dakil.notes.editor.markdown.ReferenceDialog
import pl.dakil.notes.editor.markdown.ReferenceKind
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutContext
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.dialog.TagEditorDialog
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.keys.ShortcutHandler

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

    // Formatting keys belong to the field and are handled there; these are the note's own.
    ShortcutHandler(ShortcutContext.TEXT) { action ->
        when (action) {
            ShortcutAction.UNDO -> undo.canUndo.also { if (it) undo.undo() }
            ShortcutAction.REDO -> undo.canRedo.also { if (it) undo.redo() }
            ShortcutAction.SAVE -> viewModel.flush() != null
            ShortcutAction.CLOSE_NOTE -> true.also { onNavigateBack() }
            ShortcutAction.EXPORT -> true.also { exportOpen = true }
            else -> false
        }
    }
    val coroutineScope = rememberCoroutineScope()

    // A message that came up from the store already has its own words; the fallback arrives as a
    // resource id, because the view model has no `Context` to resolve one with.
    val errorMessage = state.error ?: state.errorRes?.let { stringResource(it) }

    val imageInsert = rememberImageInsertState(
        mode = state.imageInsertMode,
        onPicked = viewModel::insertImage,
        onFailed = viewModel::imageFailed,
    )


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

    // Whether the last save landed, until the snackbar has said so. Set by a failed render as well:
    // an export that silently does nothing reads as a button that is broken.
    var exportOutcome by remember { mutableStateOf<Boolean?>(null) }
    val saveExport = rememberExportSaver { exportOutcome = it }
    if (exportOpen) {
        // Surveyed once per opening of the sheet, off the main thread: it lays the whole note out.
        val survey by produceState<TextPageExporter.Survey?>(null) {
            val markdown = text.text.toString()
            value = withContext(Dispatchers.Default) {
                runCatching { TextPageExporter.survey(context, markdown, state.pandoc, viewModel) }
                    .getOrDefault(TextPageExporter.Survey(pageCount = 1, hasImages = false))
            }
        }
        ExportSheet(
            isInk = false,
            exporting = exporting,
            pageCount = survey?.pageCount,
            initial = remember { viewModel.exportOptions() },
            hasImages = survey?.hasImages == true,
            pageSizePt = Size(TextPageExporter.PAGE_WIDTH_PT, TextPageExporter.PAGE_HEIGHT_PT),
            onOptionsChange = viewModel::rememberExportOptions,
            onDismiss = { if (!exporting) exportOpen = false },
            onExport = { options, action, pages ->
                val markdown = text.text.toString()
                exporting = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching { exportText(context, markdown, options, state.pandoc, pages, viewModel) }
                    }
                    exporting = false
                    exportOpen = false
                    result.fold(
                        onSuccess = { files ->
                            if (action == ExportAction.SAVE) saveExport(files, title)
                            else shareExport(context, files)
                        },
                        onFailure = { exportOutcome = false },
                    )
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
        )
    }

    InsertImageDialogs(imageInsert, onLink = viewModel::insertImageLink)

    // A conflict is not a failure: the note saved, and the version that was on disk was kept
    // beside it. `error` is the wrong channel — it replaces the whole editor — so this gets the
    // stock transient surface instead, which is also the one place the copy's name can be read.
    val snackbarHostState = remember { SnackbarHostState() }
    val conflict = state.saveState as? SaveState.Conflicted
    val conflictMessage = conflict?.let { stringResource(R.string.editor_conflict_saved, it.copyName) }
    LaunchedEffect(conflictMessage) {
        conflictMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    val imageFailure = if (state.imageFailed) stringResource(R.string.editor_error_image) else null
    LaunchedEffect(imageFailure) {
        imageFailure ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(imageFailure)
        viewModel.dismissImageFailure()
    }

    val exportMessage = exportOutcome?.let {
        stringResource(if (it) R.string.editor_export_saved else R.string.editor_export_failed)
    }
    LaunchedEffect(exportMessage) {
        exportMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(exportMessage)
        exportOutcome = null
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
                    onInsertImage = imageInsert::start,
                    onInsertImageLink = if (imageInsert.longPressOpensLink) imageInsert::startLink else null,
                    // A `.md` file has to stay one every other editor renders sensibly, so the size
                    // and colour controls are offered only where the user has asked for them.
                    attributes = state.pandoc,
                    savedColors = state.savedColors,
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
                    // Opened where it was left, when the user asked for that. `maxValue` reads
                    // `Int.MAX_VALUE` until the field has been measured, and scrolling before then
                    // would land on a guess.
                    //
                    // The first measurement is not the last: images load after the text, and until
                    // each one does it is a line tall. Scrolling once then clamped the saved spot
                    // to a note with no pictures in it. So the view follows the note as it grows,
                    // until it reaches the spot — or until the user scrolls, which is them choosing
                    // somewhere else, or the pictures stop arriving.
                    val scroll = rememberScrollState()
                    LaunchedEffect(Unit) {
                        val saved = viewModel.savedScroll() ?: return@LaunchedEffect
                        var placed = -1
                        withTimeoutOrNull(RESTORE_WINDOW_MS) {
                            snapshotFlow { scroll.maxValue }
                                .filter { it != Int.MAX_VALUE }
                                .takeWhile { placed < 0 || (placed < saved && scroll.value == placed) }
                                .collect { max ->
                                    scroll.scrollTo(saved.coerceAtMost(max))
                                    placed = scroll.value
                                }
                        }
                    }
                    SaveViewOnLeave { viewModel.rememberScroll(scroll.value) }
                    if (expanded) {
                        MarkdownFormatRail(
                            state = text,
                            pending = pending,
                            openPopup = popup,
                            onPopupChange = { popup = it },
                            onInsertLink = { reference = ReferenceKind.LINK },
                            onInsertImage = imageInsert::start,
                            onInsertImageLink = if (imageInsert.longPressOpensLink) imageInsert::startLink else null,
                            attributes = state.pandoc,
                            savedColors = state.savedColors,
                        )
                    }
                    MarkdownEditor(
                        state = text,
                        sourceMode = state.sourceMode,
                        pending = pending,
                        modifier = Modifier.fillMaxSize(),
                        scroll = scroll,
                        // Only a note with nothing in it opens ready to type.
                        autoFocus = text.text.isEmpty(),
                        attributes = state.pandoc,
                        images = viewModel,
                        onRequestLink = { reference = ReferenceKind.LINK },
                    )
                }
            }
        }
    }
}

/** How long a reopened note keeps following its images down to where it was left. */
private const val RESTORE_WINDOW_MS = 5_000L
