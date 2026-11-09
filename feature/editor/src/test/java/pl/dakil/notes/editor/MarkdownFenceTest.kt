package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.KeepFenceIntact
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MarkdownStructure
import pl.dakil.notes.editor.markdown.MdBackspace
import pl.dakil.notes.editor.markdown.MdBlockKind
import pl.dakil.notes.editor.markdown.MdCodeBlock
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * Fenced code as CommonMark defines it, not just as this app writes it.
 *
 * A fence that is not recognised is worse than plain text: its code is read as Markdown, so a shell
 * comment becomes a heading and a pointer becomes italics.
 */
class MarkdownFenceTest {

    private fun blocks(markdown: String) = MarkdownRenderer.plan(markdown).decorations.filterIsInstance<MdCodeBlock>()

    private fun header(markdown: String): String {
        val block = blocks(markdown).single()
        return renderedRaw(markdown).substring(block.headerStart, block.headerEnd)
    }

    @Test
    fun `a language with punctuation in its name opens a fence`() {
        assertEquals("c++", header("```c++\nint x;\n```"))
        assertEquals("c#", header("```c#\nvar x;\n```"))
        assertEquals("objective-c", header("```objective-c\nid x;\n```"))
    }

    @Test
    fun `only the first word of an info string is the language`() {
        assertEquals("js", header("```js title=\"a.js\"\nx\n```"))
        assertFalse(rendered("```js title=\"a.js\"\nx\n```").contains("title"))
    }

    @Test
    fun `a tilde fence is a fence and its code is not markdown`() {
        val source = "~~~\n# not a heading\n~~~"
        assertEquals(1, blocks(source).size)
        assertTrue(styledText(source, MdStyle.H1).isEmpty())
        assertTrue(rendered(source).contains("# not a heading"))
    }

    @Test
    fun `a longer fence can hold a shorter one`() {
        // How a Markdown snippet is shown inside a note: four backticks round three.
        val source = "````md\n```\ninner\n```\n````\nafter"
        assertEquals(1, blocks(source).size)
        assertTrue(rendered(source).contains("```"))
        assertEquals(listOf("after"), rendered(source).lines().filter { it == "after" })
    }

    @Test
    fun `a closing fence cannot carry a language`() {
        // CommonMark: an info string marks an opener, so ```js inside a block is a line of code.
        val source = "```\ncode\n```js\nmore"
        assertEquals(1, blocks(source).size)
        assertTrue(styledText(source, MdStyle.FENCE).any { it.contains("more") })
    }

    @Test
    fun `a fence indented by up to three spaces is a fence`() {
        assertEquals(1, blocks("  ```\ncode\n  ```").size)
    }

    @Test
    fun `inline backticks with text after them are not a fence`() {
        assertTrue(blocks("```a``` b").isEmpty())
    }

    @Test
    fun `the structure model agrees with the renderer about where a fence ends`() {
        val source = "~~~py\nx\n~~~\nafter"
        val block = MarkdownStructure.blocks(source).single { it.kind == MdBlockKind.FENCE }
        assertTrue(block.closed)
        assertEquals(source.indexOf("after"), block.end)
    }

    @Test
    fun `backspace cannot eat into the marker run of a long fence`() {
        val source = "````\ncode\n````"
        // A caret in the hidden run of the opener — after three of its four backticks.
        assertEquals(MdBackspace.Refuse, MarkdownStructure.backspaceAt(source, 4))
    }

    @Test
    fun `text typed in front of a tilde fence is caught`() {
        assertTrue(KeepFenceIntact.opensFenceAt("py~~~\nx\n~~~", 0, 2))
    }

    @Test
    fun `a paste of several lines in front of a fence is left where it was put`() {
        // Moving it past the backticks made its first line the fence's language.
        assertFalse(KeepFenceIntact.opensFenceAt("foo\nbar\n```\nx\n```", 0, 8))
    }
}
