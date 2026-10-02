package pl.dakil.notes.editor.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference machinery that keeps megabytes of base64 out of the caret's field.
 *
 * A warning before any of this is trusted: these tests run on the JVM, whose `Pattern` accepts
 * regexes Android's ICU rejects — the original `[^[]]` class compiled and every test passed while
 * the app crashed on open. The patterns' shape is checked in `ImageRefs.kt`, not here; these tests
 * pin the behaviour around them.
 */
class ImageRefsTest {

    private val payloads = mutableMapOf<String, String>()

    /** A payload well past [ImageRefs.THRESHOLD], made of characters the data-URI pattern accepts. */
    private fun payload(): String =
        "data:image/png;base64," + "A".repeat(ImageRefs.THRESHOLD * 2)

    private fun register(token: String, payload: String) {
        payloads[token] = payload
    }

    private fun answer(token: String): String? = payloads[token]

    @Test
    fun `a def line's payload is taken aside and expanded back byte for byte`() {
        val p = payload()
        val source = "Before.\n\n![Image 1][img-7]\n\nAfter.\n\n[img-7]: $p\n"

        val collapsed = ImageRefs.collapse(source, ::register)

        assertEquals(mapOf("image-ref:img-7" to p), payloads)
        assertEquals(
            "Before.\n\n![Image 1][img-7]\n\nAfter.\n\n[img-7]: image-ref:img-7\n",
            collapsed,
        )
        // The whole point of the feature: what the user wrote comes back unchanged.
        assertEquals(source, ImageRefs.expand(collapsed, ::answer))
    }

    @Test
    fun `an oversized inline image is rewritten to the reference form`() {
        val p = payload()
        val source = "Before.\n\n![Image 1]($p)\n\nAfter.\n"

        val collapsed = ImageRefs.collapseForPaste(source, ::register)

        val token = payloads.keys.single()
        val id = token.removePrefix(ImageRefs.TOKEN_PREFIX)
        assertEquals(p, payloads.values.single())
        assertEquals(
            "Before.\n\n![Image 1][$id]\n\nAfter.\n\n[$id]: $token",
            collapsed,
        )
        // Expanded, the definition carries the payload again, so the renderer can resolve it.
        assertEquals(
            "Before.\n\n![Image 1][$id]\n\nAfter.\n\n[$id]: $p",
            ImageRefs.expand(collapsed, ::answer),
        )
    }

    @Test
    fun `a bare payload pasted on its own becomes an image with a definition`() {
        val p = payload()

        val collapsed = ImageRefs.collapseForPaste(p, ::register)

        val token = payloads.keys.single()
        val id = token.removePrefix(ImageRefs.TOKEN_PREFIX)
        assertEquals("![Image 1][$id]\n\n[$id]: $token", collapsed)
    }

    @Test
    fun `a payload below the threshold stays exactly where it was written`() {
        val small = "data:image/png;base64,AAAA"

        assertEquals("![x]($small)", ImageRefs.collapse("![x]($small)", ::register))
        assertEquals("![x]($small)", ImageRefs.collapseForPaste("![x]($small)", ::register))
        assertTrue(payloads.isEmpty())
        assertEquals(null, ImageRefs.parseDataUri(small))
    }

    @Test
    fun `a definition-looking line inside a fence is code, not metadata`() {
        val source = "```kotlin\n[img-1]: ${payload()}\n```\n"

        assertEquals(source, ImageRefs.collapse(source, ::register))
        assertTrue(payloads.isEmpty())
    }

    @Test
    fun `an unanswered token survives expansion so an image is not silently lost`() {
        val markdown = "![x][img-9]\n\n[img-9]: image-ref:img-9\n"

        assertEquals(markdown, ImageRefs.expand(markdown) { null })
    }

    @Test
    fun `parseDataUri splits a real payload into mime type and bytes`() {
        val p = payload()

        assertEquals("image/png" to p.removePrefix("data:image/png;base64,"),
            ImageRefs.parseDataUri(p))
    }

    @Test
    fun `an oversized inline image on load is taken aside in place and expanded back byte for byte`() {
        val p = payload()
        val source = "Before.\n\n![Photo]($p)\n\nAfter.\n"

        val collapsed = ImageRefs.collapse(source, ::register)

        val token = payloads.keys.single()
        assertEquals(p, payloads.values.single())
        assertTrue(token, collapsed.contains("![Photo]($token)"))
        assertTrue(token, collapsed.length < 200)
        // The inline shape is preserved: only the payload between the parens was swapped.
        assertEquals(source, ImageRefs.expand(collapsed, ::answer))
    }

    @Test
    fun `two identical inline payloads collapse to one token`() {
        val p = payload()
        val source = "![a]($p) and ![b]($p)"

        val collapsed = ImageRefs.collapse(source, ::register)

        assertEquals(1, payloads.size)
        val token = payloads.keys.single()
        assertEquals("![a]($token) and ![b]($token)", collapsed)
        assertEquals(source, ImageRefs.expand(collapsed, ::answer))
    }

    @Test
    fun `an inline payload inside a fence is code, not an image`() {
        val source = "```\n![x](${payload()})\n```\n"

        assertEquals(source, ImageRefs.collapse(source, ::register))
        assertTrue(payloads.isEmpty())
    }

    @Test
    fun `an unanswered inline token survives expansion so an image is not silently lost`() {
        val markdown = "![x](image-ref:img-5)"

        assertEquals(markdown, ImageRefs.expand(markdown) { null })
    }

    @Test
    fun `def and inline shapes in one note both survive a collapse round trip`() {
        val p = payload()
        val source = "![Inline]($p)\n\nBody text.\n\n![Defined][img-3]\n\n[img-3]: $p\n"

        val collapsed = ImageRefs.collapse(source, ::register)

        // The def line keeps its own token; the inline mints one — same payload, two stand-ins.
        assertEquals(2, payloads.size)
        assertTrue(payloads.values.all { it == p })
        assertEquals(source, ImageRefs.expand(collapsed, ::answer))
    }
}
