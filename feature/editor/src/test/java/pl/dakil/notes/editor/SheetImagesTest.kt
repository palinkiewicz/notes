package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.data.ImportedImage
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock

class SheetImagesTest {

    private val sheet = Sheet()
    private val format = sheet.format

    private fun imported(width: Int, height: Int) = ImportedImage(
        bytes = byteArrayOf(1),
        mimeType = "image/png",
        extension = "png",
        width = width,
        height = height,
        sha = "0011223344556677",
    )

    @Test
    fun `a photograph is clamped to the column and keeps its aspect`() {
        val rect = SheetImages.placement(sheet, format.contentLeft, 100f, 4000, 3000)
        assertEquals(format.contentRight, rect.right, 0.01f)
        assertEquals(0.75f, rect.height / rect.width, 0.001f)
    }

    @Test
    fun `a small image is not blown up to fill the page`() {
        val rect = SheetImages.placement(sheet, format.contentLeft, 100f, 200, 100)
        assertEquals(200 * SheetImages.PT_PER_PX, rect.width, 0.01f)
    }

    @Test
    fun `a picture placed near the foot of a page is shrunk to end on that page`() {
        // It belongs to the page its corner is on, and printing cuts it at that page's foot.
        val top = format.height - format.margins.bottom - 50f
        val rect = SheetImages.placement(sheet, format.contentLeft, top, 1000, 1000)
        assertTrue(rect.bottom <= format.height - format.margins.bottom + 0.01f)
        assertEquals(1f, rect.height / rect.width, 0.001f)
    }

    @Test
    fun `a tap outside the margins places the picture inside them`() {
        val rect = SheetImages.placement(sheet, -40f, 100f, 400, 400)
        assertEquals(format.contentLeft, rect.left, 0.01f)
    }

    @Test
    fun `an embedded image in a text box becomes a picture block and the box keeps its prose`() {
        val payload = java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 5))
        val box = TextBlock(
            id = BlockId("b1"), z = 3, rect = Rect(60f, 80f, 400f, 120f),
            markdown = "Hello\n\n![Image 1][img-1]\n\n[img-1]: data:image/png;base64,$payload",
        )
        val migrated = SheetImages.migrateEmbedded(Sheet(blocks = listOf(box))) { _, _ -> imported(100, 50) }

        assertEquals("Hello", migrated.blocks.filterIsInstance<TextBlock>().single().markdown)
        val picture = migrated.blocks.filterIsInstance<ImageBlock>().single()
        assertTrue(picture.rect.top > box.rect.bottom)
        assertEquals(60f, picture.rect.left, 0.01f)
    }

    @Test
    fun `a box that held nothing but an embedded image is replaced by the picture`() {
        val payload = java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 5))
        val box = TextBlock(
            id = BlockId("b1"), z = 3, rect = Rect(60f, 80f, 400f, 120f),
            markdown = "![Image 1][img-1]\n\n[img-1]: data:image/png;base64,$payload",
        )
        val migrated = SheetImages.migrateEmbedded(Sheet(blocks = listOf(box))) { _, _ -> imported(100, 50) }
        assertTrue(migrated.blocks.none { it is TextBlock })
        assertEquals(80f, migrated.blocks.filterIsInstance<ImageBlock>().single().rect.top, 0.01f)
    }

    @Test
    fun `a sheet with nothing embedded is returned as the same instance`() {
        // Which is how the editor tells it has nothing to save.
        val plain = Sheet(blocks = listOf(TextBlock(BlockId("b1"), 1, Rect(0f, 0f, 10f, 10f), markdown = "hi")))
        assertSame(plain, SheetImages.migrateEmbedded(plain) { _, _ -> error("not called") })
    }

    @Test
    fun `an image that cannot be read stays in the box rather than being lost`() {
        val box = TextBlock(
            id = BlockId("b1"), z = 3, rect = Rect(60f, 80f, 400f, 120f),
            markdown = "![x](data:image/png;base64,AAAA)",
        )
        val sheet = Sheet(blocks = listOf(box))
        assertSame(sheet, SheetImages.migrateEmbedded(sheet) { _, _ -> null })
    }
}
