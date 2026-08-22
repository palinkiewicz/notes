package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Markdown rendered for reading rather than for editing.
 *
 * A sheet can carry any number of text boxes and only one of them is ever being typed in, so the
 * rest are drawn from the same [MarkdownRenderPlan] without a text field behind them. That is not
 * only cheaper — a `TextFieldState` per box would put every box's undo history and input connection
 * on the heap at once — it is what keeps a tap on a box a tap on a *box*, to be routed by the tool
 * in the user's hand, rather than something a field has already swallowed to place a caret.
 *
 * The output is the same by construction: the same plan, the same [MarkdownStyles], the same
 * decorations drawn through the same painter. A box that gains focus changes which component is
 * mounted, and nothing about how the text looks.
 */
@Composable
fun MarkdownStaticText(
    markdown: String,
    styles: MarkdownStyles,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onToggleTask: ((sourceMark: Int, checked: Boolean) -> Unit)? = null,
) {
    val palette = rememberMarkdownDecorationPalette()
    val colors = MaterialTheme.colorScheme
    val plan = remember(markdown) { MarkdownRenderer.plan(markdown) }
    val rendered = remember(plan, styles) { plan.toAnnotatedString(markdown, styles) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Line height unspecified for the same reason the editable field leaves it so: with it fixed, a
    // heading's larger glyphs are clipped by the body line height.
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = colors.onSurface,
        lineHeight = TextUnit.Unspecified,
    )

    Box(
        modifier.fillMaxWidth().drawBehind {
            val result = layout ?: return@drawBehind
            drawMarkdownDecorations(plan.decorations, result, palette)
        }
    ) {
        if (markdown.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Text(
            text = rendered,
            style = textStyle,
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layout = it },
        )
        val result = layout
        if (result != null && onToggleTask != null) {
            for (decoration in plan.decorations) {
                if (decoration is MdTask) StaticTaskCheckbox(decoration, result, onToggleTask)
            }
        }
    }
}

/**
 * The one control a box keeps while it is *not* being edited.
 *
 * Ticking something off a list is not editing it — it is the reason the list is on the page — and
 * making the user tap into the box, hunt for the caret and tap again would be a poor trade for the
 * consistency of having no controls out here at all.
 */
@Composable
private fun BoxScope.StaticTaskCheckbox(
    task: MdTask,
    layout: TextLayoutResult,
    onToggle: (sourceMark: Int, checked: Boolean) -> Unit,
) {
    val at = task.offset.coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(at)

    // No 48 dp minimum, for the same reason the editable one has none: it would reach a line above
    // and a line below and eat the taps meant for them.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Checkbox(
            checked = task.checked,
            onCheckedChange = { onToggle(task.sourceMark, task.checked) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val top = layout.getLineTop(line)
                    val height = layout.getLineBottom(line) - top
                    IntOffset(
                        x = (layout.getHorizontalPosition(at, true) - CheckboxPadding.toPx())
                            .roundToInt(),
                        y = (top + (height - CheckboxTarget.toPx()) / 2f).roundToInt(),
                    )
                }
                .size(CheckboxTarget),
        )
    }
}

private val CheckboxTarget = 24.dp
private val CheckboxPadding = 2.dp

/**
 * The plan, spelled out as text that can be handed to a `Text`.
 *
 * The same two passes `MarkdownOutputTransformation` makes against a text field's buffer, made here
 * against a string instead: hide the syntax back to front so each edit's offsets are still valid
 * when its turn comes, then hang the styles on what is left. The plan's style ranges are already in
 * post-edit coordinates, which is what lets the second pass be a straight loop.
 */
fun MarkdownRenderPlan.toAnnotatedString(source: String, styles: MarkdownStyles): AnnotatedString {
    val text = StringBuilder(source)
    for (edit in edits.asReversed()) {
        if (edit.start in 0..edit.end && edit.end <= text.length) {
            text.replace(edit.start, edit.end, edit.replacement)
        }
    }
    return AnnotatedString.Builder(text.toString()).apply {
        for (range in this@toAnnotatedString.styles) {
            if (range.end > range.start && range.end <= text.length) {
                addStyle(styles.spanFor(range.style, range.arg), range.start, range.end)
            }
        }
    }.toAnnotatedString()
}
