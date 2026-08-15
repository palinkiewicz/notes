package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One Markdown text block, swapping between a rendered view and an editable source view.
 *
 * This is the live-preview model: the focused block shows highlighted Markdown source in a text
 * field, every other block shows rendered output. It falls straight out of the block-based document
 * schema, and it keeps recomposition scoped to a single block while typing — the rest of the page
 * is untouched.
 */
@Composable
fun MarkdownBlock(
    markdown: String,
    focused: Boolean,
    readOnly: Boolean,
    onMarkdownChange: (String) -> Unit,
    onFocusRequested: () -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "Write something…",
) {
    if (focused && !readOnly) {
        MarkdownSourceField(
            markdown = markdown,
            onMarkdownChange = onMarkdownChange,
            onFocusLost = onFocusLost,
            modifier = modifier,
        )
    } else {
        MarkdownRendered(
            markdown = markdown,
            onToggleTask = { line -> onMarkdownChange(MarkdownParser.toggleTask(markdown, line)) },
            onClick = onFocusRequested,
            readOnly = readOnly,
            placeholderText = placeholderText,
            modifier = modifier,
        )
    }
}

@Composable
private fun MarkdownSourceField(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = rememberInlineTheme()
    val focusRequester = remember { FocusRequester() }

    // TextFieldValue rather than a bare String so the caret survives recomposition; the block is
    // only ever mounted focused, so seeding the selection at the end is the right initial state.
    var value by remember {
        mutableStateOf(TextFieldValue(markdown, androidx.compose.ui.text.TextRange(markdown.length)))
    }

    // `onFocusChanged` fires once with isFocused = false the moment the modifier attaches, which is
    // before the LaunchedEffect below has had a chance to request focus. Reporting that as a focus
    // loss would make the field close itself the instant it opened, so wait until it has genuinely
    // held focus before treating a loss as real.
    var hasBeenFocused by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            onMarkdownChange(it.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) hasBeenFocused = true
                else if (hasBeenFocused) onFocusLost()
            },
        textStyle = LocalTextStyle.current.merge(
            TextStyle(color = MaterialTheme.colorScheme.onSurface),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // Identity mapping: highlighting only restyles characters, never inserts or removes them,
        // so every caret offset stays exactly where the user put it.
        visualTransformation = { text ->
            androidx.compose.ui.text.input.TransformedText(
                InlineParser.highlight(text.text, theme),
                androidx.compose.ui.text.input.OffsetMapping.Identity,
            )
        },
    )

    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun MarkdownRendered(
    markdown: String,
    onToggleTask: (Int) -> Unit,
    onClick: () -> Unit,
    readOnly: Boolean,
    placeholderText: String,
    modifier: Modifier = Modifier,
) {
    val theme = rememberInlineTheme()
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // An empty block would otherwise lay out at zero height, leaving a brand-new note with
            // nothing to tap and no way into the text at all.
            .heightIn(min = 48.dp)
            .then(if (readOnly) Modifier else Modifier.clickable(onClick = onClick)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (markdown.isEmpty()) {
            Text(
                text = placeholderText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }
        for (block in blocks) {
            RenderBlock(block, theme, onToggleTask, readOnly)
        }
    }
}

@Composable
private fun RenderBlock(
    block: MdBlock,
    theme: InlineTheme,
    onToggleTask: (Int) -> Unit,
    readOnly: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    when (block) {
        is MdBlock.Heading -> Text(
            text = InlineParser.render(block.text, theme),
            style = when (block.level) {
                1 -> typography.headlineMedium
                2 -> typography.headlineSmall
                3 -> typography.titleLarge
                4 -> typography.titleMedium
                else -> typography.titleSmall
            },
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        is MdBlock.Paragraph -> Text(
            text = InlineParser.render(block.text, theme),
            style = typography.bodyLarge,
        )

        is MdBlock.BulletItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
            Text("• ", style = typography.bodyLarge, color = colors.primary)
            Text(InlineParser.render(block.text, theme), style = typography.bodyLarge)
        }

        is MdBlock.OrderedItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
            Text("${block.number}. ", style = typography.bodyLarge, color = colors.primary)
            Text(InlineParser.render(block.text, theme), style = typography.bodyLarge)
        }

        is MdBlock.TaskItem -> Row(
            modifier = Modifier.padding(start = (block.indent * 16).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = block.checked,
                onCheckedChange = if (readOnly) null else { _ -> onToggleTask(block.line) },
            )
            Text(InlineParser.render(block.text, theme), style = typography.bodyLarge)
        }

        is MdBlock.Quote -> Row(Modifier.padding(vertical = 4.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = InlineParser.render(block.text, theme),
                style = typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }

        is MdBlock.CodeFence -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp)
                // Long lines scroll inside the block instead of widening the page.
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = block.code,
                style = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                color = colors.onSurfaceVariant,
            )
        }

        is MdBlock.MathBlock -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            // Preserved and marked, not typeset — see InlineParser.
            Text(
                text = block.latex,
                style = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = theme.math,
            )
        }

        is MdBlock.Table -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Row {
                for (cell in block.header) {
                    Text(
                        text = cell,
                        style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(end = 16.dp, bottom = 4.dp),
                    )
                }
            }
            HorizontalDivider()
            for (row in block.rows) {
                Row {
                    for (cell in row) {
                        Text(
                            text = InlineParser.render(cell, theme),
                            style = typography.bodyMedium,
                            modifier = Modifier.padding(end = 16.dp, top = 4.dp),
                        )
                    }
                }
            }
        }

        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))

        MdBlock.Blank -> Spacer(Modifier.padding(2.dp))
    }
}

@Composable
fun rememberInlineTheme(): InlineTheme {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        InlineTheme(
            code = colors.onSurfaceVariant,
            codeBackground = colors.surfaceVariant,
            link = colors.primary,
            math = colors.tertiary,
            // Syntax markers stay visible but recede, so source mode is readable as prose.
            syntax = colors.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}
