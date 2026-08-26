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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import pl.dakil.notes.editor.InlineSelector
import pl.dakil.notes.editor.PopupPlacement
import pl.dakil.notes.editor.R
import pl.dakil.notes.ui.icons.NotesIcons

/** Which of the format bar's popups is open. */
enum class FormatPopup { BLOCK }

/**
 * The Markdown formatting controls, as a bottom bar.
 *
 * Every button drives the document through [RichTextState] rather than editing Markdown source, so
 * a press changes what is on the page immediately and the syntax is worked out only when the note
 * is written.
 *
 * ### Why there is no size, colour or highlight control
 *
 * The state carries all three and draws them correctly, and every one of them is dropped by
 * `toMarkdown()` — a size set here would be on the page until the note was reopened and then gone.
 * A control that silently loses the user's work is worse than a control that is not there, so they
 * are not offered. Restoring them means storing these documents as HTML, which the library does
 * round-trip losslessly.
 */
@Composable
fun MarkdownFormatBar(
    state: RichTextState,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier,
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
    state: RichTextState,
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
    state: RichTextState,
    placement: PopupPlacement,
    openPopup: FormatPopup?,
    onPopupChange: (FormatPopup?) -> Unit,
    onInsertLink: () -> Unit,
    onInsertImage: () -> Unit,
) {
    // Read straight off the state: what a button reports is the style the next thing typed will
    // carry, which the library already tracks for a bare caret. The old engine needed a side table
    // for that (`PendingStyles`) because it was editing source, where an armed style has nowhere
    // to live until there is a word to put markers round.
    val span = state.currentSpanStyle
    val heading = state.currentHeadingStyle

    FormatButton(
        label = heading.label(),
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
                        selected = style == heading,
                        onClick = {
                            onPopupChange(null)
                            state.setHeadingStyle(style)
                        },
                    )
                }
            }
        }
    }

    Separator(placement)

    FormatButton(
        label = stringResource(R.string.markdown_bold),
        description = stringResource(R.string.markdown_bold),
        selected = span.fontWeight == FontWeight.Bold,
        onClick = { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
        content = { Text("B", fontWeight = FontWeight.Bold) },
    )
    FormatButton(
        label = stringResource(R.string.markdown_italic),
        description = stringResource(R.string.markdown_italic),
        selected = span.fontStyle == FontStyle.Italic,
        onClick = { state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) },
        content = { Text("I", fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium) },
    )
    FormatButton(
        label = stringResource(R.string.markdown_strikethrough),
        description = stringResource(R.string.markdown_strikethrough),
        selected = span.textDecoration?.contains(TextDecoration.LineThrough) == true,
        onClick = { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) },
        content = { Text("S", textDecoration = TextDecoration.LineThrough) },
    )
    // New here. The previous engine had no underline because CommonMark has no syntax for one; this
    // one writes `<u>`, which is inline HTML that Markdown readers pass through and that survives
    // a round-trip through the library unchanged.
    FormatButton(
        label = stringResource(R.string.markdown_underline),
        description = stringResource(R.string.markdown_underline),
        selected = span.textDecoration?.contains(TextDecoration.Underline) == true,
        onClick = { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) },
        content = { Text("U", textDecoration = TextDecoration.Underline) },
    )
    IconFormatButton(
        icon = NotesIcons.InlineCode,
        description = stringResource(R.string.markdown_code),
        selected = state.isCodeSpan,
        onClick = { state.toggleCodeSpan() },
    )

    Separator(placement)

    IconFormatButton(
        icon = NotesIcons.BulletList,
        description = stringResource(R.string.markdown_bulleted_list),
        selected = state.isUnorderedList,
        onClick = { state.toggleUnorderedList() },
    )
    IconFormatButton(
        icon = NotesIcons.NumberedList,
        description = stringResource(R.string.markdown_numbered_list),
        selected = state.isOrderedList,
        onClick = { state.toggleOrderedList() },
    )
    // Greyed rather than hidden: a control that comes and goes as the caret moves is one the user
    // has to hunt for, and "you cannot nest this line" is worth saying.
    IconFormatButton(
        icon = NotesIcons.IndentDecrease,
        description = stringResource(R.string.markdown_indent_decrease),
        enabled = state.canDecreaseListLevel,
        onClick = { state.decreaseListLevel() },
    )
    IconFormatButton(
        icon = NotesIcons.IndentIncrease,
        description = stringResource(R.string.markdown_indent_increase),
        enabled = state.canIncreaseListLevel,
        onClick = { state.increaseListLevel() },
    )

    Separator(placement)

    IconFormatButton(
        icon = NotesIcons.Link,
        description = stringResource(R.string.markdown_link),
        selected = state.isLink,
        onClick = onInsertLink,
    )
    IconFormatButton(
        icon = NotesIcons.Image,
        description = stringResource(R.string.markdown_image),
        onClick = onInsertImage,
    )
}

/**
 * The paragraph styles offered in the popup, in the order a writer reaches for them.
 *
 * Quote used to live here. It has no equivalent in the rich-text model — a blockquote is not a
 * paragraph style the library carries, and its Markdown writer drops the `>` — so offering it
 * would have been offering a button that did nothing once the note was reopened.
 */
private val BLOCK_CHOICES = listOf(
    HeadingStyle.Normal,
    HeadingStyle.H1,
    HeadingStyle.H2,
    HeadingStyle.H3,
    HeadingStyle.H4,
    HeadingStyle.H5,
    HeadingStyle.H6,
)

/**
 * The name of a block style on the style button.
 *
 * `H1`..`H6` stay as they are: they are Markdown's own notation for a heading level, the same in
 * every language, and a translation would only make them harder to match to what gets typed.
 */
@Composable
private fun HeadingStyle.label(): String = when (this) {
    HeadingStyle.Normal -> stringResource(R.string.markdown_block_body)
    else -> "H$level"
}

@Composable
private fun BlockChoice(style: HeadingStyle, selected: Boolean, onClick: () -> Unit) {
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
            fontWeight = if (style == HeadingStyle.Normal) FontWeight.Normal else FontWeight.SemiBold,
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

/**
 * The height of a bar stacked above another one.
 *
 * Every control in it is a 40 dp button, so this is the buttons plus a hair — enough to sit them
 * apart from the row below without spending a second app bar's worth of screen on air.
 */
private val CompactBarHeight = 48.dp
