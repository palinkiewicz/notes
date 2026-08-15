package pl.dakil.notes.editor.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Colours the inline renderer needs, supplied from the M3 theme by the caller. */
data class InlineTheme(
    val code: Color,
    val codeBackground: Color,
    val link: Color,
    val math: Color,
    val syntax: Color,
)

/**
 * Renders inline Markdown into an [AnnotatedString].
 *
 * Two modes, and the distinction is the heart of the editing experience:
 *
 * - [render] **consumes** the syntax markers, producing clean prose for a block the user is not
 *   editing.
 * - [highlight] **keeps** every character and merely styles the markers, so it can back a text
 *   field. Character-for-character preservation is what lets the caller use
 *   `OffsetMapping.Identity` and keeps the caret exactly where the user put it.
 */
object InlineParser {

    fun render(text: String, theme: InlineTheme): AnnotatedString = buildAnnotatedString(text, theme, keepMarkers = false)

    fun highlight(text: String, theme: InlineTheme): AnnotatedString = buildAnnotatedString(text, theme, keepMarkers = true)

    private fun buildAnnotatedString(
        text: String,
        theme: InlineTheme,
        keepMarkers: Boolean,
    ): AnnotatedString {
        val builder = AnnotatedString.Builder(if (keepMarkers) text.length else text.length)
        var i = 0

        while (i < text.length) {
            when {
                // Inline code first: nothing inside a code span is Markdown.
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        builder.append(text[i]); i++
                    } else {
                        val body = text.substring(i + 1, end)
                        val style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = theme.code,
                            background = theme.codeBackground,
                        )
                        appendSpan(builder, "`", body, "`", style, theme, keepMarkers)
                        i = end + 1
                    }
                }

                text.startsWith("$", i) -> {
                    val end = text.indexOf('$', i + 1)
                    if (end < 0) {
                        builder.append(text[i]); i++
                    } else {
                        // Math is preserved and marked, not typeset: a real formula renderer does
                        // not fit the size budget, and mangling the source would be worse than
                        // showing it plainly.
                        val body = text.substring(i + 1, end)
                        val style = SpanStyle(fontFamily = FontFamily.Monospace, color = theme.math)
                        appendSpan(builder, "$", body, "$", style, theme, keepMarkers)
                        i = end + 1
                    }
                }

                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        builder.append(text[i]); i++
                    } else {
                        appendSpan(
                            builder, "**", text.substring(i + 2, end), "**",
                            SpanStyle(fontWeight = FontWeight.Bold), theme, keepMarkers,
                        )
                        i = end + 2
                    }
                }

                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end < 0) {
                        builder.append(text[i]); i++
                    } else {
                        appendSpan(
                            builder, "~~", text.substring(i + 2, end), "~~",
                            SpanStyle(textDecoration = TextDecoration.LineThrough), theme, keepMarkers,
                        )
                        i = end + 2
                    }
                }

                (text[i] == '*' || text[i] == '_') && !isWordChar(text.getOrNull(i - 1)) -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end < 0 || end == i + 1) {
                        builder.append(text[i]); i++
                    } else {
                        appendSpan(
                            builder, marker.toString(), text.substring(i + 1, end), marker.toString(),
                            SpanStyle(fontStyle = FontStyle.Italic), theme, keepMarkers,
                        )
                        i = end + 1
                    }
                }

                text.startsWith("[", i) -> {
                    val close = text.indexOf(']', i)
                    val open = if (close >= 0) text.indexOf('(', close) else -1
                    val end = if (open == close + 1) text.indexOf(')', open) else -1
                    if (end < 0) {
                        builder.append(text[i]); i++
                    } else {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(open + 1, end)
                        val style = SpanStyle(color = theme.link, textDecoration = TextDecoration.Underline)
                        if (keepMarkers) {
                            builder.pushStyle(SpanStyle(color = theme.syntax))
                            builder.append("[")
                            builder.pop()
                            builder.pushStyle(style)
                            builder.append(label)
                            builder.pop()
                            builder.pushStyle(SpanStyle(color = theme.syntax))
                            builder.append("](")
                            builder.append(url)
                            builder.append(")")
                            builder.pop()
                        } else {
                            builder.pushStringAnnotation(TAG_URL, url)
                            builder.pushStyle(style)
                            builder.append(label)
                            builder.pop()
                            builder.pop()
                        }
                        i = end + 1
                    }
                }

                else -> {
                    builder.append(text[i])
                    i++
                }
            }
        }

        return builder.toAnnotatedString()
    }

    private fun appendSpan(
        builder: AnnotatedString.Builder,
        open: String,
        body: String,
        close: String,
        style: SpanStyle,
        theme: InlineTheme,
        keepMarkers: Boolean,
    ) {
        if (keepMarkers) {
            // Markers stay in the text but recede visually, so the source is editable yet quiet.
            builder.pushStyle(style.merge(SpanStyle(color = theme.syntax)))
            builder.append(open)
            builder.pop()
            builder.pushStyle(style)
            builder.append(body)
            builder.pop()
            builder.pushStyle(style.merge(SpanStyle(color = theme.syntax)))
            builder.append(close)
            builder.pop()
        } else {
            builder.pushStyle(style)
            builder.append(body)
            builder.pop()
        }
    }

    private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

    const val TAG_URL = "url"
}
