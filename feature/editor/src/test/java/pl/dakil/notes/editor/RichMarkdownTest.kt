package pl.dakil.notes.editor

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Markdown engine keeps, and what it throws away.
 *
 * The editor no longer edits Markdown source — it edits a document, and Markdown is what that
 * document is serialised to when the note is written. So the question that used to be answered by
 * reading the renderer ("what does this syntax look like") is now answered by a round-trip: open a
 * note, save it, and see what came back. Anything that does not survive that is data the user
 * loses, silently, on the first save.
 *
 * These tests are that round-trip. The ones asserting a *loss* are not describing desired behaviour
 * — they are pinning known limits of the library so that a version bump which changes one shows up
 * here rather than in somebody's notes.
 */
class RichMarkdownTest {

    /** A note opened and saved with nothing touched. */
    private fun roundTrip(markdown: String): String =
        RichTextState().apply { setMarkdown(markdown) }.toMarkdown()

    /** The document as the format bar would leave it, then written out. */
    private fun formatted(text: String, apply: RichTextState.() -> Unit): String =
        RichTextState().apply { setText(text); apply() }.toMarkdown()

    // ---- What the format bar offers, which must therefore survive a save ----------------------

    @Test
    fun `bold italic and strikethrough survive being saved and reopened`() {
        assertEquals(
            "**hello** world",
            formatted("hello world") { addSpanStyle(SpanStyle(fontWeight = FontWeight.Bold), TextRange(0, 5)) },
        )
        assertEquals(
            "*hello* world",
            formatted("hello world") { addSpanStyle(SpanStyle(fontStyle = FontStyle.Italic), TextRange(0, 5)) },
        )
        assertEquals(
            "~~hello~~ world",
            formatted("hello world") {
                addSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), TextRange(0, 5))
            },
        )
    }

    /**
     * Underline is the one control this engine added.
     *
     * CommonMark has no syntax for it, so it goes out as inline HTML — which is why it is worth a
     * test of its own: it is the only style whose Markdown is not Markdown, and a reader that
     * stripped raw HTML would silently drop it.
     */
    @Test
    fun `underline is written as inline HTML and read back as underline`() {
        val once = formatted("hello world") {
            addSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline), TextRange(0, 5))
        }
        assertEquals("<u>hello</u> world", once)
        assertEquals(once, roundTrip(once))
    }

    @Test
    fun `headings keep their level`() {
        assertEquals("# hello", formatted("hello") { setHeadingStyle(HeadingStyle.H1) })
        assertEquals("### hello", formatted("hello") { setHeadingStyle(HeadingStyle.H3) })
        assertEquals("###### hello", formatted("hello") { setHeadingStyle(HeadingStyle.H6) })
    }

    @Test
    fun `bulleted and numbered lists survive`() {
        assertEquals("- hello", formatted("hello") { toggleUnorderedList() })
        assertEquals("1. hello", formatted("hello") { toggleOrderedList() })
    }

    @Test
    fun `links keep their address`() {
        val md = formatted("see ") { addLink("docs", "https://example.org") }
        assertTrue(md, "https://example.org" in md)
        assertTrue(md, "docs" in md)
        // Reopening has to leave it a link, or the bar would light up on it once and never again.
        assertTrue(RichTextState().apply { setMarkdown(md) }.toMarkdown().contains("https://example.org"))
    }

    @Test
    fun `images keep their source`() {
        assertEquals("![alt text](img.png)", roundTrip("![alt text](img.png)"))
    }

    /**
     * Saving an untouched note twice must not keep changing it.
     *
     * The `.daknote` writer is asserted to be byte-stable, and a text box's Markdown is part of
     * what it writes. An engine whose output drifted on each save would rewrite every note on every
     * open, which for a library synced by git or Syncthing is a conflict a day.
     */
    @Test
    fun `a second save of an unchanged note produces the same text`() {
        val source = "# Heading\n\nSome **bold** and *italic* text.\n\n- one\n- two\n\n[link](https://x.dev)"
        val once = roundTrip(source)
        assertEquals(once, roundTrip(once))
    }

    // ---- Known losses, pinned deliberately -----------------------------------------------------

    /**
     * Font size, colour and highlight are real in the document and absent from the file.
     *
     * This is why the format bar offers none of the three: the style applies, the page looks right,
     * and reopening the note loses it. If a library version ever starts writing these, this test
     * fails and the controls can be put back.
     */
    @Test
    fun `size colour and highlight are dropped when the note is written`() {
        assertEquals("hello world", formatted("hello world") {
            addSpanStyle(SpanStyle(fontSize = 24.sp), TextRange(0, 5))
        })
        assertEquals("hello world", formatted("hello world") {
            addSpanStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Red), TextRange(0, 5))
        })
        assertEquals("hello world", formatted("hello world") {
            addSpanStyle(SpanStyle(background = androidx.compose.ui.graphics.Color.Yellow), TextRange(0, 5))
        })
    }

    /**
     * Block constructs this engine cannot model do not come back.
     *
     * Task lists lose their checkbox, quotes lose their marker, tables lose their structure, fenced
     * code loses its fence and its language, and rules vanish. Every one of these was supported by
     * the engine this replaced, so these assertions are the migration's cost written down.
     */
    @Test
    fun `task lists quotes tables fences and rules do not survive a round trip`() {
        assertEquals("- done", roundTrip("- [x] done"))

        val quote = roundTrip("> quoted")
        assertTrue(quote, !quote.startsWith(">"))

        val table = roundTrip("| a | b |\n|---|---|\n| 1 | 2 |")
        assertTrue(table, "|" !in table)

        val fence = roundTrip("```kotlin\nval x = 1\n```")
        assertTrue(fence, "```" !in fence && "kotlin" !in fence)

        assertTrue(roundTrip("---").isBlank())
    }

    /**
     * YAML frontmatter is not the body's problem, and must never be handed to this engine.
     *
     * A `.md` note's tags live in frontmatter, and the engine eats the `---` fences that delimit
     * it — turning every tag into a line of body text. `FrontmatterCodec` splits it off before the
     * body reaches the editor and puts it back on save, which is what keeps that from happening;
     * this test is here so that the consequence of skipping that step stays on record.
     */
    @Test
    fun `frontmatter fences would be eaten if the body were not split off first`() {
        val eaten = roundTrip("---\ntags: [a]\n---\n\nbody")
        assertTrue(eaten, !eaten.startsWith("---"))
    }
}
