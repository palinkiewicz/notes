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
    fun `a stretch along one axis stretches the picture, and keeps it a plain rectangle`() {
        // The edge grips exist to change proportions, so the picture goes with the ink round it.
        // Unturned, the stretch is all in the rect: the file stays as readable as it was.
        val placed = picture().placedBy(Affine.scale(2f, 0.5f, pivotX = 50f, pivotY = 100f))
        assertEquals(Rect(50f, 100f, 450f, 150f), placed.rect)
        assertTrue(placed.transform.isIdentity)
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

    /** Where the point a fraction ([u], [v]) across and down the picture is, on the page. */
    private fun Block.pixelAt(u: Float, v: Float): Pair<Float, Float> {
        val x = rect.left + u * rect.width
        val y = rect.top + v * rect.height
        return transform.mapX(x, y) to transform.mapY(x, y)
    }

    private fun assertCarried(before: ImageBlock, after: ImageBlock, m: Affine) {
        // Checked at three corners of the image, not just its box: a box in the right place showing
        // the picture the wrong way round is the mistake this guards against.
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 0.3f to 0.8f)) {
            val (x, y) = before.pixelAt(u, v)
            val (mx, my) = after.pixelAt(u, v)
            assertEquals(m.mapX(x, y), mx, 0.01f)
            assertEquals(m.mapY(x, y), my, 0.01f)
        }
    }

    @Test
    fun `mirroring a picture puts every pixel where the mirror sends it`() {
        val before = picture().placedBy(Affine.rotate(0.3f, 50f, 100f))
        for (m in listOf(Affine.scale(-1f, 1f, 200f, 0f), Affine.scale(1f, -1f, 0f, 300f))) {
            assertCarried(before, before.placedBy(m), m)
        }
    }

    @Test
    fun `a turned picture stretched along the page is sheared, exactly as the ink round it is`() {
        val before = picture().placedBy(Affine.rotate(0.5f, 50f, 100f))
        val m = Affine.scale(1.8f, 1f, 50f, 100f)
        assertCarried(before, before.placedBy(m), m)
    }

    @Test
    fun `a picture mirrored twice is the picture it was`() {
        // Upright and unflipped again, so the descriptor goes back to what it was before.
        val m = Affine.scale(1f, -1f, 0f, 300f)
        val back = picture().placedBy(m).placedBy(m)
        assertTrue(back.transform.isIdentity)
        assertEquals(picture().rect.left, back.rect.left, 0.01f)
        assertEquals(picture().rect.top, back.rect.top, 0.01f)
    }

    private fun box() = TextBlock(BlockId("t"), 1, Rect(0f, 0f, 300f, 40f), markdown = "hello")

    @Test
    fun `a mirrored text box lands on its mirror image and still reads left to right`() {
        // Mirrored words are not words. The box goes where its mirror image would be and turns the
        // mirrored way, but the text in it is not flipped — for either of the page's mirrors.
        val turned = box().carriedBy(Affine.rotate(0.2f))
        for (m in listOf(Affine.scale(-1f, 1f, 400f, 0f), Affine.scale(1f, -1f, 0f, 500f))) {
            val flipped = turned.carriedBy(m)
            assertEquals(-0.2f, flipped.placement().angle, 1e-4f)
            assertTrue("reads forwards", flipped.transform.let { it.a * it.d - it.b * it.c } > 0f)
            val was = turned.worldBounds()
            val now = flipped.worldBounds()
            assertEquals(m.mapX((was.left + was.right) / 2f, (was.top + was.bottom) / 2f), (now.left + now.right) / 2f, 0.01f)
            assertEquals(m.mapY((was.left + was.right) / 2f, (was.top + was.bottom) / 2f), (now.top + now.bottom) / 2f, 0.01f)
            assertEquals(300f, flipped.rect.width, 0.01f)
        }
    }

    @Test
    fun `a stretched text box stretches its letters but breaks its lines where it did`() {
        // The width in the rect is where the lines wrap; the stretch is drawn, not reflowed.
        val stretched = box().carriedBy(Affine.scale(2f, 1f, 0f, 0f))
        assertEquals(300f, stretched.rect.width, 0.01f)
        assertEquals(600f, stretched.worldBounds().right, 0.01f)
        assertEquals(40f, stretched.worldBounds().bottom, 0.01f)
    }

    @Test
    fun `an even scale still moves a text box without growing its words`() {
        // The corner grips' rule, unchanged by the edge grips': only the corner is carried.
        val scaled = box().carriedBy(Affine.scale(3f, 3f, 600f, 0f))
        assertTrue(scaled.transform.isIdentity)
        assertEquals(-1200f, scaled.rect.left, 0.01f)
        assertEquals(300f, scaled.rect.width, 0.01f)
    }

    /** Where the point a fraction ([u], [v]) across and down the *whole* picture is, on the page. */
    private fun ImageBlock.sourceAt(u: Float, v: Float): Pair<Float, Float> {
        val full = uncroppedRect()
        val x = full.left + u * full.width
        val y = full.top + v * full.height
        return transform.mapX(x, y) to transform.mapY(x, y)
    }

    private fun assertSamePixels(before: ImageBlock, after: ImageBlock) {
        // A crop takes away from the edges and moves nothing: any one pixel of the picture, shown or
        // not, has to be where it was on the page.
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 0.3f to 0.8f)) {
            val (x, y) = before.sourceAt(u, v)
            val (cx, cy) = after.sourceAt(u, v)
            assertEquals(x, cx, 0.01f)
            assertEquals(y, cy, 0.01f)
        }
    }

    @Test
    fun `cropping keeps every pixel of the picture where it was on the page`() {
        val cropped = picture().croppedTo(Rect(0.25f, 0.1f, 0.75f, 0.6f))
        assertSamePixels(picture(), cropped)
        assertEquals(Rect(100f, 110f, 200f, 160f), cropped.rect)
    }

    @Test
    fun `cropping a turned picture keeps its pixels in place, and its turn`() {
        // The rect's corner is the pivot the turn is written about, so moving the corner without
        // rewriting the turn would swing the whole picture round somewhere else.
        val turned = picture().placedBy(Affine.rotate(quarter, pivotX = 150f, pivotY = 150f))
        val cropped = turned.croppedTo(Rect(0.2f, 0.3f, 0.9f, 1f))
        assertSamePixels(turned, cropped)
        assertEquals(turned.placement().angle, cropped.placement().angle, 1e-4f)
    }

    @Test
    fun `cropping a mirrored picture keeps its pixels in place`() {
        val mirrored = picture().placedBy(Affine.scale(-1f, 1f, 200f, 0f))
        assertSamePixels(mirrored, mirrored.croppedTo(Rect(0f, 0.5f, 0.4f, 1f)))
    }

    @Test
    fun `taking a crop off gives back the picture as it was`() {
        val back = picture().croppedTo(Rect(0.25f, 0.1f, 0.75f, 0.6f)).croppedTo(ImageBlock.UNCROPPED)
        assertEquals(picture().rect.left, back.rect.left, 0.01f)
        assertEquals(picture().rect.top, back.rect.top, 0.01f)
        assertEquals(picture().rect.right, back.rect.right, 0.01f)
        assertEquals(picture().rect.bottom, back.rect.bottom, 0.01f)
        assertTrue(!back.isCropped)
    }

    @Test
    fun `a crop cannot leave a picture too small to take hold of`() {
        val cropped = picture().croppedTo(Rect(0.5f, 0.5f, 0.5001f, 0.5001f))
        assertTrue(cropped.rect.width >= MIN_IMAGE_SIDE - 0.01f)
        assertTrue(cropped.rect.height >= MIN_IMAGE_SIDE - 0.01f)
    }

    @Test
    fun `a cropped picture keeps its crop through a scale and a mirror`() {
        // The crop is a fraction of the picture, so a selection scaled round it changes how large
        // the part shown is drawn, never which part it is.
        val cropped = picture().croppedTo(Rect(0.25f, 0.1f, 0.75f, 0.6f))
        val scaled = cropped.placedBy(Affine.scale(2f, 2f, 100f, 110f))
        assertEquals(cropped.crop, scaled.crop)
        assertEquals(200f, scaled.rect.width, 0.01f)
        val m = Affine.scale(1f, -1f, 0f, 300f)
        val mirrored = cropped.placedBy(m)
        assertEquals(cropped.crop, mirrored.crop)
        assertCarried(cropped, mirrored, m)
    }

    @Test
    fun `equality notices a change of crop`() {
        assertNotEquals(picture(), picture().copy(crop = Rect(0f, 0f, 0.5f, 1f)))
    }
}
