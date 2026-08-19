package pl.dakil.notes.editor.markdown

/**
 * A block-level Markdown parser covering the subset a note-taking app actually uses.
 *
 * Hand-rolled rather than pulling in a CommonMark library: Markwon is View-based and heavy, and a
 * full CommonMark implementation is a few hundred kilobytes of which this app would use perhaps a
 * twentieth. What is here — headings, lists, task lists, quotes, fences, tables, rules — is what
 * people put in notes.
 *
 * Inline spans are handled separately by [InlineParser], because the editor styles inline syntax
 * live inside a text field while block structure only matters at render time.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    // `heading` is 0 for an ordinary item. A list item may contain a heading — `- # Alpha` is what
    // CommonMark says a bulleted heading is — so the two are separate fields rather than separate
    // block kinds; every list item can carry one.
    data class BulletItem(val text: String, val indent: Int, val heading: Int = 0) : MdBlock
    data class OrderedItem(val text: String, val indent: Int, val number: Int, val heading: Int = 0) : MdBlock
    data class TaskItem(
        val text: String,
        val indent: Int,
        val checked: Boolean,
        val line: Int,
        val heading: Int = 0,
    ) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeFence(val language: String, val code: String) : MdBlock
    data class MathBlock(val latex: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
    data object Blank : MdBlock
}

object MarkdownParser {

    // `internal` so the WYSIWYG renderer recognises exactly the same blocks this parser does; two
    // sets of line patterns would drift, and the drift would show as text that renders one way and
    // edits another.
    internal val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    internal val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    internal val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    internal val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s*(.*)$")
    internal val QUOTE = Regex("^>\\s?(.*)$")
    internal val FENCE = Regex("^```\\s*(\\w*)\\s*$")
    internal val RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    internal val TABLE_DELIMITER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.lines()
        val out = ArrayList<MdBlock>(lines.size)
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fences first: their contents must not be interpreted as anything else.
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val language = fence.groupValues[1]
                val code = StringBuilder()
                i++
                while (i < lines.size && FENCE.matchEntire(lines[i]) == null) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                i++ // closing fence, or end of input
                out += MdBlock.CodeFence(language, code.toString())
                continue
            }

            if (line.trim() == "$$") {
                val latex = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != "$$") {
                    if (latex.isNotEmpty()) latex.append('\n')
                    latex.append(lines[i])
                    i++
                }
                i++
                out += MdBlock.MathBlock(latex.toString())
                continue
            }

            if (line.isBlank()) {
                out += MdBlock.Blank
                i++
                continue
            }

            if (RULE.matchEntire(line) != null) {
                out += MdBlock.Rule
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }

            // Task items must be tested before plain bullets, since they are a bullet subtype.
            val task = TASK.matchEntire(line)
            if (task != null) {
                val body = headingIn(task.groupValues[3])
                out += MdBlock.TaskItem(
                    text = body.second,
                    indent = task.groupValues[1].length / 2,
                    checked = task.groupValues[2].lowercase() == "x",
                    line = i,
                    heading = body.first,
                )
                i++
                continue
            }

            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                val body = headingIn(bullet.groupValues[2])
                out += MdBlock.BulletItem(body.second, bullet.groupValues[1].length / 2, body.first)
                i++
                continue
            }

            val ordered = ORDERED.matchEntire(line)
            if (ordered != null) {
                val body = headingIn(ordered.groupValues[3])
                out += MdBlock.OrderedItem(
                    text = body.second,
                    indent = ordered.groupValues[1].length / 2,
                    number = ordered.groupValues[2].toIntOrNull() ?: 1,
                    heading = body.first,
                )
                i++
                continue
            }

            val quote = QUOTE.matchEntire(line)
            if (quote != null) {
                // Consecutive quote lines join into one block, as CommonMark specifies.
                val text = StringBuilder(quote.groupValues[1])
                i++
                while (i < lines.size) {
                    val next = QUOTE.matchEntire(lines[i]) ?: break
                    text.append('\n').append(next.groupValues[1])
                    i++
                }
                out += MdBlock.Quote(text.toString())
                continue
            }

            // A table needs its delimiter row to be a table at all.
            if (line.contains('|') && i + 1 < lines.size && TABLE_DELIMITER.matchEntire(lines[i + 1]) != null) {
                val header = splitRow(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }

            // Paragraph: absorb following lines until a blank or a line that starts a new block.
            val paragraph = StringBuilder(line)
            i++
            while (i < lines.size && lines[i].isNotBlank() && !startsNewBlock(lines[i])) {
                paragraph.append('\n').append(lines[i])
                i++
            }
            out += MdBlock.Paragraph(paragraph.toString())
        }

        return out
    }

    /** A list item's own heading level and the text after it, or level 0 and the text as it was. */
    private fun headingIn(text: String): Pair<Int, String> {
        val match = HEADING.matchEntire(text) ?: return 0 to text
        return match.groupValues[1].length to match.groupValues[2]
    }

    private fun startsNewBlock(line: String): Boolean =
        HEADING.matchEntire(line) != null ||
            BULLET.matchEntire(line) != null ||
            ORDERED.matchEntire(line) != null ||
            QUOTE.matchEntire(line) != null ||
            FENCE.matchEntire(line) != null ||
            RULE.matchEntire(line) != null

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    /**
     * Toggles the checkbox on [lineIndex] and returns the rewritten document.
     *
     * Operating on the source text rather than a parsed tree means a checkbox tap never reflows or
     * normalises the rest of the user's Markdown.
     */
    fun toggleTask(markdown: String, lineIndex: Int): String {
        val lines = markdown.lines().toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return markdown
        val match = TASK.matchEntire(line) ?: return markdown
        val checked = match.groupValues[2].lowercase() == "x"
        val open = line.indexOf('[')
        if (open < 0 || open + 1 >= line.length) return markdown
        lines[lineIndex] = line.substring(0, open + 1) +
            (if (checked) " " else "x") +
            line.substring(open + 2)
        return lines.joinToString("\n")
    }
}
