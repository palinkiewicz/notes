package pl.dakil.notes.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.InlineSelector
import pl.dakil.notes.editor.PopupPlacement
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownActions.BlockStyle
import pl.dakil.notes.ui.icons.NotesIcons

/** Which of the format bar's popups is open. */
enum class FormatPopup { BLOCK }

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
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomAppBar(modifier = modifier) {
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
                placement = PopupPlacement.ABOVE,
                openPopup = openPopup,
                onPopupChange = onPopupChange,
                onInsertLink = onInsertLink,
                onInsertImage = onInsertImage,
            )
        }
    }
}

/** The same controls docked beside the page, for windows wide enough to spare the width. */
@Composable
fun MarkdownFormatRail(
    state: TextFieldState,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier,
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
                placement = PopupPlacement.END,
                openPopup = openPopup,
                onPopupChange = onPopupChange,
                onInsertLink = onInsertLink,
                onInsertImage = onInsertImage,
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
    placement: PopupPlacement,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
) {
    val source = state.text.toString()
    val selection = state.selection
    val active = remember(source, selection) {
        MarkdownActions.activeInlineMarkers(source, selection.start, selection.end)
    }
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
        label = block.label,
        description = "Paragraph style",
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
                            state.applyBlockToggle(style)
                        },
                    )
                }
            }
        }
    }

    Separator(placement)

    FormatButton(
        label = "Bold",
        description = "Bold",
        selected = "**" in active,
        onClick = { state.applyWrap("**") },
        content = { Text("B", fontWeight = FontWeight.Bold) },
    )
    FormatButton(
        label = "Italic",
        description = "Italic",
        selected = "*" in active,
        onClick = { state.applyWrap("*") },
        content = { Text("I", fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium) },
    )
    FormatButton(
        label = "Strikethrough",
        description = "Strikethrough",
        selected = "~~" in active,
        onClick = { state.applyWrap("~~") },
        content = { Text("S", textDecoration = TextDecoration.LineThrough) },
    )
    IconFormatButton(
        icon = NotesIcons.InlineCode,
        description = "Code",
        selected = "`" in active,
        onClick = { state.applyWrap("`") },
    )

    Separator(placement)

    IconFormatButton(
        icon = NotesIcons.BulletList,
        description = "Bulleted list",
        selected = list == BlockStyle.BULLET,
        onClick = { state.applyBlockToggle(BlockStyle.BULLET) },
    )
    IconFormatButton(
        icon = NotesIcons.NumberedList,
        description = "Numbered list",
        selected = list == BlockStyle.ORDERED,
        onClick = { state.applyBlockToggle(BlockStyle.ORDERED) },
    )
    IconFormatButton(
        icon = NotesIcons.TaskList,
        description = "Task list",
        selected = list == BlockStyle.TASK,
        onClick = { state.applyBlockToggle(BlockStyle.TASK) },
    )
    // Greyed rather than hidden: a control that comes and goes as the caret moves is one the user
    // has to hunt for, and "you cannot nest this line" is worth saying.
    IconFormatButton(
        icon = NotesIcons.IndentDecrease,
        description = "Decrease indent",
        enabled = nested,
        onClick = {
            state.applyAction { text, start, end -> MarkdownActions.outdentList(text, start, end) }
        },
    )
    IconFormatButton(
        icon = NotesIcons.IndentIncrease,
        description = "Increase indent",
        enabled = nestable,
        onClick = {
            state.applyAction { text, start, end -> MarkdownActions.indentList(text, start, end) }
        },
    )

    Separator(placement)

    IconFormatButton(icon = NotesIcons.Link, description = "Link", onClick = onInsertLink)
    IconFormatButton(icon = NotesIcons.Image, description = "Image", onClick = onInsertImage)
    IconFormatButton(
        icon = NotesIcons.CodeBlock,
        description = "Code block",
        onClick = {
            state.applyAction { text, start, _ -> MarkdownActions.insertCodeFence(text, start) }
        },
    )
    IconFormatButton(
        icon = NotesIcons.Table,
        description = "Table",
        onClick = { state.applyAction { text, start, _ -> MarkdownActions.insertTable(text, start) } },
    )
    IconFormatButton(
        icon = NotesIcons.HorizontalRule,
        description = "Divider",
        onClick = { state.applyAction { text, start, _ -> MarkdownActions.insertRule(text, start) } },
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

private val BlockStyle.label: String
    get() = when (this) {
        BlockStyle.PARAGRAPH -> "Body"
        BlockStyle.H1 -> "H1"
        BlockStyle.H2 -> "H2"
        BlockStyle.H3 -> "H3"
        BlockStyle.H4 -> "H4"
        BlockStyle.H5 -> "H5"
        BlockStyle.H6 -> "H6"
        BlockStyle.QUOTE -> "Quote"
        BlockStyle.BULLET -> "List"
        BlockStyle.TASK -> "Tasks"
        BlockStyle.ORDERED -> "Numbers"
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
            text = style.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (style == BlockStyle.PARAGRAPH) FontWeight.Normal else FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun TextFieldState.applyWrap(marker: String) = applyAction { text, start, end ->
    MarkdownActions.toggleWrap(text, start, end, marker)
}

private fun TextFieldState.applyBlockToggle(style: BlockStyle) = applyAction { text, start, end ->
    MarkdownActions.toggleBlockStyle(text, start, end, style)
}

/**
 * Runs a [MarkdownActions] transform over the field's current text and selection.
 *
 * `edit` works in source coordinates and so does `selection`, whatever the output transformation is
 * doing on screen — so the same call is correct in both display modes, and one edit means one undo
 * step rather than a dozen.
 */
private fun TextFieldState.applyAction(
    action: (text: String, start: Int, end: Int) -> MarkdownActions.Result,
) {
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
