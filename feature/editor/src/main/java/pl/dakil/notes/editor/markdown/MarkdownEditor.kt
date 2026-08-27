package pl.dakil.notes.editor.markdown

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import pl.dakil.notes.editor.R
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.MonospaceStyle

/**
 * A layout, and the document it was measured from.
 *
 * Kept as one value because using either without the other is a bug: every decoration is placed by
 * asking the layout where an offset of the *plan* landed, and the two are only ever in step when
 * they came from the same string. The plan is worked out here rather than in the decorator so that
 * the renderer's one-slot cache is still warm from the transformation that laid this text out.
 */
private class MeasuredMarkdown(
    val layout: TextLayoutResult,
    val source: String,
    sourceMode: Boolean,
) {
    /**
     * The parse of [source], shared by everything that needs it for this layout.
     *
     * Lazy because source mode draws no decorations and would otherwise parse for nothing, and
     * held because the caret reporter below needs the same plan's edits on every caret move — it
     * used to re-parse the whole document to map one offset, so walking a long note with the arrow
     * keys re-parsed it once per keypress.
     */
    val plan: MarkdownRenderPlan by lazy(LazyThreadSafetyMode.NONE) { MarkdownRenderer.plan(source) }

    // Source mode shows the source, decorations and all left undrawn — a box round the backticks
    // would be claiming they are not there.
    val decorations: List<MdDecoration> = if (sourceMode) emptyList() else plan.decorations
}

/**
 * A Markdown document in a single live-preview text field.
 *
 * One field, two presentations. By default the syntax is gone — headings are big, bold is bold, a
 * bullet is a bullet — and [MarkdownFormatBar] is how the user creates any of it; [sourceMode] swaps
 * in the raw source in a monospace font instead. Both are the same field over the same string, so
 * switching never costs an edit.
 *
 * Every caller in the app shares this one implementation: the `.md` note screen wraps it in a page
 * of its own, and each text box on a sheet mounts one when the user taps into it. The parts that
 * differ between them are all here as parameters, because the parts that do not differ — what
 * Markdown looks like, what Enter does inside a list, what Copy puts on the clipboard — are exactly
 * the parts that must never disagree between one kind of note and the other.
 *
 * [scroll] is the field's own scroller, taken as a parameter rather than left internal because the
 * decorator wraps the *viewport* and not the text: anything drawn in it has to be moved by however
 * far the text has slid, or the borders stay put while their blocks scroll away from under them. A
 * caller that lays this out at its full height — a text box does — passes one that never moves.
 */
@Composable
fun MarkdownEditor(
    state: TextFieldState,
    sourceMode: Boolean,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    placeholder: String = stringResource(R.string.markdown_placeholder),
    /**
     * Whether to take focus as soon as this is mounted.
     *
     * A `.md` note passes true only for an empty one: throwing the keyboard up over a note somebody
     * came back to *read* costs them half the page and a tap to get it back. A text box on a sheet
     * always passes true — it is only ever mounted as an editor because the user just tapped into
     * it, and a box with no caret in it would be a keyboard that never arrives.
     */
    autoFocus: Boolean = true,
    /** Whether `[text]{size=18}` sets a size here, or is merely hidden. See [MarkdownStyles]. */
    sizes: Boolean = false,
    /**
     * What the formatting bar has been asked for but not yet typed into. See [PendingStyles].
     *
     * A caller that shows a formatting bar beside this field has to hand both of them the *same*
     * one — arming and spending are the two halves of one gesture. The default is a private one so
     * that a field with no bar still behaves, rather than silently holding a style forever.
     */
    pending: PendingStyles = remember { PendingStyles() },
    /**
     * Where the caret is, in this field's own pixels, whenever it moves.
     *
     * For the caller that cannot scroll to it by itself. A `.md` note has [scroll] and the field
     * keeps the caret in view through it; a text box on a sheet is laid out at its full height
     * inside a surface that pans as a whole, and only the surface can bring the caret out from
     * under the keyboard.
     */
    onCaretBounds: ((top: Float, bottom: Float) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val styles = rememberMarkdownStyles(sizes)
    val palette = rememberMarkdownDecorationPalette()
    val transformation = remember(styles, sourceMode) { MarkdownOutputTransformation(styles, !sourceMode) }
    val applyPending = remember(pending) { ApplyPendingStyles(pending) }
    val keepInline = remember(pending) { KeepInlineIntact(pending) }
    val keepSpace = remember(pending) { KeepSpaceOutside(pending) }
    val focusRequester = remember { FocusRequester() }

    var measured by remember { mutableStateOf<MeasuredMarkdown?>(null) }

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
            // Sides only. There is nothing to pad against at the top: the document's first line
            // is meant to sit against the app bar, and the blank lines that space paragraphs out
            // are only ever *between* two of them. Nor at the bottom, where padding would hold a
            // strip of the viewport permanently empty and show a note in less room than it has —
            // the room to scroll the last line clear of the bar is a blank line at the foot of the
            // document instead. See [MarkdownStyles.trailingSpace].
            .padding(horizontal = 20.dp)
            // Inside the padding, so a position here is already in the text's own coordinates.
            // The tap is watched rather than taken: it goes on to place the caret as any other
            // tap would, and all this adds is which border — if any — it was nearest.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val shown = measured ?: return@awaitEachGesture
                    val result = shown.layout
                    val decorations = shown.decorations
                    val at = down.position + Offset(0f, scroll.value.toFloat())
                    val drop = CellDrop.toPx()

                    // The `+` stands on the middle of the border it belongs to, so every tap on it
                    // is also a tap on that border. The button gets it.
                    val open = border
                    val centre = open?.let { decorations.buttonCentre(it, result, drop) }
                    if (centre != null && (at - centre).getDistance() <= InsertButton.toPx() / 2f) {
                        return@awaitEachGesture
                    }

                    // Taking the tap rather than watching it. Letting it through as well would drop
                    // the caret into the nearest cell, and the handle Android draws under a caret
                    // is wide enough to cover the button that is about to appear.
                    val hit = decorations.borderAt(result, drop, BorderTouch.toPx(), at)
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
        // [KeepMarkersIntact] stands beside [KeepBlocksIntact] because both repair a range the
        // *field* invented rather than one the user aimed; the two can never both fire, so their
        // order relative to each other is not load-bearing, but they must precede everything that
        // judges the edit afterwards.
        // [ApplyPendingStyles] runs last in both chains: what it wraps has to be the keystroke as
        // the rest of them left it, not as the keyboard sent it.
        inputTransformation = if (sourceMode) {
            ListIndent.then(ContinueList).then(applyPending)
        } else {
            KeepBlocksIntact.then(KeepMarkersIntact).then(ListIndent).then(InsertTableRow)
                .then(ContinueList).then(KeepFenceIntact).then(keepInline).then(DropStrandedMarkers)
                .then(applyPending).then(keepSpace)
        },
        // Present in source mode too, though it renders nothing there: it is also what keeps the
        // blank line at the foot of the document, and the page has the same bottom in both views.
        outputTransformation = transformation,
        scrollState = scroll,
        // The plan is captured here rather than read from the document, so that whatever is drawn
        // over the text is drawn from the offsets this very layout was built out of. A layout
        // arrives a frame after the edit that caused it — `onTextLayout` runs in the layout phase,
        // by which time the composition that changed the document has been and gone — so a
        // decoration placed by the new plan against the old layout is placed wrong for exactly one
        // frame. That frame is what made a checkbox jump to the line above on a backspace.
        onTextLayout = { result ->
            val source = state.text.toString()
            measured = result()?.let { MeasuredMarkdown(it, source, sourceMode) }
        },
        decorator = TextFieldDecorator { field ->
            val text = state.text.toString()
            // Read here rather than in the field's body: the decoration is recomposed as the
            // document changes anyway, and the table menu has to know which cell it would act on.
            val caret = state.selection.start
            val shown = measured

            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val result = shown?.layout ?: return@drawBehind
                        val decorations = shown.decorations
                        // Clipped by hand rather than with `clipToBounds`, and by a hair's breadth
                        // wider than the text: a block's box is drawn flush with the text column,
                        // so half of its one-dp outline falls outside it and would be shaved off.
                        // Vertically it clips exactly, so nothing a scroll has taken off the top is
                        // painted over the bar above.
                        clipRect(
                            left = -BlockBleed.toPx(),
                            top = 0f,
                            right = size.width + BlockBleed.toPx(),
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
                        text = placeholder,
                        style = textStyle,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                field()
                if (shown != null) {
                    val result = shown.layout
                    shown.decorations.forEach { decoration ->
                        when (decoration) {
                            is MdCodeBlock -> CopyCodeButton(decoration, result, shown.source, scroll.value)
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

    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    // A style armed for the next thing typed lasts exactly as long as the caret it was armed at.
    // Tapping somewhere else is how a user says they have changed their mind, and there is nothing
    // on screen for them to press a second time to take it back — nothing was inserted.
    LaunchedEffect(state, pending) {
        // A different document in the same field is a different caret, whatever its offset.
        pending.clear()
        snapshotFlow { state.selection }.collect { pending.keepAt(it) }
    }
    // Leaving the field is a caret move that produces no selection change of its own: a text box on
    // a sheet takes the whole editor away with it.
    DisposableEffect(pending) {
        onDispose { pending.clear() }
    }

    // The caret's own position, for a caller that has to scroll something else to reveal it. The
    // layout is over the *rendered* text, so the source offset has to be mapped through the plan's
    // edits first — the difference is every hidden `#` and `**` above the caret, which on a page of
    // formatted text is a great many lines' worth.
    if (onCaretBounds != null) {
        val shown = measured
        LaunchedEffect(shown, state.selection) {
            val laid = shown ?: return@LaunchedEffect
            val at = MarkdownRenderer.transformedOffset(laid.plan.edits, state.selection.start)
                .coerceIn(0, laid.layout.layoutInput.text.length)
            val cursor = laid.layout.getCursorRect(at)
            onCaretBounds(cursor.top, cursor.bottom)
        }
    }

    // Watching the caret rather than the tap that moved it. A tap is consumed by the field on its
    // way past, so there is no moment afterwards to correct — and this way arrow keys and a dragged
    // handle land in a cell's text as surely as a tap does.
    if (!sourceMode) {
        LaunchedEffect(state) {
            snapshotFlow { state.selection }.collect { selection ->
                val source = state.text.toString()
                // A caret that has come to rest inside markup nobody can see is not one the user
                // placed, however they came to it — a tap in the few pixels above a heading, or an
                // arrow key stepping into the hashes, which the field reports back as a range
                // covering the whole run. Neither is a selection: both cover nothing on screen.
                if (!MarkdownRenderer.coversVisibleText(source, selection.start, selection.end)) {
                    val past = MarkdownRenderer.visibleCaret(source, selection.min)
                    if (past != null) {
                        state.edit { this.selection = TextRange(past.coerceIn(0, length)) }
                        return@collect
                    }
                }
                if (!selection.collapsed) return@collect
                val target = MarkdownStructure.cellCaret(source, selection.start) ?: return@collect
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
            contentDescription = stringResource(R.string.markdown_copy_code),
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
private enum class TableAction(@param:StringRes val label: Int) {
    RowAbove(R.string.markdown_row_above),
    RowBelow(R.string.markdown_row_below),
    ColumnLeft(R.string.markdown_column_left),
    ColumnRight(R.string.markdown_column_right),
    DeleteRow(R.string.markdown_delete_row),
    DeleteColumn(R.string.markdown_delete_column),
    DeleteTable(R.string.markdown_delete_table),
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
    val grid = table.gridIn(layout, with(density) { CellDrop.toPx() }) ?: return
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
                    contentDescription = stringResource(R.string.markdown_table_options),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (action in TableAction.entries) {
                if (action == TableAction.DeleteRow) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
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
    val grid = table.gridIn(layout, with(density) { CellDrop.toPx() }) ?: return
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
                contentDescription = stringResource(
                    if (here.vertical) R.string.markdown_insert_column
                    else R.string.markdown_insert_row,
                ),
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

internal enum class ReferenceKind { LINK, IMAGE }

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
            TextButton(
                onClick = { onConfirm(label, url) },
                // An address is the one part that cannot be filled in later from the formatted
                // view, because the formatted view hides it.
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.markdown_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}
