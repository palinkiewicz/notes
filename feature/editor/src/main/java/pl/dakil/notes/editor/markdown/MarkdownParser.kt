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
    internal val FENCE = Regex("^```\\s*(\\w*)\\s*$")
    internal val RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    internal val TABLE_DELIMITER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    /**
     * A line that is nothing but one image: `![alt](url)`, `![alt](url "title")` or `![alt][id]`.
     *
     * Group 1 is the alt text, group 2 an inline destination, group 3 a reference label. Such a line
     * is drawn as the picture itself; an image in the middle of prose stays a labelled chip.
     */
    internal val BLOCK_IMAGE =
        Regex("^\\s{0,3}!\\[([^\\[\\]]*)\\](?:\\(\\s*(\\S+?)(?:\\s+\"[^\"]*\")?\\s*\\)|\\[([^\\[\\]]+)\\])\\s*$")
    internal val REFERENCE_DEF = Regex("^\\s{0,3}\\[([^\\]]+)\\]:\\s*(\\S.*)$")
}
