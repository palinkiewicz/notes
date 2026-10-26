package pl.dakil.notes.editor.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataUriImagesTest {

    private val payload = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7, 6))
    private val uri = "data:image/png;base64,$payload"

    /** What the release that embedded images wrote: a tag at the caret, a definition at the foot. */
    private val embedded = "Before\n\n![Image 1][img-1]\n\nAfter\n\n[img-1]: $uri"

    @Test
    fun `an embedded image is found once with its type and bytes`() {
        val found = DataUriImages.find("$embedded\n\n![again]($uri)").single()
        assertEquals("image/png", found.mimeType)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), found.bytes())
    }

    @Test
    fun `replacing a payload with its link leaves everything else byte for byte`() {
        val replaced = DataUriImages.replace(embedded, mapOf(uri to "attachments/x.png"))
        assertEquals("Before\n\n![Image 1][img-1]\n\nAfter\n\n[img-1]: attachments/x.png", replaced)
    }

    @Test
    fun `a data URI inside a code fence is the sample's content and is left alone`() {
        val source = "```\n![a]($uri)\n```"
        assertTrue(DataUriImages.find(source).isEmpty())
        assertEquals(source, DataUriImages.replace(source, mapOf(uri to "x")))
    }

    @Test
    fun `stripping takes the image tag and its definition and keeps the prose`() {
        assertEquals("Before\n\n\nAfter", DataUriImages.strip(embedded))
    }

    @Test
    fun `stripping an inline embedded image keeps the sentence around it`() {
        assertEquals("a  b", DataUriImages.strip("a ![x]($uri) b"))
    }

    @Test
    fun `a note with nothing embedded is returned as it was`() {
        val plain = "# Title\n\n![](attachments/a.png)"
        assertTrue(DataUriImages.find(plain).isEmpty())
        assertEquals(plain, DataUriImages.strip(plain))
    }
}
