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
data class MarkdownStyles(
    private val byStyle: Map<MdStyle, SpanStyle>,
    /**
     * Whether [MdStyle.SIZE] means anything here.
     *
     * False for a `.md` note, where the tag is hidden but not obeyed. That is not an omission: the
     * file has to stay a Markdown file that other editors render sensibly, and a size is not
     * something Markdown can say. So text pasted from a sheet arrives at the note's own body size,
     * carries its tag along invisibly, and is that size again the moment it is pasted back.
     */
    private val sizesApply: Boolean = true,
    /**
     * The blank line kept past the end of the document.
     *
     * Not one of the [MdStyle]s: it is nothing to do with what the Markdown means, only with there
     * being somewhere to scroll to. It is part of the *document* rather than of the field around it
     * for exactly that reason — padding on the field would hold a strip of the viewport empty at
     * all times, so a note that fits on one screen would be shown in less room than it has.
     */
    val trailingSpace: SpanStyle,
) {
    // Unstyled rather than absent for a style nobody has given a span to: a new [MdStyle] with no
    // entry here is a paragraph that looks plain, which is a bug someone will notice and fix, and
    // not a note that cannot be opened.
    fun spanFor(style: MdStyle, arg: Int = 0): SpanStyle = when {
        style != MdStyle.SIZE -> byStyle[style] ?: SpanStyle()
        sizesApply && arg > 0 -> SpanStyle(fontSize = arg.sp)
        else -> SpanStyle()
    }
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
class MarkdownOutputTransformation(
    private val styles: MarkdownStyles,
    private val formatted: Boolean,
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        // Source mode renders nothing: that *is* the source, unchanged and unhidden. It still gets
        // the trailing line, so the bottom of the page is in the same place in both views.
        if (formatted) {
            val plan = MarkdownRenderer.plan(toString())
            for (edit in plan.edits.asReversed()) {
                replace(edit.start, edit.end, edit.replacement)
            }
            for (range in plan.styles) {
                if (range.end > range.start) {
                    addStyle(styles.spanFor(range.style, range.arg), range.start, range.end)
                }
            }
        }

        // And a short blank line past the end, so the last line of a note can be scrolled clear of
        // the bar below it. See [MarkdownStyles.trailingSpace].
        val end = length
        replace(end, end, "\n")
        addStyle(styles.trailingSpace, end, end + 1)
    }

    // Two transformations built from the same theme must compare equal, or the field rebuilds its
    // layout on every recomposition.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is MarkdownOutputTransformation && styles == other.styles && formatted == other.formatted)

    override fun hashCode(): Int = 31 * styles.hashCode() + formatted.hashCode()
}

/**
 * The Material theme, resolved into one `SpanStyle` per [MdStyle].
 *
 * [sizes] says whether this document may set its own font sizes — true on a sheet, false in a `.md`
 * note. See [MarkdownStyles.sizesApply].
 */
@Composable
fun rememberMarkdownStyles(sizes: Boolean = true): MarkdownStyles {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return remember(colors, typography, sizes) {
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
            sizesApply = sizes,
            // A blank line of a 14 sp face comes out at about 16 dp, which is what holds the last
            // line of a note off the formatting bar once it has been scrolled to.
            trailingSpace = SpanStyle(fontSize = 14.sp),
            byStyle = mapOf(
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
                // Monospace for the same reason the checkbox blank is: a nesting level has to be
                // the same step every time, whatever the item's first character happens to be.
                MdStyle.INDENT to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                // Carries no ink — it is the hole a checkbox is floated into — so all it does is
                // hold a predictable width open. See `TASK_BLANK`.
                MdStyle.TASK_BOX to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                // Leading, worn by a line's terminating newline. A line takes the height of the
                // tallest thing on it, so an oversized newline is room on the line and nothing
                // else — no glyph to draw and no width. Blocks only: see [MdStyle.LEADING_TIGHT].
                MdStyle.LEADING_TIGHT to SpanStyle(fontSize = 16.sp),
                // A table row is as tall as this rather than as tall as its own monospace text, so
                // that a cell has four dp of air above and below what is written in it. Which side
                // that air falls on is not up to us — see `CellLift`.
                MdStyle.LEADING_CELL to SpanStyle(fontSize = 21.sp),
                // The blank lines standing between one thing and the next, in the three sizes the
                // document is spaced with: items of one list, two paragraphs, and a drawn block
                // against whatever it stands beside. A blank line comes out a shade taller than
                // the face it is set in — 4 sp reads as about 4 dp, 7 sp as about 8.
                MdStyle.LIST_GAP to SpanStyle(fontSize = 3.5.sp),
                MdStyle.PARAGRAPH_GAP to SpanStyle(fontSize = 7.sp),
                MdStyle.BLOCK_GAP to SpanStyle(fontSize = 11.sp),
            )
        )
    }
}
