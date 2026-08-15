package pl.dakil.notes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.ui.color.ColorPickerSheet
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pageSetupOpen by remember { mutableStateOf(false) }
    var editingPen by remember { mutableStateOf<ToolId?>(null) }
    var editingPageColor by remember { mutableStateOf<PageColorTarget?>(null) }

    val sheet = state.sheet

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
            contentPageCount = sheet.contentPageCount(),
            onAddPage = viewModel::addPage,
            onRemovePage = viewModel::removeLastPage,
            onDismiss = { pageSetupOpen = false },
        )
    }

    // Pen colour, applied live so the swatch and the next stroke follow the drag.
    editingPen?.let { tool ->
        val spec = state.toolPresets.firstOrNull { it.tool == tool } ?: ToolSpec.defaultFor(tool)
        ColorPickerSheet(
            title = "Pen colour",
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
            PageColorTarget.PAPER_LIGHT -> background.color
            PageColorTarget.PAPER_DARK -> background.darkColor
            PageColorTarget.LINES -> background.pattern.color
            PageColorTarget.MARGIN -> background.pattern.marginColor
        }
        ColorPickerSheet(
            title = when (target) {
                PageColorTarget.PAPER_LIGHT -> "Paper colour (light)"
                PageColorTarget.PAPER_DARK -> "Paper colour (dark)"
                PageColorTarget.LINES -> "Line colour"
                PageColorTarget.MARGIN -> "Margin colour"
            },
            initial = current,
            // Paper is opaque by definition; rules are frequently translucent.
            showAlpha = lines,
            presets = if (lines) ColorCodec.LINE_PRESETS else ColorCodec.PAPER_PRESETS,
            recents = state.recentColors,
            onColorChange = { picked ->
                viewModel.setPageBackground(
                    when (target) {
                        PageColorTarget.PAPER_LIGHT -> background.copy(color = picked)
                        PageColorTarget.PAPER_DARK -> background.copy(darkColor = picked)
                        PageColorTarget.LINES ->
                            background.copy(pattern = background.pattern.copy(color = picked))
                        PageColorTarget.MARGIN ->
                            background.copy(pattern = background.pattern.copy(marginColor = picked))
                    }
                )
            },
            onCommit = viewModel::rememberColor,
            onDismiss = { editingPageColor = null },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.note?.meta?.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    ViewSwitch(view = state.view, onViewChange = viewModel::setView)
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(NotesIcons.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(NotesIcons.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { pageSetupOpen = true }) {
                        Icon(NotesIcons.PageSetup, contentDescription = "Page setup")
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded) {
                EditorToolbar(
                    state = state,
                    onSelectTool = viewModel::selectTool,
                    onSelectTextTool = viewModel::selectTextTool,
                    onUpdateTool = viewModel::updateTool,
                    onToggleFingerDrawing = viewModel::setFingerDrawing,
                    onOpenColorPicker = { editingPen = it },
                    onAddPage = viewModel::addPage,
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
                    // On a tablet the tools dock beside the sheet instead of taking a bottom bar,
                    // keeping the page area as tall as possible.
                    if (expanded) {
                        EditorToolRail(
                            state = state,
                            onSelectTool = viewModel::selectTool,
                            onSelectTextTool = viewModel::selectTextTool,
                            onUpdateTool = viewModel::updateTool,
                            onToggleFingerDrawing = viewModel::setFingerDrawing,
                            onOpenColorPicker = { editingPen = it },
                            onAddPage = viewModel::addPage,
                        )
                    }
                    SheetEditor(
                        state = state,
                        viewModel = viewModel,
                        darkTheme = darkTheme,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Pages or continuous scroll.
 *
 * Presentation only — the note keeps its paper size and its page breaks either way, so switching
 * to continuous does not turn the note into something that cannot be printed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitch(view: ViewMode, onViewChange: (ViewMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(end = 8.dp)) {
        SegmentedButton(
            selected = view == ViewMode.PAGED,
            onClick = { onViewChange(ViewMode.PAGED) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {},
        ) { Text("Pages") }
        SegmentedButton(
            selected = view == ViewMode.CONTINUOUS,
            onClick = { onViewChange(ViewMode.CONTINUOUS) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
        ) { Text("Scroll") }
    }
}
