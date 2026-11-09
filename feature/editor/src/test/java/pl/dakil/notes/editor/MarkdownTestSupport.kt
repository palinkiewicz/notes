package pl.dakil.notes.editor

import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * What the formatted view shows for [markdown], applied the way `MarkdownOutputTransformation`
 * applies it — back to front over the source — so a test sees exactly what the field would.
 */
internal fun renderedRaw(markdown: String): String {
    val out = StringBuilder(markdown)
    for (edit in MarkdownRenderer.plan(markdown).edits.asReversed()) out.replace(edit.start, edit.end, edit.replacement)
    return out.toString()
}

/** [renderedRaw] with each line's trailing table padding taken off, so expectations stay readable. */
internal fun rendered(markdown: String): String =
    renderedRaw(markdown).lines().joinToString("\n") { it.trimEnd() }

/** The text each range of [style] covers, trimmed of the inserted blanks it can take in at its ends. */
internal fun styledText(markdown: String, style: MdStyle): List<String> {
    val text = renderedRaw(markdown)
    return MarkdownRenderer.plan(markdown).styles
        .filter { it.style == style && it.end > it.start }
        .map { text.substring(it.start, it.end).trim() }
}

/**
 * The promise a plan makes to `TextFieldBuffer`: edits ascending, disjoint and inside the document,
 * styles inside what they render to. A field handed anything else throws on the user's keystroke.
 */
internal fun assertPlanContract(markdown: String) {
    val plan = MarkdownRenderer.plan(markdown)
    var previousEnd = 0
    var previousStart = -1
    for (edit in plan.edits) {
        check(edit.start >= previousEnd) { "edit out of order in [$markdown]: $edit" }
        // Two insertions at one offset are one insertion written twice, and the mapping counts both.
        check(!(edit.start == edit.end && edit.start == previousStart && previousEnd == previousStart)) {
            "two insertions at one offset in [$markdown]: $edit"
        }
        check(edit.end in edit.start..markdown.length) { "edit out of range in [$markdown]: $edit" }
        previousEnd = edit.end
        previousStart = edit.start
    }
    val length = renderedRaw(markdown).length
    for (range in plan.styles) {
        check(range.start in 0..length && range.end in range.start..length) {
            "style out of range in [$markdown]: $range"
        }
    }
}
