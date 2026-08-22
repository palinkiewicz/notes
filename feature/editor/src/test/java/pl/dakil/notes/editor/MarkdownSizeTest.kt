package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle

/**
 * `[text]{size=18}`: the one styling a sheet has that a `.md` note does not.
 *
 * The tag is understood everywhere — hidden from the reader in both kinds of note — and only a
 * sheet acts on it. That split is what lets text be copied from a page of paper into a Markdown
 * file without the file gaining punctuation nobody typed or losing the size on the way back.
 */
class MarkdownSizeTest {

    private fun rendered(markdown: String) = MarkdownRenderer.render(markdown)

    private fun sizesIn(markdown: String) =
        MarkdownRenderer.plan(markdown).styles.filter { it.style == MdStyle.SIZE }

    @Test
    fun `the tag is invisible and the text inside it is not`() {
        assertEquals("big words here", rendered("[big words]{size=24} here"))
    }

    @Test
    fun `the size is carried on the run it wraps`() {
        val size = sizesIn("a [big]{size=24} b").single()
        assertEquals(24, size.arg)
        // Transformed coordinates: the `[` before it is already gone by the time this is used.
        assertEquals("big", rendered("a [big]{size=24} b").substring(size.start, size.end))
    }

    @Test
    fun `a link is still a link`() {
        // Both start with a bracket, and only the suffix tells them apart.
        assertEquals("label", rendered("[label](https://example.com)"))
        assertTrue(sizesIn("[label](https://example.com)").isEmpty())
    }

    @Test
    fun `other styles still apply inside a sized span`() {
        val styles = MarkdownRenderer.plan("[a **b** c]{size=20}").styles
        assertTrue(styles.any { it.style == MdStyle.BOLD })
        assertEquals(20, styles.single { it.style == MdStyle.SIZE }.arg)
        assertEquals("a b c", rendered("[a **b** c]{size=20}"))
    }

    @Test
    fun `a sized span nested in another closes at its own bracket`() {
        // Taking the first `]{` would end the outer span in the middle of the inner one, and style
        // half a sentence at the wrong size.
        val source = "[a [b]{size=12} c]{size=24}"
        assertEquals("a b c", rendered(source))
        assertEquals(listOf(12, 24), sizesIn(source).map { it.arg }.sorted())
    }

    @Test
    fun `nonsense in the braces is left as the text it is`() {
        for (source in listOf("[a]{size=}", "[a]{size=abc}", "[a]{size=0}", "[a]{size=9999}", "[a]{}")) {
            assertTrue("$source should not parse as a size", sizesIn(source).isEmpty())
            // And nothing is hidden: what cannot be understood stays on screen to be corrected.
            assertTrue("$source should stay visible", rendered(source).contains("{"))
        }
    }

    @Test
    fun `an empty span is not one`() {
        assertTrue(sizesIn("[]{size=24}").isEmpty())
    }

    // ---- The bar's actions ------------------------------------------------------------------------

    @Test
    fun `setting a size wraps the selection and keeps it selected`() {
        val result = MarkdownActions.setSize("hello world", 6, 11, 24)
        assertEquals("hello [world]{size=24}", result.text)
        assertEquals("world", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `setting a size with no selection leaves the caret inside the tag`() {
        // So that what is typed next comes out at the size that was asked for.
        val result = MarkdownActions.setSize("ab", 1, 1, 32)
        assertEquals("a[]{size=32}b", result.text)
        assertEquals(2, result.selectionStart)
        assertEquals(2, result.selectionEnd)
    }

    @Test
    fun `a second size replaces the first rather than nesting inside it`() {
        // Two sizes on one run of characters is a document with no answer.
        val source = "x [word]{size=24} y"
        val result = MarkdownActions.setSize(source, 3, 7, 12)
        assertEquals("x [word]{size=12} y", result.text)
    }

    @Test
    fun `going back to body size takes the tag with it`() {
        val source = "x [word]{size=24} y"
        val result = MarkdownActions.setSize(source, 3, 7, null)
        assertEquals("x word y", result.text)
        assertEquals("word", result.text.substring(result.selectionStart, result.selectionEnd))
    }

    @Test
    fun `the caret reports the size it is standing in`() {
        val source = "plain [big]{size=24} plain"
        assertNull(MarkdownActions.sizeAt(source, 2))
        assertEquals(24, MarkdownActions.sizeAt(source, 8))
        assertNull(MarkdownActions.sizeAt(source, source.length - 2))
    }

    @Test
    fun `the innermost size wins where two overlap`() {
        val source = "[a [b]{size=12} c]{size=24}"
        assertEquals(12, MarkdownActions.sizeAt(source, source.indexOf('b')))
        assertEquals(24, MarkdownActions.sizeAt(source, source.indexOf('c')))
    }

    @Test
    fun `a bracket that is not a size span is not mistaken for one`() {
        assertNull(MarkdownActions.sizeAt("[label](url)", 3))
        assertNull(MarkdownActions.sizeAt("an [unclosed bracket", 6))
    }

    // ---- Selecting text that is already sized -----------------------------------------------

    @Test
    fun `selecting a sized run reports its size and not the document's`() {
        // The tag is hidden, so selecting the words the reader can see hands back a range that
        // reaches over the syntax around them. Reading that as "no size" is what made the button
        // say Body while it was showing 32 point text.
        val source = "[big words]{size=32}"
        assertEquals(32, MarkdownActions.sizeIn(source, 0, source.length))
        assertEquals(32, MarkdownActions.sizeIn(source, 0, 11))
        assertEquals(32, MarkdownActions.sizeIn(source, 1, source.length))
    }

    @Test
    fun `a selection that swallows the tag is resized rather than wrapped a second time`() {
        val source = "[big words]{size=32}"
        assertEquals("[big words]{size=24}", MarkdownActions.setSize(source, 0, source.length, 24).text)
        assertEquals("big words", MarkdownActions.setSize(source, 0, source.length, null).text)
    }

    @Test
    fun `a selection over two sized runs reports one size only when they agree`() {
        assertEquals(20, MarkdownActions.sizeIn("[a]{size=20} [b]{size=20}", 0, 25))
        assertNull(MarkdownActions.sizeIn("[a]{size=20} [b]{size=32}", 0, 25))
        // Half in and half out has no one answer either: the second word is at the body size.
        assertNull(MarkdownActions.sizeIn("[a]{size=20} b", 0, 14))
    }

    @Test
    fun `sizing a selection that contains tags leaves one tag behind`() {
        val result = MarkdownActions.setSize("[a]{size=20} b [c]{size=12}", 0, 27, 16)
        assertEquals("[a b c]{size=16}", result.text)
    }

    @Test
    fun `each line of a selection is sized on its own`() {
        // The renderer scans inline syntax one line at a time, so a tag opened on one line and
        // closed on the next is not a tag — it is punctuation the reader can see.
        val result = MarkdownActions.setSize("one\ntwo", 0, 7, 18)
        assertEquals("[one]{size=18}\n[two]{size=18}", result.text)
        assertEquals(MarkdownRenderer.render("one\ntwo"), MarkdownRenderer.render(result.text))
    }

    @Test
    fun `a list item is sized past its bullet`() {
        // The marker is not prose: wrapping it would hide the bullet inside a span and stop the
        // line being a list item at all.
        val result = MarkdownActions.setSize("- item", 2, 6, 24)
        assertEquals("- [item]{size=24}", result.text)
    }
}
