package pl.dakil.notes.editor.export

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import pl.dakil.notes.editor.markdown.MarkdownStyles
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * A plain, non-composable [MarkdownStyles] for export.
 *
 * `rememberMarkdownStyles` needs a `MaterialTheme` to resolve colours and type scale from, and
 * export has no composition to read one from — [InkPageExporter] and [TextPageExporter] both run
 * off the UI tree. This carries only what a first cut of export needs: heading sizes, bold, italic,
 * strikethrough and monospace code. Fence backgrounds, table gridlines and quote bars are left out
 * on purpose — see the export screens' documentation for why.
 */
fun exportMarkdownStyles(): MarkdownStyles {
    fun heading(size: androidx.compose.ui.unit.TextUnit) =
        SpanStyle(fontSize = size, fontWeight = FontWeight.SemiBold)

    return MarkdownStyles(
        sizesApply = false,
        trailingSpace = SpanStyle(fontSize = 14.sp),
        byStyle = mapOf(
            MdStyle.H1 to heading(28.sp),
            MdStyle.H2 to heading(24.sp),
            MdStyle.H3 to heading(20.sp),
            MdStyle.H4 to heading(18.sp),
            MdStyle.H5 to heading(16.sp),
            MdStyle.H6 to heading(14.sp),
            MdStyle.BOLD to SpanStyle(fontWeight = FontWeight.Bold),
            MdStyle.ITALIC to SpanStyle(fontStyle = FontStyle.Italic),
            MdStyle.STRIKE to SpanStyle(textDecoration = TextDecoration.LineThrough),
            MdStyle.CODE to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            MdStyle.FENCE to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            MdStyle.FENCE_HEADER to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            MdStyle.TABLE to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            MdStyle.TABLE_HEADER to SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            MdStyle.INDENT to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            MdStyle.TASK_BOX to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            MdStyle.LEADING_TIGHT to SpanStyle(fontSize = 16.sp),
            MdStyle.LEADING_CELL to SpanStyle(fontSize = 21.sp),
            MdStyle.LIST_GAP to SpanStyle(fontSize = 3.5.sp),
            MdStyle.PARAGRAPH_GAP to SpanStyle(fontSize = 7.sp),
            MdStyle.BLOCK_GAP to SpanStyle(fontSize = 11.sp),
        ),
    )
}
