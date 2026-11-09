package pl.dakil.notes.editor.markdown

/**
 * What a line of Markdown looks like.
 *
 * Hand-rolled rather than pulling in a CommonMark library: Markwon is View-based and heavy, and a
 * full CommonMark implementation is a few hundred kilobytes of which this app would use perhaps a
 * twentieth. What is recognised here — headings, lists, task lists, quotes, fences, tables, rules —
 * is what people put in notes.
 *
 * There is nothing else in this file, and that is the point. There used to be a second, block-level
 * parser building a tree of `MdBlock`s for a renderer that showed Markdown some other way than the
 * editor did. Two implementations of "what does this text mean" is one too many, and the one that
 * survived is the one a caret can be put into: [MarkdownRenderer] plans the same document for
 * display and for editing, and [MarkdownStructure] answers what a keystroke should do to it. Both
 * start from these patterns, so neither can drift from the other.
 */
object MarkdownParser {

    internal val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    internal val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    internal val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    internal val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s*(.*)$")
    internal val QUOTE = Regex("^>\\s?(.*)$")
    internal val RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    /** One hyphen is enough: `|-|-|` is the shortest delimiter GFM accepts, and a common one. */
    internal val TABLE_DELIMITER = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    /**
     * A line that is nothing but one image: `![alt](url)`, `![alt](url "title")` or `![alt][id]`.
     *
     * Group 1 is the alt text, group 2 an inline destination, group 3 a reference label. Such a line
     * is drawn as the picture itself; an image in the middle of prose stays a labelled chip.
     */
    internal val BLOCK_IMAGE =
        Regex("^\\s{0,3}!\\[([^\\[\\]]*)\\](?:\\(\\s*(\\S+?)(?:\\s+\"[^\"]*\")?\\s*\\)|\\[([^\\[\\]]+)\\])\\s*$")
    internal val REFERENCE_DEF = Regex("^\\s{0,3}\\[([^\\]]+)\\]:\\s*(\\S.*)$")

    /**
     * Whether the `|` at [at] separates two table cells, rather than being one written into a cell.
     *
     * GFM lets a cell hold a pipe by escaping it, and a backslash can itself be escaped, so what
     * decides is whether the run of backslashes in front of it is odd. [from] is where the row
     * starts; nothing before it can escape anything on it.
     */
    internal fun isCellPipe(text: CharSequence, at: Int, from: Int = 0): Boolean {
        if (text[at] != '|') return false
        var slashes = 0
        var i = at - 1
        while (i >= from && text[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 0
    }

    /** The offsets of the cell-separating pipes in `[from, to)`. See [isCellPipe]. */
    internal fun cellPipes(text: CharSequence, from: Int = 0, to: Int = text.length): List<Int> {
        val out = ArrayList<Int>()
        for (i in from until to) if (isCellPipe(text, i, from)) out += i
        return out
    }

    /**
     * The line that opens a fenced code block, as CommonMark reads it, or null when [line] is not
     * one: up to three spaces, then three or more backticks or tildes, then an info string.
     *
     * The info string is anything — `c++`, `c#`, `objective-c`, `js title="a.js"` — and its first
     * word is the language. A backtick fence's info may not hold a backtick, which is what keeps
     * ``` ```a``` b ``` a line of inline code rather than the start of a block.
     */
    internal fun openFence(line: CharSequence): Fence? {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        if (i > 3 || i >= line.length) return null
        val char = line[i]
        if (char != '`' && char != '~') return null
        val runStart = i
        while (i < line.length && line[i] == char) i++
        val length = i - runStart
        if (length < 3) return null
        if (char == '`' && (i until line.length).any { line[it] == '`' }) return null
        while (i < line.length && line[i].isWhitespace()) i++
        val languageStart = i
        while (i < line.length && !line[i].isWhitespace()) i++
        return Fence(
            char = char,
            length = length,
            markerEnd = runStart + length,
            languageStart = languageStart,
            languageEnd = i,
        )
    }

    /** Whether [line] closes [fence]: the same character, at least as many of it, and nothing after. */
    internal fun closesFence(line: CharSequence, fence: Fence): Boolean {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        if (i > 3) return false
        val runStart = i
        while (i < line.length && line[i] == fence.char) i++
        if (i - runStart < fence.length) return false
        return (i until line.length).all { line[it].isWhitespace() }
    }
}

/**
 * An opening fence, in offsets within its own line.
 *
 * [markerEnd] is where the run of backticks or tildes stops, and `[languageStart, languageEnd)` is
 * the language — empty, and at the end of the line, when the fence names none.
 */
internal data class Fence(
    val char: Char,
    val length: Int,
    val markerEnd: Int,
    val languageStart: Int,
    val languageEnd: Int,
) {
    fun language(line: CharSequence): String = line.subSequence(languageStart, languageEnd).toString()
}
