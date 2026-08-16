package pl.dakil.notes.editor

import androidx.activity.compose.BackHandler
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
    // Held here rather than in the toolbar: the sheet and the app bar close it too.
    var toolPopup by remember { mutableStateOf<ToolPopup?>(null) }

    val sheet = state.sheet

    // The selectors do not take focus, so back would otherwise leave the editor with one open.
    BackHandler(enabled = toolPopup != null) { toolPopup = null }

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
            PageColorTarget.PAPER -> background.color
            PageColorTarget.LINES -> background.pattern.color
            PageColorTarget.MARGIN -> background.pattern.marginColor
        }
        ColorPickerSheet(
            title = when (target) {
                PageColorTarget.PAPER -> "Paper colour"
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
                        PageColorTarget.PAPER -> background.copy(color = picked)
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
                modifier = Modifier.dismissToolPopupOnPress { toolPopup = null },
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
                    openPopup = toolPopup,
                    onPopupChange = { toolPopup = it },
                    onSelectTool = viewModel::selectTool,
                    onSelectTextTool = viewModel::selectTextTool,
                    onUpdateTool = viewModel::updateTool,
                    onToggleFingerDrawing = viewModel::setFingerDrawing,
                    onOpenColorPicker = { editingPen = it },
                )
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
                            openPopup = toolPopup,
                            onPopupChange = { toolPopup = it },
                            onSelectTool = viewModel::selectTool,
                            onSelectTextTool = viewModel::selectTextTool,
                            onUpdateTool = viewModel::updateTool,
                            onToggleFingerDrawing = viewModel::setFingerDrawing,
                            onOpenColorPicker = { editingPen = it },
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
