package pl.dakil.notes.editor

import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MarkdownStructure
import kotlin.random.Random

/**
 * Every pure Markdown function, over every offset of a few thousand generated documents.
 *
 * The editor's rules are all "look at the line the caret is on", and the caret can be anywhere —
 * including places nobody types, like offset zero of a note that opens with a blank line, which is
 * where a `lastIndexOf` bound taken inclusively produced a line start *after* the caret and a
 * substring range that ran backwards. That crashed the app on the first press of any block button.
 * A case like that is not one anybody writes a test for by hand; sweeping the whole space is.
 *
 * It also holds the contract the text field depends on: the plan's edits have to be ascending,
 * disjoint and inside the document, because `TextFieldBuffer` applies them back to front and trusts
 * that they are.
 */
class MarkdownRobustnessTest {

    private val fragments = listOf(
        "```", "```kt", "$$", "| a | b |", "| --- | --- |", "---", "# h", "## h",
        "- a", "- ", "1. a", "- [ ] a", "- [x] a", "> q", "text", "", "  - b", "~~s~~",
        "**b**", "`c`", "![a](u)", "[a](u)", "|", "- # a", "    1. # a", "> - # a",
        // Nested emphasis of unequal widths, which is where the closer is chosen by arithmetic
        // rather than by the next run that matches: `*a**b***`, `**a ** b**` and their relatives.
        "*a**b***", "**a*b***", "*a **b** c*", "***both***", "**a ** b**", "*a **b** *",
    )

    private fun documents(): Sequence<String> = sequence {
        val random = Random(seed = 7)
        repeat(3000) {
            val lines = List(random.nextInt(0, 6)) { fragments[random.nextInt(fragments.size)] }
            yield(lines.joinToString("\n") + if (random.nextBoolean()) "\n" else "")
        }
    }

    @Test
    fun `no document and no offset makes a markdown function throw`() {
        for (document in documents()) {
            try {
                checkPlan(document)
                for (at in 0..document.length) checkOffset(document, at)
            } catch (cause: Throwable) {
                throw AssertionError("failed on [${document.replace("\n", "\\n")}]", cause)
            }
        }
    }

    /** The plan is a promise to `TextFieldBuffer`, and this is the promise spelled out. */
    private fun checkPlan(document: String) {
        val plan = MarkdownRenderer.plan(document)
        var previousEnd = 0
        for (edit in plan.edits) {
            check(edit.start in previousEnd..document.length) { "edit out of order or range: $edit" }
            check(edit.end in edit.start..document.length) { "edit ends out of range: $edit" }
            previousEnd = edit.end
        }
        val rendered = MarkdownRenderer.render(document)
        for (range in plan.styles) {
            check(range.start in 0..rendered.length && range.end in range.start..rendered.length) {
                "style out of range: $range"
            }
        }
    }

    private fun checkOffset(document: String, at: Int) {
        MarkdownStructure.backspaceAt(document, at)
        MarkdownStructure.rowBelow(document, at)
        MarkdownStructure.cellCaret(document, at)
        MarkdownStructure.cellAt(document, at)?.let { cell ->
            MarkdownStructure.addRow(document, cell.table, cell.row)
            MarkdownStructure.addColumn(document, cell.table, cell.column)
            MarkdownStructure.removeRow(document, cell.table, cell.row)
            MarkdownStructure.removeColumn(document, cell.table, cell.column)
            MarkdownStructure.removeTable(document, cell.table)
        }
        MarkdownStructure.breaksLineOnly(document.substring(at))
        MarkdownStructure.stylesOpenAt(document, at)
        MarkdownStructure.splitOpenRuns(document, at, at, "x", 1)
        MarkdownStructure.keepMarkers(document, at, document.length, "x")
        MarkdownStructure.spaceOutsideRun(document, at, " ")
        MarkdownStructure.spaceOutsideRunAfterDeletion(document, at, (at + 1).coerceAtMost(document.length))
        MarkdownStructure.openerOutsideRunAfterDeletion(document, at, (at + 1).coerceAtMost(document.length))
        MarkdownRenderer.takesMoreThanOneVisibleCharacter(document, at, document.length)
        MarkdownRenderer.visibleSpan(document, at, document.length)
        for (marker in listOf("**", "*", "~~", "`", "***")) {
            MarkdownActions.toggleArmedMarker(MarkdownStructure.stylesOpenAt(document, at), marker)
        }
        MarkdownStructure.extendableRun(document, at, "**")
        MarkdownStructure.runCaret(document, at)
        MarkdownActions.blockStyleAt(document, at)
        MarkdownActions.paragraphStyleAt(document, at)
        MarkdownActions.listStyleAt(document, at)
        MarkdownActions.listMarkerEnd(document, at)
        MarkdownActions.unindentOrUnlist(document, at)
        MarkdownActions.insertCodeFence(document, at)
        MarkdownActions.insertTable(document, at)
        MarkdownActions.insertRule(document, at)

        for (to in at..document.length) {
            for (marker in listOf("**", "*", "~~", "`")) {
                MarkdownActions.toggleWrap(document, at, to, marker)
            }
            MarkdownActions.activeInlineMarkers(document, at, to)
            MarkdownActions.indentList(document, at, to)
            MarkdownActions.outdentList(document, at, to)
            MarkdownActions.canIndent(document, at, to)
            MarkdownActions.canOutdent(document, at, to)
            MarkdownRenderer.coversVisibleText(document, at, to)
                MarkdownRenderer.hidesAnythingIn(document, at, to)
                MarkdownRenderer.lastVisibleBefore(document, at, to)?.let {
                    check(it in at until maxOf(to, at + 1)) { "lastVisibleBefore($at, $to) gave $it" }
                }
            for (style in MarkdownActions.BlockStyle.entries) {
                MarkdownActions.toggleBlockStyle(document, at, to, style)
            }
        }
    }
}
