package pl.dakil.notes.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import pl.dakil.notes.data.SaveState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import pl.dakil.notes.editor.export.exportInk
import pl.dakil.notes.editor.export.shareExport
import pl.dakil.notes.editor.markdown.FormatPopup
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownFormatBar
import pl.dakil.notes.editor.markdown.MarkdownFormatRail
import pl.dakil.notes.editor.markdown.ReferenceDialog
import pl.dakil.notes.editor.markdown.ReferenceKind
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.ui.color.ColorPickerSheet
import pl.dakil.notes.ui.dialog.RenameNoteDialog
import pl.dakil.notes.ui.dialog.TagEditorDialog
import pl.dakil.notes.ui.icons.NotesIcons

/**
 * The note editor.
 *
 * One surface, not two modes: [SheetEditor] draws paper, text and ink together. The app bar's
 * segmented control chooses only how the *paper* is presented — as discrete pages or as one
 * continuous scroll — which never changes the document or where it will break when printed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: NoteViewModel,
    onNavigateBack: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onRenamed: (StoreRef) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pageSetupOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var editingPen by remember { mutableStateOf<ToolId?>(null) }
    var editingPageColor by remember { mutableStateOf<PageColorTarget?>(null) }
    // Held here rather than in the toolbar: the sheet and the app bar close it too.
    var toolPopup by remember { mutableStateOf<ToolPopup?>(null) }
    var formatPopup by remember { mutableStateOf<FormatPopup?>(null) }
    var reference by remember { mutableStateOf<ReferenceKind?>(null) }
    var imageTapPosition by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val sheet = state.sheet
    val title = state.note?.meta?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.editor_untitled)

    // A message that came up from the store already has its own words; the fallback arrives as a
    // resource id, because the view model has no `Context` to resolve one with.
    val errorMessage = state.error ?: state.errorRes?.let { stringResource(it) }


    // The selectors do not take focus, so back would otherwise leave the editor with one open.
    BackHandler(enabled = toolPopup != null || formatPopup != null) {
        toolPopup = null
        formatPopup = null
    }

    if (pageSetupOpen && sheet != null) {
        PageSetupSheet(
            size = sheet.format.size,
            background = sheet.format.background,
            margins = sheet.format.margins,
            onSizeChange = viewModel::setPageSize,
            onBackgroundChange = viewModel::setPageBackground,
            onMarginsChange = viewModel::setMargins,
            onEditColor = { target ->
                pageSetupOpen = false
                editingPageColor = target
            },
            pageCount = sheet.pageCount(),
            view = state.view,
            onViewChange = viewModel::setView,
            unit = state.measurementUnit,
            onDismiss = { pageSetupOpen = false },
        )
    }

    // Pen colour, applied live so the swatch and the next stroke follow the drag.
    editingPen?.let { tool ->
        val spec = state.toolPresets.firstOrNull { it.tool == tool } ?: ToolSpec.defaultFor(tool)
        ColorPickerSheet(
            title = stringResource(R.string.editor_pen_colour),
            initial = spec.color,
            presets = ColorCodec.INK_PRESETS,
            recents = state.recentColors,
            onColorChange = { viewModel.setToolColor(tool, it) },
            onCommit = viewModel::rememberColor,
            onDismiss = { editingPen = null },
        )
    }

    // Page colours, applied live onto the document so the sheet under the picker updates as you
    // drag — the only way to judge paper and rule colours honestly.
    editingPageColor?.let { target ->
        val background = sheet?.format?.background ?: return@let
        val lines = target == PageColorTarget.LINES || target == PageColorTarget.MARGIN
        val current = when (target) {
            PageColorTarget.PAPER -> background.color
            PageColorTarget.LINES -> background.pattern.color
            PageColorTarget.MARGIN -> background.pattern.marginColor
        }
        ColorPickerSheet(
            title = when (target) {
                PageColorTarget.PAPER -> stringResource(R.string.editor_paper_colour)
                PageColorTarget.LINES -> stringResource(R.string.editor_line_colour)
                PageColorTarget.MARGIN -> stringResource(R.string.editor_margin_colour)
            },
            initial = current,
            // Paper is opaque by definition; rules are frequently translucent.
            showAlpha = lines,
            presets = if (lines) ColorCodec.LINE_PRESETS else ColorCodec.PAPER_PRESETS,
            recents = state.recentColors,
            onColorChange = { picked ->
                viewModel.setPageBackground(
                    when (target) {
                        PageColorTarget.PAPER -> background.copy(color = picked)
                        PageColorTarget.LINES ->
                            background.copy(pattern = background.pattern.copy(color = picked))
                        PageColorTarget.MARGIN ->
                            background.copy(pattern = background.pattern.copy(marginColor = picked))
                    }
                )
            },
            onCommit = viewModel::rememberColor,
            onDismiss = {
                editingPageColor = null
                pageSetupOpen = true
            },
        )
    }

    reference?.let { kind ->
        val field = viewModel.textField
        ReferenceDialog(
            kind = kind,
            initialLabel = field.text.toString().substring(field.selection.min, field.selection.max),
            onDismiss = { reference = null },
            onConfirm = { label, url ->
                reference = null
                val before = field.text.toString()
                val result = when (kind) {
                    ReferenceKind.LINK ->
                        MarkdownActions.insertLink(before, field.selection.start, field.selection.end, label, url)

                    ReferenceKind.IMAGE ->
                        MarkdownActions.insertImage(before, field.selection.start, field.selection.end, label, url)
                }
                field.edit {
                    replace(0, length, result.text)
                    selection = TextRange(result.selectionStart, result.selectionEnd)
                }
            },
            onConfirmDeviceImage = if (kind == ReferenceKind.IMAGE) { label, mimeType, base64 ->
                reference = null
                val before = field.text.toString()
                val result = MarkdownActions.insertBase64Image(
                    text = before,
                    start = field.selection.start,
                    end = field.selection.end,
                    alt = label,
                    mimeType = mimeType,
                    base64Data = base64,
                )
                field.edit {
                    replace(0, length, result.text)
                    selection = TextRange(result.selectionStart, result.selectionEnd)
                }
            } else null,
        )
    }

    imageTapPosition?.let { (tapX, tapY) ->
        ReferenceDialog(
            kind = ReferenceKind.IMAGE,
            initialLabel = "",
            onDismiss = { imageTapPosition = null },
            onConfirm = { label, url ->
                imageTapPosition = null
                val markdown = MarkdownActions.insertImage("", 0, 0, label, url).text
                viewModel.insertTextBlockWithContent(tapX, tapY, markdown)
            },
            onConfirmDeviceImage = { label, mimeType, base64 ->
                imageTapPosition = null
                val markdown = MarkdownActions.insertBase64Image(
                    text = "",
                    start = 0,
                    end = 0,
                    alt = label,
                    mimeType = mimeType,
                    base64Data = base64,
                ).text
                viewModel.insertTextBlockWithContent(tapX, tapY, markdown)
            },
        )
    }

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
            initial = state.note?.meta?.tags.orEmpty(),
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
            isInk = true,
            exporting = exporting,
            onDismiss = { if (!exporting) exportOpen = false },
            onExport = { preset, format ->
                val note = state.note ?: return@ExportSheet
                exporting = true
                coroutineScope.launch(Dispatchers.Default) {
                    val result = runCatching { exportInk(context, note.sheet, preset, format) }
                    exporting = false
                    exportOpen = false
                    result.getOrNull()?.let { shareExport(context, it) }
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
        // On the whole scaffold rather than on the sheet: the formatting bar is the one control the
        // user needs *while* the keyboard is up, so the bar has to rise with it. The sheet loses the
        // height, which is what lets the transform scroll a box clear of the keyboard.
        modifier = modifier.fillMaxSize().imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.dismissToolPopupOnPress { toolPopup = null },
                title = {
                    // The name is a control rather than a caption: tapping it renames the note,
                    // which is the same thing the library's three-dot menu offers. A note this
                    // build may only read is left alone — its title lives in a manifest that must
                    // not be rewritten, so a rename here could only half happen.
                    Text(
                        text = title,
                        maxLines = 1,
                        modifier = if (state.isReadOnly) {
                            Modifier
                        } else {
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable { renaming = true }
                                .padding(vertical = 8.dp)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.editor_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(NotesIcons.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(NotesIcons.Redo, contentDescription = stringResource(R.string.editor_redo))
                    }
                    Box {
                        IconButton(onClick = { moreMenuOpen = true }) {
                            Icon(NotesIcons.More, contentDescription = stringResource(R.string.editor_more))
                        }
                        DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_page_setup)) },
                                leadingIcon = { Icon(NotesIcons.PageSetup, contentDescription = null) },
                                onClick = {
                                    moreMenuOpen = false
                                    pageSetupOpen = true
                                },
                            )
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
            if (!expanded) {
                // Two rows while the caret is in a box, and one otherwise. Stacked rather than
                // swapped: the tools are how you get *out* of the text — pick a pen and the pen is
                // what the next touch does — so taking them away while typing would make leaving a
                // box a thing you have to discover.
                Column {
                    if (state.editingTextBlock != null) {
                        MarkdownFormatBar(
                            state = viewModel.textField,
                            pending = viewModel.pendingStyles,
                            openPopup = formatPopup,
                            onPopupChange = { formatPopup = it },
                            onInsertLink = { reference = ReferenceKind.LINK },
                            onInsertImage = { reference = ReferenceKind.IMAGE },
                            // A sheet is paper, not a Markdown file: it can set its own sizes and colours.
                            attributes = true,
                            compact = true,
                        )
                    }
                    EditorToolbar(
                        state = state,
                        openPopup = toolPopup,
                        onPopupChange = { toolPopup = it },
                        onSelectTool = viewModel::selectTool,
                        onSelectTextTool = viewModel::selectTextTool,
                        onSelectImageTool = viewModel::selectImageTool,
                        onUpdateTool = viewModel::updateTool,
                        onToggleFingerDrawing = viewModel::setFingerDrawing,
                        onToggleRuler = viewModel::setRuler,
                        onOpenColorPicker = { editingPen = it },
                        compact = state.editingTextBlock != null,
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .dismissToolPopupOnPress { toolPopup = null }
        ) {
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
                    // On a tablet the tools dock beside the sheet instead of taking a bottom bar,
                    // keeping the page area as tall as possible.
                    if (expanded && state.editingTextBlock != null) {
                        MarkdownFormatRail(
                            state = viewModel.textField,
                            pending = viewModel.pendingStyles,
                            openPopup = formatPopup,
                            onPopupChange = { formatPopup = it },
                            onInsertLink = { reference = ReferenceKind.LINK },
                            onInsertImage = { reference = ReferenceKind.IMAGE },
                            attributes = true,
                        )
                    }
                    if (expanded) {
                        EditorToolRail(
                            state = state,
                            openPopup = toolPopup,
                            onPopupChange = { toolPopup = it },
                            onSelectTool = viewModel::selectTool,
                            onSelectTextTool = viewModel::selectTextTool,
                            onSelectImageTool = viewModel::selectImageTool,
                            onUpdateTool = viewModel::updateTool,
                            onToggleFingerDrawing = viewModel::setFingerDrawing,
                            onToggleRuler = viewModel::setRuler,
                            onOpenColorPicker = { editingPen = it },
                        )
                    }
                    SheetEditor(
                        state = state,
                        viewModel = viewModel,
                        darkTheme = darkTheme,
                        modifier = Modifier.fillMaxSize(),
                        onImageToolTap = { x, y -> imageTapPosition = x to y },
                    )
                }
            }
        }
    }
}
