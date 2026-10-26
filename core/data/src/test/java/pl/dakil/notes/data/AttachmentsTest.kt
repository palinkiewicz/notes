package pl.dakil.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentsTest {

    @Test
    fun `a note's attachments are found in inline links and in reference definitions`() {
        val markdown = """
            ![](attachments/aaa.png)
            text ![cat](attachments/bbb.jpg "A cat") more

            ![dog][d]

            [d]: attachments/ccc.webp
        """.trimIndent()
        assertEquals(setOf("aaa.png", "bbb.jpg", "ccc.webp"), Attachments.referencedIn(markdown))
    }

    @Test
    fun `images elsewhere are not attachments`() {
        assertEquals(emptySet<String>(), Attachments.referencedIn("![](https://x/attachments/a.png) ![](pics/b.png)"))
    }

    @Test
    fun `only a bare name directly inside the folder is followed`() {
        // A `..` or a nested path is nothing this app wrote, and following one reads whatever file
        // it happens to reach.
        assertEquals("a.png", Attachments.fileNameOf("attachments/a.png"))
        assertEquals("a.png", Attachments.fileNameOf("./attachments/a.png"))
        assertNull(Attachments.fileNameOf("attachments/../secret.md"))
        assertNull(Attachments.fileNameOf("attachments/sub/a.png"))
        assertNull(Attachments.fileNameOf("other/a.png"))
    }

    @Test
    fun `the link a note writes is the one it reads back`() {
        assertEquals("x.png", Attachments.fileNameOf(Attachments.linkTo("x.png")))
    }
}
