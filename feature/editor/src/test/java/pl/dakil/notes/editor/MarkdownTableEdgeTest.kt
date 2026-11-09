package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdTable

/**
 * Tables written the ways people and other tools actually write them.
 *
 * Each of these is valid GFM that another editor renders as a table, so a note synced from one of
 * them arrives with it — and a table that renders as garbage here is a note the user cannot read.
 */
class MarkdownTableEdgeTest {

    private fun tables(markdown: String) = MarkdownRenderer.plan(markdown).decorations.filterIsInstance<MdTable>()

    @Test
    fun `an empty cell keeps the row's edits in order and draws one rule per pipe`() {
        // `||` used to emit the pipe's own edit ahead of the empty cell's padding at the same offset,
        // and then visit the pipe a second time — two rules on one border and a row out of line.
        val source = "| a || c |\n|---|---|---|\n| 1 | 2 | 3 |"
        assertPlanContract(source)
        val table = tables(source).single()
        assertEquals(4, table.columnStops.size)
        assertEquals(table.columnStops, table.columnStops.distinct())
        val rows = rendered(source).lines()
        // Every row ends at the same column, which is what lining up means.
        assertEquals(renderedRaw(source).lines()[0].length, renderedRaw(source).lines()[1].length)
        assertTrue(rows[0].contains("a") && rows[0].contains("c"))
    }

    @Test
    fun `an empty header cell is still a column`() {
        val source = "|| b |\n|---|---|\n| 1 | 2 |"
        assertPlanContract(source)
        assertEquals(3, tables(source).single().columnStops.size)
    }

    @Test
    fun `a delimiter row of single hyphens makes a table`() {
        // `|-|-|` is the shortest delimiter GFM accepts, and what people type by hand on a phone.
        val source = "|a|b|\n|-|-|\n|1|2|"
        assertEquals(1, tables(source).size)
        assertEquals(listOf("a  b", "1  2"), rendered(source).lines().map { it.trim() })
    }

    @Test
    fun `a delimiter row that does not match the header's columns is not a table`() {
        // GFM's own rule, and what keeps a line of prose with a pipe in it from becoming a header.
        assertTrue(tables("| a | b |\n|---|\n| 1 | 2 |").isEmpty())
    }

    @Test
    fun `an escaped pipe belongs to its cell`() {
        val source = "| a \\| b | c |\n|---|---|\n| 1 | 2 |"
        assertPlanContract(source)
        assertEquals(3, tables(source).single().columnStops.size)
        assertEquals(2, MarkdownRenderer.cellTexts(source, 0, source.indexOf('\n')).size)
    }

    @Test
    fun `table actions find the cell of an escaped pipe's row`() {
        val source = "| a \\| b | c |\n|---|---|\n| 1 | 2 |"
        val cell = MarkdownStructure.cellAt(source, source.indexOf('c'))
        assertEquals(1, cell?.column)
    }
}
