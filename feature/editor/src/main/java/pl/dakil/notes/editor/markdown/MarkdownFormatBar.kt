package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.InlineSelector
import pl.dakil.notes.editor.PopupPlacement
import pl.dakil.notes.editor.R
import pl.dakil.notes.editor.markdown.MarkdownActions.BlockStyle
import pl.dakil.notes.ui.icons.NotesIcons

/** Which of the format bar's popups is open. */
enum class FormatPopup { BLOCK, SIZE }

/**
 * The Markdown formatting controls, as a bottom bar.
 *
 * Every button is a pure text transformation from [MarkdownActions] applied to the field's current
 * selection — the same functions whichever way the note is being displayed, so formatting works
 * identically in the formatted view and in the raw source.
 */
@Composable
fun MarkdownFormatBar(
    state: TextFieldState,
    pending: PendingStyles,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this document may set its own font sizes. See [MdStyle.SIZE]. */
    sizes: Boolean = false,
    /**
     * Whether another bar stands below this one.
     *
     * A sheet keeps its tools out while the caret is in a box, so the two bars are stacked — and a
     * pair of full-height app bars is a third of a phone screen, most of it empty. The one that is
     * not at the bottom of the window also has no business padding itself clear of the navigation
     * bar: the bar below it is what the system insets belong to, and paying them twice is the gap
     * that appears between the two.
     */
    compact: Boolean = false,
) {
    BottomAppBar(
        modifier = if (compact) modifier.height(CompactBarHeight) else modifier,
        windowInsets = if (compact) WindowInsets(0, 0, 0, 0) else BottomAppBarDefaults.windowInsets,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatControls(
                state = state,
                pending = pending,
                placement = PopupPlacement.ABOVE,
                openPopup = openPopup,
                onPopupChange = onPopupChange,
                onInsertLink = onInsertLink,
                onInsertImage = onInsertImage,
                sizes = sizes,
            )
        }
    }
}

/** The same controls docked beside the page, for windows wide enough to spare the width. */
@Composable
fun MarkdownFormatRail(
    state: TextFieldState,
    pending: PendingStyles,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this document may set its own font sizes. See [MdStyle.SIZE]. */
    sizes: Boolean = false,
) {
    NavigationRail(modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FormatControls(
                state = state,
                pending = pending,
                placement = PopupPlacement.END,
                openPopup = openPopup,
                onPopupChange = onPopupChange,
                onInsertLink = onInsertLink,
                onInsertImage = onInsertImage,
                sizes = sizes,
            )
        }
    }
}

/**
 * One definition of the controls, laid out by whichever container holds them.
 *
 * `Row` and `Column` both accept these because nothing here declares a scope-specific modifier —
 * which is what lets the bar and the rail stay honestly identical rather than two lists that drift.
 */
@Composable
private fun FormatControls(
    state: TextFieldState,
    pending: PendingStyles,
    placement: PopupPlacement,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
    sizes: Boolean,
) {
    val source = state.text.toString()
    val selection = state.selection
    // What a button reports is the style the *next* thing typed will carry, which is not always what
    // the text around the caret says: a style armed at a bare caret is real to the user — they
    // pressed it and it lit up — while the document still knows nothing about it. See [PendingStyles].
    val armed = if (selection.collapsed) pending.stylesAt(selection.start) else emptyList()
    val marked = remember(source, selection) {
        MarkdownActions.activeInlineMarkers(source, selection.start, selection.end)
    }
    // Through [inlineMarkersOf], because an armed marker is written as the run it will be typed as:
    // a bold-italic caret carries `***`, and the buttons ask about `**` and `*`.
    val armedWraps = armed.filterIsInstance<MdPending.Wrap>()
        .flatMapTo(HashSet()) { MarkdownActions.inlineMarkersOf(it.marker) }
    // The difference between them, either way round: arming a marker that is already in force is a
    // press to turn it *off* for what comes next, and the button has to go dark to say so.
    val active = (marked - armedWraps) + (armedWraps - marked)
    val block = remember(source, selection) { MarkdownActions.blockStyleAt(source, selection.start) }
    // The two menus are read separately, because a line can be in both at once: `- # Alpha` lights
    // H1 in the popup *and* the bullet button beside it.
    val paragraph = remember(source, selection) {
        MarkdownActions.paragraphStyleAt(source, selection.start)
    }
    val list = remember(source, selection) { MarkdownActions.listStyleAt(source, selection.start) }
    val nestable = remember(source, selection) {
        MarkdownActions.canIndent(source, selection.start, selection.end)
    }
    val nested = remember(source, selection) {
        MarkdownActions.canOutdent(source, selection.start, selection.end)
    }

    FormatButton(
        label = block.label(),
        description = stringResource(R.string.markdown_paragraph_style),
        selected = openPopup == FormatPopup.BLOCK,
        onClick = { onPopupChange(if (openPopup == FormatPopup.BLOCK) null else FormatPopup.BLOCK) },
        content = { Icon(NotesIcons.Heading, contentDescription = null) },
    ) {
        if (openPopup == FormatPopup.BLOCK) {
            InlineSelector(placement = placement, onDismiss = { onPopupChange(null) }) {
                for (style in BLOCK_CHOICES) {
                    BlockChoice(
                        style = style,
                        selected = style == paragraph,
                        onClick = {
                            onPopupChange(null)
                            state.applyBlockToggle(pending, style)
                        },
                    )
                }
            }
        }
    }

    if (sizes) {
        val inText = remember(source, selection) {
            MarkdownActions.sizeIn(source, selection.start, selection.end)
        }
        val armedSize = armed.lastOrNull { it is MdPending.Size } as MdPending.Size?
        val size = if (armedSize != null) armedSize.sp else inText
        FormatButton(
            // The number rather than a glyph: the whole point of the control is which size is in
            // force, and a letter A with arrows beside it can only say "some size, possibly".
            label = size?.toString() ?: "Aa",
            description = stringResource(R.string.markdown_font_size),
            selected = openPopup == FormatPopup.SIZE,
            onClick = { onPopupChange(if (openPopup == FormatPopup.SIZE) null else FormatPopup.SIZE) },
            content = { Text(size?.toString() ?: "Aa", fontWeight = FontWeight.Medium) },
        ) {
            if (openPopup == FormatPopup.SIZE) {
                InlineSelector(placement = placement, onDismiss = { onPopupChange(null) }) {
                    SizeChoice(label = stringResource(R.string.markdown_block_body), selected = size == null) {
                        onPopupChange(null)
                        state.applySize(pending, null)
                    }
                    for (choice in SIZE_CHOICES) {
                        SizeChoice(label = choice.toString(), selected = size == choice) {
                            onPopupChange(null)
                            state.applySize(pending, choice)
                        }
                    }
                }
            }
        }
    }

    Separator(placement)

    FormatButton(
        label = stringResource(R.string.markdown_bold),
        description = stringResource(R.string.markdown_bold),
        selected = "**" in active,
        onClick = { state.applyWrap(pending, "**") },
        content = { Text("B", fontWeight = FontWeight.Bold) },
    )
    FormatButton(
        label = stringResource(R.string.markdown_italic),
        description = stringResource(R.string.markdown_italic),
        selected = "*" in active,
        onClick = { state.applyWrap(pending, "*") },
        content = { Text("I", fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium) },
    )
    FormatButton(
        label = stringResource(R.string.markdown_strikethrough),
        description = stringResource(R.string.markdown_strikethrough),
        selected = "~~" in active,
        onClick = { state.applyWrap(pending, "~~") },
        content = { Text("S", textDecoration = TextDecoration.LineThrough) },
    )
    IconFormatButton(
        icon = NotesIcons.InlineCode,
        description = stringResource(R.string.markdown_code),
        selected = "`" in active,
        onClick = { state.applyWrap(pending, "`") },
    )

    Separator(placement)

    IconFormatButton(
        icon = NotesIcons.BulletList,
        description = stringResource(R.string.markdown_bulleted_list),
        selected = list == BlockStyle.BULLET,
        onClick = { state.applyBlockToggle(pending, BlockStyle.BULLET) },
    )
    IconFormatButton(
        icon = NotesIcons.NumberedList,
        description = stringResource(R.string.markdown_numbered_list),
        selected = list == BlockStyle.ORDERED,
        onClick = { state.applyBlockToggle(pending, BlockStyle.ORDERED) },
    )
    IconFormatButton(
        icon = NotesIcons.TaskList,
        description = stringResource(R.string.markdown_task_list),
        selected = list == BlockStyle.TASK,
        onClick = { state.applyBlockToggle(pending, BlockStyle.TASK) },
    )
    // Greyed rather than hidden: a control that comes and goes as the caret moves is one the user
    // has to hunt for, and "you cannot nest this line" is worth saying.
    IconFormatButton(
        icon = NotesIcons.IndentDecrease,
        description = stringResource(R.string.markdown_indent_decrease),
        enabled = nested,
        onClick = {
            state.applyAction(pending) { text, start, end -> MarkdownActions.outdentList(text, start, end) }
        },
    )
    IconFormatButton(
        icon = NotesIcons.IndentIncrease,
        description = stringResource(R.string.markdown_indent_increase),
        enabled = nestable,
        onClick = {
            state.applyAction(pending) { text, start, end -> MarkdownActions.indentList(text, start, end) }
        },
    )

    Separator(placement)

    IconFormatButton(
        icon = NotesIcons.Link,
        description = stringResource(R.string.markdown_link),
        onClick = { pending.clear(); onInsertLink() },
    )
    IconFormatButton(
        icon = NotesIcons.Image,
        description = stringResource(R.string.markdown_image),
        onClick = { pending.clear(); onInsertImage() },
    )
    IconFormatButton(
        icon = NotesIcons.CodeBlock,
        description = stringResource(R.string.markdown_code_block),
        onClick = {
            state.applyAction(pending) { text, start, _ -> MarkdownActions.insertCodeFence(text, start) }
        },
    )
    IconFormatButton(
        icon = NotesIcons.Table,
        description = stringResource(R.string.markdown_table),
        onClick = {
            state.applyAction(pending) { text, start, _ -> MarkdownActions.insertTable(text, start) }
        },
    )
    IconFormatButton(
        icon = NotesIcons.HorizontalRule,
        description = stringResource(R.string.markdown_divider),
        onClick = {
            state.applyAction(pending) { text, start, _ -> MarkdownActions.insertRule(text, start) }
        },
    )
}

/**
 * The paragraph styles offered in the popup, in the order a writer reaches for them.
 *
 * Quote lives here rather than out on the bar with the list buttons: it is a *paragraph* style, it
 * cannot be true at the same time as a heading, and a menu of things that exclude each other is
 * exactly what this popup is. It also buys back a slot on a bar that had run out of them.
 */
private val BLOCK_CHOICES = listOf(
    BlockStyle.PARAGRAPH,
    BlockStyle.H1,
    BlockStyle.H2,
    BlockStyle.H3,
    BlockStyle.H4,
    BlockStyle.H5,
    BlockStyle.H6,
    BlockStyle.QUOTE,
)

/**
   * The name of a block style on the style button.
   *
   * `H1`..`H6` stay as they are: they are Markdown's own notation for a heading level, the same in
   * every language, and a translation would only make them harder to match to what gets typed.
   */
@Composable
private fun BlockStyle.label(): String = when (this) {
    BlockStyle.H1 -> "H1"
    BlockStyle.H2 -> "H2"
    BlockStyle.H3 -> "H3"
    BlockStyle.H4 -> "H4"
    BlockStyle.H5 -> "H5"
    BlockStyle.H6 -> "H6"
    else -> stringResource(
        when (this) {
            BlockStyle.QUOTE -> R.string.markdown_block_quote
            BlockStyle.BULLET -> R.string.markdown_block_bullet
            BlockStyle.TASK -> R.string.markdown_block_task
            BlockStyle.ORDERED -> R.string.markdown_block_ordered
            else -> R.string.markdown_block_body
        },
    )
}

@Composable
private fun BlockChoice(style: BlockStyle, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The choices are typographic, so they are shown as type rather than as icons: an "H2" set
        // in the weight it produces says more than any glyph could.
        Text(
            text = style.label(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (style == BlockStyle.PARAGRAPH) FontWeight.Normal else FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SizeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColor.current
            },
        )
    }
}

@Composable
private fun IconFormatButton(
    icon: ImageVector,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FormatButton(
        label = description,
        description = description,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        content = { Icon(icon, contentDescription = null) },
    )
}

/**
 * One formatting control.
 *
 * Carries a long-press tooltip because the bar is icon-only and a dozen glyphs wide: "what does this
 * one do" has to be answerable without pressing it and undoing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatButton(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
    enabled: Boolean = true,
    popup: @Composable () -> Unit = {},
) {
    TooltipBox(
        // Above the button: below would put the tooltip under the user's own fingertip.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    else Modifier
                )
                .clickable(enabled = enabled, onClick = onClick, onClickLabel = description),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                content = content,
            )
            popup()
        }
    }
}

/** Groups the controls. Runs across the bar, so it turns with it. */
@Composable
private fun Separator(placement: PopupPlacement) {
    val color = MaterialTheme.colorScheme.outlineVariant
    if (placement == PopupPlacement.ABOVE) {
        VerticalDivider(Modifier.padding(horizontal = 4.dp).height(28.dp), color = color)
    } else {
        HorizontalDivider(Modifier.padding(vertical = 4.dp).width(28.dp), color = color)
    }
}

// ---- Applying an action to the field ---------------------------------------------------------

/**
 * Bold, italic, strikethrough or code.
 *
 * With something selected this is an edit like any other. With nothing selected there is nothing to
 * put markers round yet, and putting them in anyway is what used to leave `****` on screen — four
 * asterisks that style nothing, so nothing hides them. The request is armed instead, and becomes
 * markers the moment there is a word between them. See [PendingStyles].
 */
private fun TextFieldState.applyWrap(pending: PendingStyles, marker: String) {
    if (selection.collapsed) {
        pending.toggleWrap(selection.start, marker)
        return
    }
    applyAction(pending) { text, start, end -> MarkdownActions.toggleWrap(text, start, end, marker) }
}

private fun TextFieldState.applyBlockToggle(pending: PendingStyles, style: BlockStyle) =
    applyAction(pending) { text, start, end -> MarkdownActions.toggleBlockStyle(text, start, end, style) }

/** A size, armed at a bare caret for the same reason [applyWrap] arms a marker. */
private fun TextFieldState.applySize(pending: PendingStyles, sp: Int?) {
    if (selection.collapsed) {
        pending.setSize(selection.start, sp)
        return
    }
    applyAction(pending) { text, start, end -> MarkdownActions.setSize(text, start, end, sp) }
}

/**
 * The ladder offered in the size menu.
 *
 * A short list of sizes people actually reach for rather than a stepper or a free field: every one
 * of them is one tap, and the sizes in a note that used this list will agree with each other, which
 * is most of what makes a page look deliberate. Anything else can still be typed by hand in source
 * mode — the tag is ordinary text.
 */
private val SIZE_CHOICES = listOf(10, 12, 14, 16, 20, 24, 32, 48)

/**
 * The height of a bar stacked above another one.
 *
 * Every control in it is a 40 dp button, so this is the buttons plus a hair — enough to sit them
 * apart from the row below without spending a second app bar's worth of screen on air.
 */
private val CompactBarHeight = 48.dp

/**
 * Runs a [MarkdownActions] transform over the field's current text and selection.
 *
 * `edit` works in source coordinates and so does `selection`, whatever the output transformation is
 * doing on screen — so the same call is correct in both display modes, and one edit means one undo
 * step rather than a dozen.
 */
private fun TextFieldState.applyAction(
    pending: PendingStyles,
    action: (text: String, start: Int, end: Int) -> MarkdownActions.Result,
) {
    // Anything that edits the text moves the caret off where a style was armed, and a style armed
    // for a caret the user has left is one they have moved on from.
    pending.clear()
    val before = text.toString()
    val result = action(before, selection.start, selection.end)
    if (result.text == before && result.selectionStart == selection.start) return
    edit {
        replace(0, length, result.text)
        selection = TextRange(
            result.selectionStart.coerceIn(0, result.text.length),
            result.selectionEnd.coerceIn(0, result.text.length),
        )
    }
}
