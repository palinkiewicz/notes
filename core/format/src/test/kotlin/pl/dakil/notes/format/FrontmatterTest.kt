package pl.dakil.notes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontmatterTest {

    @Test
    fun `a note without frontmatter is all body`() {
        val note = "# Groceries\n\nMilk\n"

        val parsed = FrontmatterCodec.parse(note)

        assertFalse(parsed.present)
        assertEquals(emptyList<String>(), parsed.tags)
        assertEquals(note, parsed.body)
    }

    @Test
    fun `tags are read from the flow form`() {
        val parsed = FrontmatterCodec.parse("---\ntags: [work, ideas]\n---\n\n# Notes\n")

        assertEquals(listOf("work", "ideas"), parsed.tags)
        // The blank line between the block and the text belongs to the block's spacing, not to the
        // note, so it is not part of what the editor puts a caret in.
        assertEquals("# Notes\n", parsed.body)
    }

    @Test
    fun `tags are read from the block form other editors write`() {
        val parsed = FrontmatterCodec.parse("---\ntags:\n  - work\n  - ideas\n---\nbody\n")

        assertEquals(listOf("work", "ideas"), parsed.tags)
        assertEquals("body\n", parsed.body)
    }

    @Test
    fun `a comma inside a quoted tag does not split it`() {
        val parsed = FrontmatterCodec.parse("---\ntags: [\"one, two\", three]\n---\n")

        assertEquals(listOf("one, two", "three"), parsed.tags)
    }

    @Test
    fun `keys this app does not understand survive a rewrite`() {
        // The whole reason for keeping a remainder: a note that came from Obsidian goes back to it
        // with its aliases intact, rather than losing them the first time it is tagged here.
        val note = "---\naliases: [Shopping]\ntags: [old]\ncssclass: wide\n---\nbody\n"

        val written = FrontmatterCodec.withTags(note, listOf("new"))

        // The blank line is the canonical spacing this app writes; a file that arrived without one
        // gains it here and then never changes again.
        assertEquals("---\ntags: [new]\naliases: [Shopping]\ncssclass: wide\n---\n\nbody\n", written)
        assertEquals(listOf("new"), FrontmatterCodec.parse(written).tags)
    }

    @Test
    fun `clearing the last tag removes the block rather than leaving an empty one`() {
        // An empty `---\n---` at the top of a file is something every other Markdown renderer draws
        // as a horizontal rule, so leaving one behind is visible damage.
        val written = FrontmatterCodec.withTags("---\ntags: [work]\n---\n\n# Notes\n", emptyList())

        assertEquals("# Notes\n", written)
    }

    @Test
    fun `tagging a file that had no frontmatter adds a block and keeps the text apart from it`() {
        val written = FrontmatterCodec.withTags("# Notes\n", listOf("work"))

        assertEquals("---\ntags: [work]\n---\n\n# Notes\n", written)
    }

    @Test
    fun `an unterminated block is body text, not a block`() {
        // A note whose first line happens to be a horizontal rule must not have the rest of itself
        // eaten as frontmatter.
        val note = "---\nnot really frontmatter\n\nstill the note\n"

        val parsed = FrontmatterCodec.parse(note)

        assertFalse(parsed.present)
        assertEquals(note, parsed.body)
    }

    @Test
    fun `a body kept apart from its block goes back together unchanged`() {
        // The editor holds only the body, so this round trip is what every save of a tagged note
        // goes through.
        val note = "---\naliases: [Shopping]\ntags: [work]\n---\n\n# Notes\n"
        val parsed = FrontmatterCodec.parse(note)

        val written = FrontmatterCodec.render(parsed.tags, parsed.remainder, parsed.body)

        assertEquals("---\ntags: [work]\naliases: [Shopping]\n---\n\n# Notes\n", written)
        assertEquals(written, FrontmatterCodec.withTags(written, parsed.tags))
    }

    @Test
    fun `a tag with a comma in it survives a round trip`() {
        val written = FrontmatterCodec.withTags("body", listOf("one, two"))

        assertTrue(written.startsWith("---\ntags: [\"one, two\"]\n---\n"))
        assertEquals(listOf("one, two"), FrontmatterCodec.parse(written).tags)
    }

    @Test
    fun `writing a note this app already wrote changes not one byte`() {
        // The `.daknote` writer holds itself to this and a `.md` note in a synced folder deserves
        // the same: a save that rewrites spacing shows up as a conflict on someone's other device.
        val note = FrontmatterCodec.withTags("# Notes\n\nBody.\n", listOf("work", "ideas"))

        assertEquals(note, FrontmatterCodec.withTags(note, listOf("work", "ideas")))
    }

    @Test
    fun `duplicate tags are written once`() {
        val written = FrontmatterCodec.withTags("body", listOf("work", "work", " work "))

        assertEquals(listOf("work"), FrontmatterCodec.parse(written).tags)
    }
}
