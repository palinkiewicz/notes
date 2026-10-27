package pl.dakil.notes.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCropTest {

    private val half = Rect(0.25f, 0.25f, 0.75f, 0.75f)

    @Test
    fun `a corner grip moves its two edges and leaves the other two where they were`() {
        val dragged = ImageCrop.resize(half, left = true, top = true, right = false, bottom = false, 0.1f, 0.05f, 0.05f, 0.05f)
        assertEquals(Rect(0.1f, 0.05f, 0.75f, 0.75f), dragged)
    }

    @Test
    fun `an edge grip moves its one edge`() {
        val dragged = ImageCrop.resize(half, left = false, top = false, right = true, bottom = false, 0.9f, 0.0f, 0.05f, 0.05f)
        assertEquals(Rect(0.25f, 0.25f, 0.9f, 0.75f), dragged)
    }

    @Test
    fun `an edge dragged past the one opposite it stops short instead of turning the crop inside out`() {
        val dragged = ImageCrop.resize(half, left = true, top = false, right = false, bottom = false, 0.95f, 0f, 0.1f, 0.1f)
        assertEquals(0.65f, dragged.left, 1e-6f)
        assertEquals(0.75f, dragged.right, 1e-6f)
    }

    @Test
    fun `an edge cannot be dragged past the edge of the picture`() {
        // There is nothing out there to show.
        val dragged = ImageCrop.resize(half, left = true, top = true, right = false, bottom = false, -0.4f, -1f, 0.05f, 0.05f)
        assertEquals(Rect(0f, 0f, 0.75f, 0.75f), dragged)
    }

    @Test
    fun `a crop too narrow to be one is widened to the least there can be`() {
        val clamped = ImageCrop.clamped(Rect(0.99f, 0.5f, 0.995f, 0.5f), 0.1f, 0.1f)
        assertEquals(Rect(0.9f, 0.5f, 1f, 0.6f), clamped)
    }

    @Test
    fun `a dragged grip on a mirrored picture moves along the picture, not the page`() {
        // Flipped left to right, a drag to the right on the page goes towards the picture's left
        // edge — which is the edge the grip under the finger belongs to.
        val picture = ImageBlock(
            id = BlockId("p"), z = 1, rect = Rect(0f, 0f, 200f, 100f),
            src = "media/a.png", mimeType = "image/png", bytes = byteArrayOf(),
        ).placedBy(Affine.scale(-1f, 1f, 100f, 0f))
        val out = FloatArray(2)
        picture.pictureDelta(20f, 10f, out)
        assertEquals(-0.1f, out[0], 1e-5f)
        assertEquals(0.1f, out[1], 1e-5f)
    }

    @Test
    fun `a dragged grip on a turned picture moves along the picture's own axes`() {
        val picture = ImageBlock(
            id = BlockId("p"), z = 1, rect = Rect(0f, 0f, 200f, 100f),
            src = "media/a.png", mimeType = "image/png", bytes = byteArrayOf(),
        ).placedBy(Affine.rotate((Math.PI / 2).toFloat()))
        // A quarter turn clockwise on the page: the picture's width now runs down the page.
        val out = FloatArray(2)
        picture.pictureDelta(0f, 50f, out)
        assertEquals(0.25f, out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-5f)
    }

    @Test
    fun `the pixels a crop decodes are rounded outwards so no visible edge is lost`() {
        assertArrayEquals(intArrayOf(33, 0, 67, 100), ImageCrop.pixelRegion(Rect(0.333f, 0f, 0.666f, 1f), 100, 100))
        // A fraction that lands on a whole pixel, give or take float noise, takes exactly that pixel.
        assertArrayEquals(intArrayOf(10, 20, 30, 40), ImageCrop.pixelRegion(Rect(0.1f, 0.2f, 0.3f, 0.4f), 100, 100))
    }

    @Test
    fun `a sliver of a crop still decodes a pixel of picture`() {
        assertArrayEquals(intArrayOf(99, 0, 100, 1), ImageCrop.pixelRegion(Rect(1f, 0f, 1f, 0f), 100, 100))
    }

    @Test
    fun `a tight crop is sampled by its own width, not the photograph's`() {
        // A tenth of a 4000-pixel photograph shown 400 pixels wide is decoded at full resolution;
        // sampling by the photograph's width would decode it a quarter as sharp as it is shown.
        assertEquals(1, ImageCrop.sampleFor(400, 400))
        assertEquals(8, ImageCrop.sampleFor(4000, 400))
        assertEquals(1, ImageCrop.sampleFor(4000, 0))
    }
}
