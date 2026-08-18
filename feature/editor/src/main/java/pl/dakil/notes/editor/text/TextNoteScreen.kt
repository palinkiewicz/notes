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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.ClipEntry
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
import pl.dakil.notes.editor.markdown.MdCodeBlock
import pl.dakil.notes.editor.markdown.MdQuote
import pl.dakil.notes.editor.markdown.MdRule
import pl.dakil.notes.editor.markdown.MdTable
import pl.dakil.notes.editor.markdown.MdTask
import pl.dakil.notes.editor.markdown.drawMarkdownDecorations
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
        // The fence guard belongs to the formatted view only: in source mode the backticks are on
        // screen, and typing in front of them is something a person can mean.
        inputTransformation = if (sourceMode) ContinueList else ContinueList.then(KeepFenceIntact),
        // Null in source mode: that *is* the source, unchanged and unhidden.
        outputTransformation = if (sourceMode) null else transformation,
        scrollState = scroll,
        onTextLayout = { result -> layout = result() },
        decorator = TextFieldDecorator { field ->
            val text = state.text.toString()
            // Source mode shows the source, decorations and all left undrawn — a box round the
            // backticks would be claiming they are not there.
            val plan = if (sourceMode) null else MarkdownRenderer.plan(text)

            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .drawBehind {
                        val result = layout ?: return@drawBehind
                        val decorations = plan?.decorations ?: return@drawBehind
                        translate(top = -scroll.value.toFloat()) {
                            drawMarkdownDecorations(decorations, result, palette)
                        }
                    },
            ) {
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
                            // The rest are shapes rather than controls, and are drawn behind the
                            // text instead of placed over it.
                            is MdTable, is MdRule, is MdQuote -> Unit
                        }
                    }
                }
            }
        },
    )

    // Only a note with nothing in it opens ready to type. Throwing the keyboard up over a note the
    // user came back to *read* costs them half the page and a tap to get it back.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state.text.isEmpty()) focusRequester.requestFocus()
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
