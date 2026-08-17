package pl.dakil.notes.editor.markdown

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

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
 * styles arrive already in post-edit coordinates from [MarkdownRenderer].
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

        // Code spans keep the block's own family and size and change only colour, so a keyword and
        // the bracket beside it still line up in the monospace grid.
        fun code(color: Color, weight: FontWeight? = null) = SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = color,
            fontWeight = weight,
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
                MdStyle.CODE to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant,
                    background = colors.surfaceVariant,
                ),
                MdStyle.MATH to SpanStyle(fontFamily = FontFamily.Monospace, color = colors.tertiary),
                // Same family and size as the block body, deliberately. The block is squared off by
                // padding every line to the same number of characters, which only squares anything
                // if every line's characters are the same width — a smaller header would leave the
                // background ragged down the right-hand edge.
                MdStyle.FENCE_HEADER to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                    background = colors.surfaceVariant,
                ),
                // Syntax colours. Deliberately few and far apart in hue: a code block in a note is
                // read at a glance, and a dozen near-identical tints would be noise rather than
                // information.
                MdStyle.CODE_KEYWORD to code(colors.primary, FontWeight.SemiBold),
                MdStyle.CODE_STRING to code(colors.tertiary),
                MdStyle.CODE_NUMBER to code(colors.secondary),
                // Not italic, for the same reason the header is not smaller: a synthesised oblique
                // does not advance like the upright it is slanting, and a commented line would end
                // up a few pixels wider than the rest of the block.
                MdStyle.CODE_COMMENT to code(colors.onSurfaceVariant.copy(alpha = 0.7f)),
                MdStyle.CODE_FUNCTION to code(colors.onSurface),
                // Tables must be monospace or the padding that lines the columns up means nothing.
                MdStyle.TABLE to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                MdStyle.TABLE_HEADER to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                MdStyle.TABLE_RULE to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.outline,
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
                    color = colors.onSurfaceVariant,
                    background = colors.surfaceVariant,
                ),
                MdStyle.MARKER to SpanStyle(color = colors.primary),
            )
        )
    }
}
