package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import pl.dakil.notes.editor.markdown.code.CodeColors

/** One `SpanStyle` per [MdStyle], resolved from the Material theme once and reused per keystroke. */
@Immutable
data class MarkdownStyles(private val byStyle: Map<MdStyle, SpanStyle>) {
    fun spanFor(style: MdStyle): SpanStyle = byStyle.getValue(style)
}

/**
 * Turns Markdown source into formatted output *inside a live text field*.
 *
 * `OutputTransformation` mutates a [TextFieldBuffer], and the field derives the caret mapping from
 * those mutations itself — which is the whole reason this is a word processor rather than a syntax
 * highlighter. Hiding `**` is a real deletion from what the user sees, but the document under it is
 * untouched Markdown, and the caret still lands where they tapped.
 *
 * Edits are applied back to front so each one's offsets are still valid when its turn comes; the
 * styles arrive already in post-edit coordinates from [MarkdownRenderer]. The plan's other layer —
 * the shapes a block wants drawn round it — is not applied here at all; a text field cannot draw a
 * box, so `MarkdownDecorations` draws them from the same plan.
 */
class MarkdownOutputTransformation(private val styles: MarkdownStyles) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val plan = MarkdownRenderer.plan(toString())
        for (edit in plan.edits.asReversed()) {
            replace(edit.start, edit.end, edit.replacement)
        }
        for (range in plan.styles) {
            if (range.end > range.start) addStyle(styles.spanFor(range.style), range.start, range.end)
        }
    }

    // Two transformations built from the same theme must compare equal, or the field rebuilds its
    // layout on every recomposition.
    override fun equals(other: Any?): Boolean =
        this === other || (other is MarkdownOutputTransformation && styles == other.styles)

    override fun hashCode(): Int = styles.hashCode()
}

@Composable
fun rememberMarkdownStyles(): MarkdownStyles {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return remember(colors, typography) {
        // Sizes come from the heading styles the rendered view already uses, so a note looks the
        // same whether it is being edited here or displayed on a sheet.
        fun heading(size: TextUnit) = SpanStyle(fontSize = size, fontWeight = FontWeight.SemiBold)

        // Dark or light is read off the surface the code is drawn on rather than passed in: this
        // is the only place in the app that needs to know, and the scheme already says.
        val syntax = if (colors.surface.luminance() < 0.5f) CodeColors.Dark else CodeColors.Light

        // Code spans keep the block's own family and size and change only colour, so a keyword and
        // the bracket beside it still line up in the monospace grid.
        fun code(color: Color, weight: FontWeight? = null, italic: FontStyle? = null) = SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = color,
            fontWeight = weight,
            fontStyle = italic,
        )

        MarkdownStyles(
            mapOf(
                MdStyle.H1 to heading(typography.headlineMedium.fontSize),
                MdStyle.H2 to heading(typography.headlineSmall.fontSize),
                MdStyle.H3 to heading(typography.titleLarge.fontSize),
                MdStyle.H4 to heading(typography.titleMedium.fontSize),
                MdStyle.H5 to heading(typography.titleSmall.fontSize),
                MdStyle.H6 to heading(typography.titleSmall.fontSize).copy(color = colors.onSurfaceVariant),
                MdStyle.BOLD to SpanStyle(fontWeight = FontWeight.Bold),
                MdStyle.ITALIC to SpanStyle(fontStyle = FontStyle.Italic),
                MdStyle.STRIKE to SpanStyle(textDecoration = TextDecoration.LineThrough),
                // Inline code keeps a background: it is a chip a few characters wide, and a
                // background that hugs the glyphs is exactly the right shape for one.
                MdStyle.CODE to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant,
                    background = colors.surfaceVariant,
                ),
                MdStyle.MATH to SpanStyle(fontFamily = FontFamily.Monospace, color = colors.tertiary),
                // No background, and free to be its own size: the block behind it is drawn, so
                // nothing depends any more on every line having the same character advance.
                MdStyle.FENCE_HEADER to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                ),
                // Syntax colours, from [CodeColors] rather than from the scheme: telling a string
                // from a number at a glance is what they are for, and the scheme's roles are built
                // to harmonise with each other instead.
                MdStyle.CODE_KEYWORD to code(syntax.keyword, FontWeight.SemiBold),
                MdStyle.CODE_STRING to code(syntax.string),
                MdStyle.CODE_NUMBER to code(syntax.number),
                MdStyle.CODE_COMMENT to code(syntax.comment, italic = FontStyle.Italic),
                MdStyle.CODE_FUNCTION to code(syntax.function),
                // Tables must be monospace or the padding that lines the columns up means nothing.
                MdStyle.TABLE to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                MdStyle.TABLE_HEADER to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                MdStyle.LINK to SpanStyle(
                    color = colors.primary,
                    textDecoration = TextDecoration.Underline,
                ),
                MdStyle.IMAGE to SpanStyle(color = colors.primary),
                MdStyle.QUOTE to SpanStyle(color = colors.onSurfaceVariant),
                MdStyle.FENCE to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colors.onSurface,
                ),
                MdStyle.MARKER to SpanStyle(color = colors.primary),
                // Carries no ink — it is the hole a checkbox is floated into — so all it does is
                // hold a predictable width open. See `TASK_BLANK`.
                MdStyle.TASK_BOX to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                // Leading, worn by a line's terminating newline. A line takes the height of the
                // tallest thing on it, so an oversized newline is space below the line and nothing
                // else — no glyph to draw, no width, and a taller line keeps its own height.
                MdStyle.LEADING to SpanStyle(fontSize = 21.sp),
                MdStyle.LEADING_TIGHT to SpanStyle(fontSize = 16.sp),
                // And the blank line that stands between a block and its neighbour, kept to about
                // half the height of a line of text.
                MdStyle.BLOCK_GAP to SpanStyle(fontSize = 7.sp),
            )
        )
    }
}
