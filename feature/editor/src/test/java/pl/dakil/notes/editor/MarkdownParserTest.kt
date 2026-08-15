package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownParser
import pl.dakil.notes.editor.markdown.MdBlock

class MarkdownParserTest {

    private fun parse(md: String) = MarkdownParser.parse(md).filterNot { it is MdBlock.Blank }

    @Test
    fun `headings carry their level`() {
        val blocks = parse("# One\n## Two\n###### Six")
        assertEquals(listOf(1, 2, 6), blocks.map { (it as MdBlock.Heading).level })
        assertEquals("One", (blocks[0] as MdBlock.Heading).text)
    }

    @Test
    fun `a hash without a space is not a heading`() {
        assertTrue(parse("#hashtag").single() is MdBlock.Paragraph)
    }

    @Test
    fun `task items are recognised before plain bullets`() {
        // Order matters: a task item is a bullet, so testing bullets first would swallow it.
        val blocks = parse("- [ ] open\n- [x] done\n- plain")
        assertTrue(blocks[0] is MdBlock.TaskItem)
        assertTrue(blocks[1] is MdBlock.TaskItem)
        assertTrue(blocks[2] is MdBlock.BulletItem)
        assertEquals(false, (blocks[0] as MdBlock.TaskItem).checked)
        assertEquals(true, (blocks[1] as MdBlock.TaskItem).checked)
    }

    @Test
    fun `ordered items keep their numbering`() {
        val blocks = parse("1. first\n2. second\n7) seventh")
        assertEquals(listOf(1, 2, 7), blocks.map { (it as MdBlock.OrderedItem).number })
    }

    @Test
    fun `indentation becomes nesting depth`() {
        val blocks = parse("- top\n  - nested\n    - deeper")
        assertEquals(listOf(0, 1, 2), blocks.map { (it as MdBlock.BulletItem).indent })
    }

    @Test
    fun `fenced code is taken verbatim`() {
        // Nothing inside a fence may be reinterpreted, or code samples containing Markdown break.
        val block = parse("```kotlin\n# not a heading\n- not a bullet\n```").single() as MdBlock.CodeFence
        assertEquals("kotlin", block.language)
        assertEquals("# not a heading\n- not a bullet", block.code)
    }

    @Test
    fun `an unterminated fence still parses to the end`() {
        val block = parse("```\nstill code").single() as MdBlock.CodeFence
        assertEquals("still code", block.code)
    }

    @Test
    fun `consecutive quote lines join into one block`() {
        val block = parse("> first\n> second").single() as MdBlock.Quote
        assertEquals("first\nsecond", block.text)
    }

    @Test
    fun `a table needs its delimiter row`() {
        val table = parse("| a | b |\n| --- | --- |\n| 1 | 2 |").single() as MdBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)

        // Without the delimiter it is just a paragraph containing pipes.
        assertTrue(parse("| a | b |\n| 1 | 2 |").first() is MdBlock.Paragraph)
    }

    @Test
    fun `horizontal rules are recognised in their several spellings`() {
        for (rule in listOf("---", "***", "___", "- - -")) {
            assertEquals(rule, MdBlock.Rule, parse(rule).single())
        }
    }

    @Test
    fun `math blocks are preserved rather than reflowed`() {
        val block = parse("$$\n\\int_0^1 x^2 dx\n$$").single() as MdBlock.MathBlock
        assertEquals("\\int_0^1 x^2 dx", block.latex)
    }

    @Test
    fun `paragraphs absorb soft-wrapped lines but stop at new blocks`() {
        val blocks = parse("one\ntwo\n# heading")
        assertEquals("one\ntwo", (blocks[0] as MdBlock.Paragraph).text)
        assertTrue(blocks[1] is MdBlock.Heading)
    }

    @Test
    fun `an empty document parses to nothing`() {
        assertTrue(parse("").isEmpty())
    }

    // ---- Task toggling -------------------------------------------------------------------------

    @Test
    fun `toggling a checkbox rewrites only that line`() {
        // Operating on the source rather than a parsed tree means a checkbox tap never reformats
        // the rest of the user's Markdown.
        val source = "# Notes\n- [ ] first\n- [ ] second\n\n  odd   spacing   kept"
        val toggled = MarkdownParser.toggleTask(source, 2)
        assertEquals("# Notes\n- [ ] first\n- [x] second\n\n  odd   spacing   kept", toggled)
    }

    @Test
    fun `toggling is reversible`() {
        val source = "- [x] done"
        assertEquals("- [ ] done", MarkdownParser.toggleTask(source, 0))
        assertEquals(source, MarkdownParser.toggleTask(MarkdownParser.toggleTask(source, 0), 0))
    }

    @Test
    fun `toggling a non-task line changes nothing`() {
        val source = "# Heading\njust text"
        assertEquals(source, MarkdownParser.toggleTask(source, 1))
        assertEquals(source, MarkdownParser.toggleTask(source, 99))
    }

    @Test
    fun `task line indices match the source, not the rendered blocks`() {
        // The renderer hands back a source line number; a mismatch would tick the wrong box.
        val source = "# Title\n\nintro\n\n- [ ] alpha\n- [ ] beta"
        val tasks = MarkdownParser.parse(source).filterIsInstance<MdBlock.TaskItem>()
        assertEquals(listOf(4, 5), tasks.map { it.line })
        assertTrue(MarkdownParser.toggleTask(source, tasks[1].line).contains("- [x] beta"))
    }
}
