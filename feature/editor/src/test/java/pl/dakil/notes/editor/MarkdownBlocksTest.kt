package pl.dakil.notes.editor

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownBlocks
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdItem
import pl.dakil.notes.editor.markdown.MdSlice
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * Cutting a note into separately drawn blocks, judged against the one thing that must not change:
 * what the whole note looks like. Every block is read off the plan of the whole document, so each
 * of these is a promise that stitching the blocks back together gives that rendering exactly.
 */
class MarkdownBlocksTest {

    private fun items(doc: String) = MarkdownBlocks.partition(doc, MarkdownRenderer.plan(doc).edits)

    private fun slices(doc: String): List<MdSlice> {
        val plan = MarkdownRenderer.plan(doc)
        return items(doc).map { MarkdownBlocks.slice(doc, plan, it.start, it.end) }
    }

    /** Every document the generator makes, plus the hand-written ones. */
    private val documents: List<String> by lazy {
        listOf(MarkdownRenderPlanTest.KITCHEN_SINK, "", "one line", "\n\n\nafter blanks", "a\n\n") +
            (0 until 300).map { generate(Random(it)) }
    }

    @Test
    fun `the items cover the document without gaps or overlaps`() {
        for (doc in documents) {
            val items = items(doc)
            assertEquals(doc, 0, items.first().start)
            assertEquals(doc, doc.length, items.last().end)
            for ((a, b) in items.zipWithNext()) assertEquals(doc, a.end, b.start)
            for (item in items) assertTrue(doc, item.end >= item.start)
        }
    }

    @Test
    fun `no plan edit straddles an item boundary`() {
        for (doc in documents) {
            val edits = MarkdownRenderer.plan(doc).edits
            for (item in items(doc).drop(1)) {
                val cut = item.start
                for (edit in edits) {
                    assertTrue("$edit reaches across $cut in [$doc]", !(edit.start < cut && edit.end >= cut))
                }
            }
        }
    }

    @Test
    fun `the character before every item but the first is a newline nobody edits`() {
        for (doc in documents) {
            val hidden = MarkdownRenderer.plan(doc).hidden
            for (item in items(doc).drop(1)) {
                assertEquals(doc, '\n', doc[item.start - 1])
                assertTrue(doc, !hidden[item.start - 1])
            }
        }
    }

    @Test
    fun `a boundary an edit would straddle merges the items instead`() {
        // The image takes the newline above it — the blank line's — so the cut in front of it would
        // leave the image's edit hanging over the edge of the item above.
        val doc = "Text above.\n\n![pic](a.png)\n\nText below."
        val starts = items(doc).map { it.start }
        assertTrue(starts.toString(), doc.indexOf("![pic]") !in starts)
        assertEquals(renderedRaw(doc), slices(doc).joinToString("") { it.text.drop(it.lead) })
    }

    @Test
    fun `blank lines go with the block above them`() {
        val doc = "# Title\n\nFirst paragraph.\n\n\nSecond paragraph."
        assertEquals(
            listOf("# Title\n\n", "First paragraph.\n\n\n", "Second paragraph."),
            items(doc).map { doc.substring(it.start, it.end) },
        )
    }

    @Test
    fun `the slices concatenated equal the rendering of the whole document`() {
        for (doc in documents) {
            assertPlanContract(doc)
            assertEquals(doc, renderedRaw(doc), slices(doc).joinToString("") { it.text.drop(it.lead) })
        }
    }

    @Test
    fun `every slice's styles, indents and decorations equal the whole plan's shifted into it`() {
        for (doc in documents) {
            val plan = MarkdownRenderer.plan(doc)
            val whole = renderedRaw(doc)
            val expected = styleMap(whole.length, plan.styles.map { Triple(it.start, it.end, it.style) })
            val expectedIndents = styleMap(whole.length, plan.indents.map { Triple(it.start, it.end, it.style) })
            val actual = HashMap<Int, MutableSet<MdStyle>>()
            val actualIndents = HashMap<Int, MutableSet<MdStyle>>()
            var decorations = 0
            var offset = 0
            for (slice in slices(doc)) {
                val base = offset - slice.lead
                for (r in slice.styles) for (i in r.start until r.end) actual.getOrPut(i + base) { HashSet() } += r.style
                for (r in slice.indents) for (i in r.start until r.end) actualIndents.getOrPut(i + base) { HashSet() } += r.style
                decorations += slice.decorations.size
                offset += slice.text.length - slice.lead
            }
            assertEquals(doc, expected, actual)
            assertEquals(doc, expectedIndents, actualIndents)
            assertEquals(doc, plan.decorations.size, decorations)
        }
    }

    private fun styleMap(length: Int, ranges: List<Triple<Int, Int, MdStyle>>): Map<Int, Set<MdStyle>> {
        val out = HashMap<Int, MutableSet<MdStyle>>()
        for ((s, e, style) in ranges) for (i in s until minOf(e, length)) out.getOrPut(i) { HashSet() } += style
        return out
    }

    @Test
    fun `the indexed offset map answers exactly as walking the edits does`() {
        // The plan maps every style through it; a disagreement anywhere moves a style off its text.
        for (doc in documents) {
            val edits = MarkdownRenderer.plan(doc).edits
            val map = MarkdownRenderer.OffsetMap(edits)
            for (o in 0..doc.length) for (include in listOf(true, false)) {
                assertEquals("offset $o of [$doc]", MarkdownRenderer.transformedOffset(edits, o, include), map.map(o, include))
            }
        }
    }

    @Test
    fun `a reference defined in another block still resolves`() {
        val doc = "See [the site][ref] for more.\n\n[ref]: https://example.com"
        assertEquals("See the site for more.", slices(doc).first().text.trimEnd())
    }

    @Test
    fun `an unclosed fence runs its item to the end of the document`() {
        val doc = "Before.\n\n```\ncode\n\nmore code\n\nstill code"
        val items = items(doc)
        assertEquals(2, items.size)
        assertEquals(doc.indexOf("```"), items[1].start)
        assertEquals(doc.length, items[1].end)
    }

    @Test
    fun `a fence with blank lines inside it is one item`() {
        val doc = "```\na\n\nb\n```\n\nafter"
        assertEquals(listOf(0, doc.indexOf("after")), items(doc).map { it.start })
    }

    @Test
    fun `an empty document is one empty item`() {
        assertEquals(listOf(MdItem(0, 0)), items(""))
        assertEquals("", slices("").single().text)
    }

    @Test
    fun `a slice starts with the newline in front of it`() {
        val doc = "one\n\ntwo"
        val second = slices(doc)[1]
        assertEquals(1, second.lead)
        assertEquals("\ntwo", second.text)
    }

    @Test
    fun `a visible character lands on the same rendered character in its slice as in the whole document`() {
        for (doc in documents) {
            val plan = MarkdownRenderer.plan(doc)
            val whole = renderedRaw(doc)
            for (slice in slices(doc)) {
                for (o in slice.start until slice.end) {
                    if (plan.edits.any { o >= it.start && o < it.end }) continue
                    val inWhole = MarkdownRenderer.transformedOffset(plan.edits, o)
                    val inSlice = slice.toSlice(o)
                    assertEquals("offset $o of [$doc]", whole[inWhole], slice.text[inSlice])
                    assertEquals("offset $o of [$doc]", o, slice.toSource(inSlice))
                }
            }
        }
    }

    @Test
    fun `a selection within the cap makes a window of every item it spans`() {
        val doc = "one\n\ntwo\n\nthree\n\nfour"
        val items = items(doc)
        assertEquals(1..2, MarkdownBlocks.windowFor(items, doc.indexOf("two"), doc.indexOf("three") + 2))
        assertEquals(3..3, MarkdownBlocks.windowFor(items, doc.length, doc.length))
    }

    @Test
    fun `a selection bigger than the cap is shown from the item it ends in`() {
        val paragraph = "word ".repeat(400).trim()
        val doc = List(6) { paragraph }.joinToString("\n\n")
        val items = items(doc)
        assertEquals(6, items.size)
        assertEquals(0..0, MarkdownBlocks.windowFor(items, doc.length, 0))
        assertEquals(5..5, MarkdownBlocks.windowFor(items, 0, doc.length))
    }

    @Test
    fun `item keys stay unique when two blocks have the same text`() {
        val doc = "---\n\n- [ ] a\n\n---\n\n- [ ] a\n\n---"
        val keys = MarkdownBlocks.keys(doc, items(doc))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an item's key survives an edit in another item`() {
        val before = "one\n\ntwo\n\nthree"
        val after = "one more\n\ntwo\n\nthree"
        assertEquals(
            MarkdownBlocks.keys(before, items(before)).drop(1),
            MarkdownBlocks.keys(after, items(after)).drop(1),
        )
    }

    @Test
    fun `source mode cuts at the same blank lines without any plan`() {
        val doc = "# Title\n\n```\na\n\nb\n```\n\ntext"
        assertEquals(items(doc), MarkdownBlocks.items(doc, formatted = false))
    }

    // ---- The window's edges ------------------------------------------------------------------

    private val doc = "First block.\n\nSecond block here.\n\nThird block."
    private val ws = doc.indexOf("Second")
    private val we = doc.indexOf("Third")
    private val window = MarkdownBlocks.bounds(ws, we)

    @Test
    fun `a backspace at the start of a block deletes only the newline before it`() {
        // The field reaching through the trimmed-off head would take everything above the block.
        assertEquals(ws - 1 until ws, MarkdownBlocks.repairChange(window, ws, ws, 0, ws, inserted = 0))
    }

    @Test
    fun `a forward delete at the end of a block never reaches the next block`() {
        val caret = we - 1
        assertEquals(caret until caret + 1, MarkdownBlocks.repairChange(window, caret, caret, caret, doc.length, inserted = 0))
    }

    @Test
    fun `an edit inside the window is left alone`() {
        assertNull(MarkdownBlocks.repairChange(window, ws + 3, ws + 3, ws + 2, ws + 3, inserted = 0))
        assertNull(MarkdownBlocks.repairChange(window, ws, ws, ws - 1, ws, inserted = 0))
    }

    @Test
    fun `a selection the user made across blocks is deleted as asked`() {
        assertNull(MarkdownBlocks.repairChange(window, 2, ws + 4, 2, ws + 4, inserted = 0))
    }

    @Test
    fun `an arrow onto the separator above puts the caret at the end of the previous block`() {
        // The field maps the one point the trimmed head became back to all of it.
        assertEquals(ws - 1 to ws - 1, MarkdownBlocks.repairSelection(window, doc.length, ws, ws, 0, ws - 1))
    }

    @Test
    fun `moving onto the trailing line puts the caret at the start of the next block`() {
        assertEquals(we to we, MarkdownBlocks.repairSelection(window, doc.length, we - 1, we - 1, we, doc.length))
    }

    @Test
    fun `an anchor outside the window survives a shift-arrow`() {
        val anchor = 3
        assertEquals(
            anchor to ws + 2,
            MarkdownBlocks.repairSelection(window, doc.length, anchor, ws + 4, 0, ws + 2),
        )
    }

    @Test
    fun `select all is left as select all`() {
        assertNull(MarkdownBlocks.repairSelection(window, doc.length, ws, ws, 0, doc.length))
    }

    @Test
    fun `a caret moved within the window is left alone`() {
        assertNull(MarkdownBlocks.repairSelection(window, doc.length, ws, ws, ws + 5, ws + 5))
    }

    @Test
    fun `the window of a caret at the start of a block is that block`() {
        val layout = MarkdownBlocks.layout(doc, ws, ws, formatted = true)
        assertEquals(ws, layout.windowStart)
        assertEquals(we, layout.windowEnd)
    }

    // ---- A generator of documents with every construct the cutter has to respect ---------------

    private fun generate(rng: Random): String {
        val pieces = listOf(
            "# Heading",
            "Plain **bold** and *italic* text with `code`.",
            "- item one\n- item two\n  - nested",
            "- [ ] task\n- [x] done",
            "1. first\n2. second",
            "> quoted\n> again",
            "```kotlin\nval x = 1\n\nval y = 2\n```",
            "```\nunclosed fence",
            "$$\nx^2\n\ny\n$$",
            "| a | b |\n| --- | --- |\n| 1 | 2 |",
            "---",
            "![pic](a.png)",
            "See [x][r] here.",
            "[r]: https://example.com",
            "Line one\nline two",
            "[big]{size=24} words",
        )
        val out = StringBuilder()
        repeat(rng.nextInt(1, 9)) {
            if (out.isNotEmpty()) out.append("\n".repeat(rng.nextInt(1, 4)))
            out.append(pieces[rng.nextInt(pieces.size)])
        }
        if (rng.nextBoolean()) out.append("\n".repeat(rng.nextInt(1, 3)))
        return out.toString()
    }
}
