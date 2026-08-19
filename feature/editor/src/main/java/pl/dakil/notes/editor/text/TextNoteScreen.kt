package pl.dakil.notes.editor.text

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.dakil.notes.editor.markdown.BlockPadding
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownOutputTransformation
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdBorder
import pl.dakil.notes.editor.markdown.MdCodeBlock
import pl.dakil.notes.editor.markdown.MdQuote
import pl.dakil.notes.editor.markdown.MdRule
import pl.dakil.notes.editor.markdown.MdTable
import pl.dakil.notes.editor.markdown.MdTask
import pl.dakil.notes.editor.markdown.CodeInset
import pl.dakil.notes.editor.markdown.borderAt
import pl.dakil.notes.editor.markdown.buttonCentre
import pl.dakil.notes.editor.markdown.centreOf
import pl.dakil.notes.editor.markdown.drawMarkdownDecorations
import pl.dakil.notes.editor.markdown.gridIn
import pl.dakil.notes.editor.markdown.rememberMarkdownDecorationPalette
import pl.dakil.notes.editor.markdown.rememberMarkdownStyles
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.MonospaceStyle
import kotlin.math.roundToInt

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
    val palette = rememberMarkdownDecorationPalette()
    val transformation = remember(styles) { MarkdownOutputTransformation(styles) }
    val focusRequester = remember { FocusRequester() }

    // The field's own scroller, held here rather than left internal: the decorator wraps the
    // viewport and not the text, so anything drawn in it has to be moved by however far the text
    // has slid. Without this the borders stay put while their blocks scroll away from under them.
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Which table border the user has picked out, named by where its table starts in the source.
    // Nothing clears it when the document moves under it: the border is only ever drawn against a
    // table whose offset still matches, so a stale one is inert rather than wrong.
    var border by remember { mutableStateOf<MdBorder?>(null) }

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
            // Copy and cut have to carry the Markdown away rather than the rendering of it.
            .markdownClipboard(state)
            // Sides only. There is no top padding because there is nothing to pad against: every
            // line already carries [MdStyle.LEADING] on its terminator, and a line takes the height
            // of the tallest thing on it — so the first line arrives with air above it whether or
            // not this asks for any, and asking anyway put a visible gap under the app bar. A
            // bottom margin would not be a margin either, but a strip of page the text can never
            // reach; the bar below already separates the two.
            .padding(horizontal = 20.dp)
            // Inside the padding, so a position here is already in the text's own coordinates.
            // The tap is watched rather than taken: it goes on to place the caret as any other
            // tap would, and all this adds is which border — if any — it was nearest.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val result = layout ?: return@awaitEachGesture
                    val decorations = MarkdownRenderer.plan(state.text.toString()).decorations
                    val at = down.position + Offset(0f, scroll.value.toFloat())
                    val padding = BlockPadding.toPx()

                    // The `+` stands on the middle of the border it belongs to, so every tap on it
                    // is also a tap on that border. The button gets it.
                    val open = border
                    val centre = open?.let { decorations.buttonCentre(it, result, padding) }
                    if (centre != null && (at - centre).getDistance() <= InsertButton.toPx() / 2f) {
                        return@awaitEachGesture
                    }

                    // Taking the tap rather than watching it. Letting it through as well would drop
                    // the caret into the nearest cell, and the handle Android draws under a caret
                    // is wide enough to cover the button that is about to appear.
                    val hit = decorations.borderAt(result, padding, BorderTouch.toPx(), at)
                    if (hit == null) {
                        // Anywhere else puts the last border back the way it was, which is the only
                        // way out of the focused state that does not need a control of its own. The
                        // touch itself is left alone from here: the field consumes it during the
                        // main pass, so there is nothing left to wait for.
                        border = null
                        return@awaitEachGesture
                    }

                    // A border tap is taken outright, so the caret stays where it was: letting it
                    // through would drop the caret into the nearest cell, and the handle Android
                    // draws under a caret is wide enough to cover the button about to appear.
                    down.consume()
                    val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                    if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        return@awaitEachGesture
                    }
                    up.consume()
                    border = if (hit == open) null else hit
                }
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        lineLimits = TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        // The block guards belong to the formatted view only: in source mode the backticks and the
        // pipes are on screen, and editing them is something a person can mean. They run *before*
        // `ContinueList`, so each one judges the user's own keystroke rather than another
        // transformation's rewrite of it.
        inputTransformation = if (sourceMode) {
            ListIndent.then(ContinueList)
        } else {
            KeepBlocksIntact.then(ListIndent).then(InsertTableRow).then(ContinueList)
                .then(KeepFenceIntact)
        },
        // Null in source mode: that *is* the source, unchanged and unhidden.
        outputTransformation = if (sourceMode) null else transformation,
        scrollState = scroll,
        onTextLayout = { result -> layout = result() },
        decorator = TextFieldDecorator { field ->
            val text = state.text.toString()
            // Read here rather than in the field's body: the decoration is recomposed as the
            // document changes anyway, and the table menu has to know which cell it would act on.
            val caret = state.selection.start
            // Source mode shows the source, decorations and all left undrawn — a box round the
            // backticks would be claiming they are not there.
            val plan = if (sourceMode) null else MarkdownRenderer.plan(text)

            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val result = layout ?: return@drawBehind
                        val decorations = plan?.decorations ?: return@drawBehind
                        // Clipped by hand rather than with `clipToBounds`, and a little wider than
                        // the text: a code block's box has to sit outside the column its code is
                        // set in, because the code cannot be moved inwards. An indent made of
                        // spaces is lost the moment a line wraps, and a wrapped line that starts
                        // under the border instead of under its own first character is worse than
                        // no padding at all. Vertically it clips exactly, so nothing a scroll has
                        // taken off the top is painted over the bar above.
                        clipRect(
                            left = -CodeInset.toPx(),
                            top = 0f,
                            right = size.width + CodeInset.toPx(),
                            bottom = size.height,
                        ) {
                            translate(top = -scroll.value.toFloat()) {
                                drawMarkdownDecorations(decorations, result, palette, border)
                            }
                        }
                    },
            ) {
              Box(Modifier.fillMaxWidth().clipToBounds()) {
                if (text.isEmpty()) {
                    Text(
                        text = "Write something…",
                        style = textStyle,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                field()
                layout?.let { result ->
                    plan?.decorations?.forEach { decoration ->
                        when (decoration) {
                            is MdCodeBlock -> CopyCodeButton(decoration, result, text, scroll.value)
                            is MdTask -> TaskCheckbox(decoration, result, scroll.value) {
                                toggleTask(state, decoration)
                            }
                            is MdTable -> {
                                TableInsertButton(
                                    table = decoration,
                                    layout = result,
                                    scroll = scroll.value,
                                    focused = border,
                                    onInsert = {
                                        insertIntoTable(state, text, it)
                                        border = null
                                    },
                                )
                                if (caret in decoration.sourceStart..decoration.sourceEnd) {
                                    TableMenuButton(
                                        table = decoration,
                                        layout = result,
                                        scroll = scroll.value,
                                        onAction = { applyToTable(state, text, caret, it) },
                                    )
                                }
                            }
                            // The rest are shapes rather than controls, and are drawn behind the
                            // text instead of placed over it.
                            is MdRule, is MdQuote -> Unit
                        }
                    }
                }
              }
            }
        },
    )

    // Only a note with nothing in it opens ready to type. Throwing the keyboard up over a note the
    // user came back to *read* costs them half the page and a tap to get it back.
    LaunchedEffect(Unit) {
        if (state.text.isEmpty()) focusRequester.requestFocus()
    }

    // Watching the caret rather than the tap that moved it. A tap is consumed by the field on its
    // way past, so there is no moment afterwards to correct — and this way arrow keys and a dragged
    // handle land in a cell's text as surely as a tap does.
    if (!sourceMode) {
        LaunchedEffect(state) {
            snapshotFlow { state.selection }.collect { selection ->
                if (!selection.collapsed) return@collect
                val target = MarkdownStructure.cellCaret(state.text.toString(), selection.start)
                    ?: return@collect
                state.edit { this.selection = TextRange(target.coerceIn(0, length)) }
            }
        }
    }
}

/**
 * The button in a code block's header strip.
 *
 * Floated over the field rather than drawn into it, because it has to be pressable. The cost is
 * that the top-right corner of every code block stops placing a caret when tapped; the header is
 * mostly empty space, so that corner was not worth much.
 */
@Composable
private fun BoxScope.CopyCodeButton(
    block: MdCodeBlock,
    layout: TextLayoutResult,
    source: String,
    scroll: Int,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val line = layout.getLineForOffset(block.start.coerceIn(0, layout.layoutInput.text.length))

    IconButton(
        onClick = {
            val from = block.sourceStart.coerceIn(0, source.length)
            val to = block.sourceEnd.coerceIn(from, source.length)
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", source.substring(from, to))))
            }
        },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset {
                // Centred on the header strip, which reaches one BlockPadding above its line — the
                // same measurement the strip itself is drawn from.
                val top = layout.getLineTop(line) - BlockPadding.toPx()
                val height = layout.getLineBottom(line) - top
                IntOffset(
                    x = -4.dp.roundToPx(),
                    y = (top - scroll + (height - 22.dp.toPx()) / 2f).roundToInt(),
                )
            }
            .size(22.dp),
    ) {
        Icon(
            imageVector = NotesIcons.Copy,
            contentDescription = "Copy code",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A task item's checkbox, floated over the blank the plan left for it.
 *
 * The stock Material control rather than a glyph or something drawn: it is a checkbox, and it has
 * to tick, animate and answer TalkBack like every other checkbox in the app.
 */
@Composable
private fun BoxScope.TaskCheckbox(
    task: MdTask,
    layout: TextLayoutResult,
    scroll: Int,
    onToggle: () -> Unit,
) {
    val at = task.offset.coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(at)

    // The 48 dp minimum touch target is right for a button standing on its own and wrong for a
    // control sitting inside a line of text: it would reach a line above and a line below and eat
    // the taps meant to put a caret there. What is left is the checkbox's own 24 dp — tapped
    // exactly where it is seen.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Checkbox(
            checked = task.checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val top = layout.getLineTop(line)
                    val height = layout.getLineBottom(line) - top
                    IntOffset(
                        // Backing out the control's own padding puts the box itself, rather than
                        // its bounds, against the left edge of the blank.
                        x = (layout.getHorizontalPosition(at, true) - CheckboxPadding.toPx())
                            .roundToInt(),
                        y = (top - scroll + (height - CheckboxTarget.toPx()) / 2f).roundToInt(),
                    )
                }
                .size(CheckboxTarget),
        )
    }
}

/** What a Material checkbox measures with its own padding, and how much of that padding is its. */
private val CheckboxTarget = 24.dp
private val CheckboxPadding = 2.dp

/**
 * Flips the one character between a task's brackets.
 *
 * A single-character replacement rather than a rewritten line, so the caret, the undo history and
 * every other character the user wrote are left alone. What is there is checked before it is
 * replaced: the layout the tap was aimed with can be a frame behind the text, and a stale offset
 * has to miss rather than overwrite whatever moved into its place.
 */
private fun toggleTask(state: TextFieldState, task: MdTask) {
    state.edit {
        val at = task.sourceMark
        if (at !in 0 until length) return@edit
        if (asCharSequence()[at] !in " xX") return@edit
        replace(at, at + 1, if (task.checked) " " else "x")
    }
}

/**
 * The `+` that appears on a focused table border.
 *
 * Drawn geometry cannot be pressed — that is the trade for having real borders rather than typed
 * ones — so the control that acts on a border is a real button parked exactly on top of it. It
 * exists only while a border is focused, which is why one tap to pick the border and one to press
 * the button is two taps rather than one: nothing is on screen until the user has aimed.
 */
/** What the table menu can do, all of it relative to the cell the caret is in. */
private enum class TableAction(val label: String) {
    RowAbove("Insert row above"),
    RowBelow("Insert row below"),
    ColumnLeft("Insert column left"),
    ColumnRight("Insert column right"),
    DeleteRow("Delete row"),
    DeleteColumn("Delete column"),
    DeleteTable("Delete table"),
}

/**
 * The table's own menu, standing beside it while the caret is inside it.
 *
 * Every item names the thing it does to the cell the caret is in, which is the one framing with no
 * ambiguity in it: a `+` on a border says where a row goes but not which row to take away, and a
 * gesture nobody can see is not a way to delete anything. Words in a list are.
 */
@Composable
private fun BoxScope.TableMenuButton(
    table: MdTable,
    layout: TextLayoutResult,
    scroll: Int,
    onAction: (TableAction) -> Unit,
) {
    val density = LocalDensity.current
    val grid = table.gridIn(layout, with(density) { BlockPadding.toPx() }) ?: return
    val header = grid.rows.getOrNull(1) ?: grid.bottom
    var expanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset {
                // Just clear of the table where there is room for it, and tucked back inside the
                // right margin where there is not.
                val limit = layout.layoutInput.constraints.maxWidth - MenuButton.toPx()
                IntOffset(
                    x = (grid.right + 4.dp.toPx()).coerceAtMost(limit).roundToInt(),
                    y = ((grid.top + header) / 2f - scroll - MenuButton.toPx() / 2f).roundToInt(),
                )
            },
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            FilledTonalIconButton(onClick = { expanded = true }, modifier = Modifier.size(MenuButton)) {
                Icon(
                    imageVector = NotesIcons.Table,
                    contentDescription = "Table options",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (action in TableAction.entries) {
                if (action == TableAction.DeleteRow) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        expanded = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TableInsertButton(
    table: MdTable,
    layout: TextLayoutResult,
    scroll: Int,
    focused: MdBorder?,
    onInsert: (MdBorder) -> Unit,
) {
    val here = focused?.takeIf { it.table == table.sourceStart } ?: return
    val density = LocalDensity.current
    val grid = table.gridIn(layout, with(density) { BlockPadding.toPx() }) ?: return
    val at = grid.centreOf(here) ?: return

    // No minimum touch target: 48 dp of it would cover most of a small table, and the button is
    // only ever on screen because the user has already aimed at the border under it.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        FilledIconButton(
            onClick = { onInsert(here) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        x = (at.x - InsertButton.toPx() / 2f).roundToInt(),
                        y = (at.y - scroll - InsertButton.toPx() / 2f).roundToInt(),
                    )
                }
                .size(InsertButton),
        ) {
            Icon(
                imageVector = NotesIcons.Add,
                contentDescription = if (here.vertical) "Insert column" else "Insert row",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** How close to a border a tap has to land, and how big the button that appears on it is. */
private val BorderTouch = 8.dp
private val InsertButton = 28.dp
private val MenuButton = 28.dp

/**
 * Adds the row or column a `+` button stands for.
 *
 * The table is looked up in the source again rather than carried along, because between the tap
 * that focused the border and the tap that pressed the button the document may have moved.
 */
/** Carries out a menu item against the cell the caret is in. */
private fun applyToTable(state: TextFieldState, source: String, caret: Int, action: TableAction) {
    val cell = MarkdownStructure.cellAt(source, caret) ?: return
    val table = cell.table
    val edit = when (action) {
        TableAction.RowAbove -> MarkdownStructure.addRow(source, table, cell.row)
        TableAction.RowBelow -> MarkdownStructure.addRow(source, table, cell.row + 1)
        TableAction.ColumnLeft -> MarkdownStructure.addColumn(source, table, cell.column)
        TableAction.ColumnRight -> MarkdownStructure.addColumn(source, table, cell.column + 1)
        TableAction.DeleteRow -> MarkdownStructure.removeRow(source, table, cell.row)
        TableAction.DeleteColumn -> MarkdownStructure.removeColumn(source, table, cell.column)
        TableAction.DeleteTable -> MarkdownStructure.removeTable(source, table)
    }
    state.edit {
        replace(edit.start, edit.end, edit.text)
        selection = TextRange(edit.caret.coerceIn(0, length))
    }
}

private fun insertIntoTable(state: TextFieldState, source: String, border: MdBorder) {
    val table = MarkdownStructure.tableAt(source, border.table) ?: return
    val edit = if (border.vertical) {
        MarkdownStructure.addColumn(source, table, border.index)
    } else {
        MarkdownStructure.addRow(source, table, border.index)
    }
    state.edit {
        replace(edit.start, edit.end, edit.text)
        selection = TextRange(edit.caret.coerceIn(0, length))
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
