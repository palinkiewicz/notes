package pl.dakil.notes.editor.export

import androidx.compose.ui.text.AnnotatedString
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * A note's Markdown, laid out for the printer.
 *
 * The exporters run off the composition — on a background thread, painting into a `Paragraph` — so
 * they cannot mount an editor to ask it what the text looks like. A bare [RichTextState] is the
 * answer: it is the same parser the screen uses, so what prints is what was on the page, and it
 * needs no `MaterialTheme` to resolve because the export palette is fixed.
 *
 * Sizes and colours are not carried, for the same reason the format bar does not offer them: the
 * Markdown on disk never held any.
 */
internal fun exportAnnotatedMarkdown(markdown: String): AnnotatedString =
    RichTextState().apply { setMarkdown(markdown) }.annotatedString
