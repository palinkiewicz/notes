package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBlockTest {

    private val pageHeight = PageSize.A4.height

    private fun picture(top: Float = 100f, id: String = "p") = ImageBlock(
        id = BlockId(id),
        z = 1,
        rect = Rect(50f, top, 250f, top + 100f),
        src = "media/abc.png",
        mimeType = "image/png",
        bytes = byteArrayOf(1, 2, 3),
    )

    @Test
    fun `a corner drag that scales the axes apart still keeps the picture's aspect`() {
        // A picture pulled out of its aspect is a picture of something else.
        val placed = picture().placedBy(Affine.scale(2f, 0.5f, pivotX = 50f, pivotY = 100f))
        assertEquals(2f, placed.rect.width / placed.rect.height, 0.001f)
        // The area the drag asked for: 2 × 0.5 is no change at all.
        assertEquals(200f, placed.rect.width, 0.01f)
    }

    @Test
    fun `an even scale about the top-left grows the picture from that corner`() {
        val placed = picture().placedBy(Affine.scale(1.5f, 1.5f, pivotX = 50f, pivotY = 100f))
        assertEquals(Rect(50f, 100f, 350f, 250f), placed.rect)
    }

    @Test
    fun `a picture cannot be scaled to nothing`() {
        val placed = picture().placedBy(Affine.scale(0.001f, 0.001f))
        assertTrue(placed.rect.width >= MIN_IMAGE_SIDE)
        assertTrue(placed.rect.height >= MIN_IMAGE_SIDE)
    }

    @Test
    fun `a picture moves with the page it is on`() {
        val sheet = Sheet(blocks = listOf(picture(top = 100f))).withPages(3)
        val swapped = sheet.withPagesSwapped(0, 2)
        val moved = swapped.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(pageHeight * 2 + 100f, moved.rect.top, 0.01f)
    }

    @Test
    fun `equality goes by the picture's name rather than its bytes`() {
        // The name is derived from the content; comparing a photograph byte for byte on every edit
        // to the sheet would be paid for nothing.
        assertEquals(picture(), picture().copy(bytes = byteArrayOf(1, 2, 3)))
        assertNotEquals(picture(), picture().copy(src = "media/other.png"))
    }

    private val quarter = (Math.PI / 2).toFloat()

    @Test
    fun `turning a selection turns the picture with it, about its own corner`() {
        // What used to happen: the picture's corner was carried round and the picture stayed upright.
        val turned = picture().placedBy(Affine.rotate(quarter, pivotX = 150f, pivotY = 150f))
        val at = turned.placement()
        assertEquals(quarter, at.angle, 1e-4f)
        // The corner went where the rotation sends it, and the size did not change.
        assertEquals(200f, at.left, 0.01f)
        assertEquals(50f, at.top, 0.01f)
        assertEquals(200f, turned.rect.width, 0.01f)
    }

    @Test
    fun `turning a text box turns its words and never scales them`() {
        val box = TextBlock(BlockId("t"), 1, Rect(0f, 0f, 300f, 40f), markdown = "hello")
        val turned = box.carriedBy(Affine.rotate(quarter).then(Affine.scale(2f, 2f)))
        assertEquals(quarter, turned.placement().angle, 1e-4f)
        assertEquals(300f, turned.rect.width, 0.01f)
    }

    @Test
    fun `a full turn leaves no transform behind`() {
        // Upright is written as no transform at all, so it stays byte-identical to an unturned box.
        val once = picture().placedBy(Affine.rotate(quarter * 2, 150f, 150f))
        val back = once.placedBy(Affine.rotate(quarter * 2, 150f, 150f))
        assertTrue(back.transform.isIdentity)
        assertEquals(50f, back.rect.left, 0.01f)
    }

    @Test
    fun `a turned picture moved with its page stays turned, and its rect moves with it`() {
        // The rect is what the handles and the live editor read; it has to stay where the block is.
        val turned = picture().placedBy(Affine.rotate(0.3f, 50f, 100f))
        val moved = turned.translated(0f, pageHeight) as ImageBlock
        assertEquals(turned.placement().angle, moved.placement().angle, 1e-4f)
        assertEquals(turned.placement().top + pageHeight, moved.placement().top, 0.01f)
        assertEquals(moved.placement().top, moved.rect.top, 0.01f)
    }
}
